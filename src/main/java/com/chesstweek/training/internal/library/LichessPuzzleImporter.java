package com.chesstweek.training.internal.library;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.internal.library.process.ZstdInput;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

@Component
final class LichessPuzzleImporter {

    private static final String PROVIDER = "lichess-puzzle-database";
    private static final String HEADER =
            "PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags,DailyDate";
    private static final long MAX_COMPRESSED_BYTES = 10L * 1024 * 1024 * 1024;
    private static final long MAX_DECOMPRESSED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 4096;
    private static final int MAX_OPENING_CLASSIFICATIONS = 1000;
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,255}\\.(?:csv|zst)");
    private static final Pattern CHECKSUM = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9]{5,8}");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern THEME = Pattern.compile("[A-Za-z0-9]{1,40}");
    private static final Pattern OPENING = Pattern.compile("[A-Za-z0-9_]{1,120}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingLong(Candidate::score).reversed()
            .thenComparing(Candidate::externalId);
    private static final Comparator<Candidate> WORST_FIRST = BEST_FIRST.reversed();

    private final Clock clock;

    LichessPuzzleImporter() {
        this(Clock.systemUTC());
    }

    LichessPuzzleImporter(Clock clock) {
        this.clock = clock;
    }

    PuzzleImportBatch read(PuzzleLibrary.ImportRequest request, Map<String, String> visibleThemes) {
        ValidRequest valid = validate(request);
        String actualChecksum = sha256(valid.file());
        if (valid.checksum() != null && !valid.checksum().equals(actualChecksum)) {
            throw new IllegalArgumentException("Puzzle snapshot checksum does not match");
        }
        String checksum = actualChecksum;
        Map<CollectionKey, PriorityQueue<Candidate>> candidates = new LinkedHashMap<>();
        Map<String, PuzzleImportBatch.Classification> openings = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();

        try (InputStream input = new BufferedInputStream(open(valid.file()), 64 * 1024)) {
            BoundedLines lines = new BoundedLines(input);
            String first = lines.next();
            boolean hasHeader = HEADER.equals(first);
            if (!hasHeader && first != null) {
                accept(first, valid, visibleThemes, candidates, openings, seenIds);
            }
            String line;
            while ((line = lines.next()) != null) {
                accept(line, valid, visibleThemes, candidates, openings, seenIds);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Puzzle snapshot could not be read", exception);
        }

        Map<String, Candidate> selected = new LinkedHashMap<>();
        List<PuzzleImportBatch.Collection> collections = new ArrayList<>();
        for (Map.Entry<CollectionKey, PriorityQueue<Candidate>> entry : candidates.entrySet()) {
            List<Candidate> ordered = entry.getValue().stream().sorted(BEST_FIRST).toList();
            ordered.forEach(candidate -> selected.putIfAbsent(candidate.externalId(), candidate));
            CollectionKey key = entry.getKey();
            collections.add(new PuzzleImportBatch.Collection(
                    key.kind(), key.key(),
                    key.kind() == PuzzleImportBatch.Kind.THEME
                            ? visibleThemes.get(key.key())
                            : displayName(key.key()),
                    valid.target(),
                    ordered.stream().map(candidate -> new PuzzleImportBatch.Member(
                            PROVIDER, candidate.externalId(), candidate.score())).toList()));
        }

        List<PuzzleImportBatch.PuzzleRow> puzzles = selected.values().stream()
                .map(LichessPuzzleImporter::validateAndMap)
                .toList();
        var snapshot = new PuzzleImportBatch.Snapshot(
                PROVIDER, valid.snapshotDate(), checksum, "puzzle-library-v1",
                clock.instant().toEpochMilli());
        return new PuzzleImportBatch(
                snapshot,
                puzzles,
                List.copyOf(openings.values()),
                List.copyOf(collections),
                List.of());
    }

    private static void accept(
            String line,
            ValidRequest request,
            Map<String, String> visibleThemes,
            Map<CollectionKey, PriorityQueue<Candidate>> buckets,
            Map<String, PuzzleImportBatch.Classification> openings,
            Set<String> seenIds) {
        if (line.isBlank()) {
            return;
        }
        String[] fields = line.split(",", -1);
        if (fields.length != 11) {
            throw new IllegalArgumentException("Puzzle snapshot contains a malformed record");
        }
        Candidate candidate = parse(fields);
        if (!seenIds.add(candidate.externalId())) {
            throw new IllegalArgumentException("Puzzle snapshot contains a duplicate puzzle ID");
        }
        if (candidate.rating() < request.minimumRating()
                || candidate.rating() > request.maximumRating()
                || candidate.ratingDeviation() > 90
                || candidate.popularity() < 80
                || candidate.playCount() < 200) {
            return;
        }
        add(buckets, new CollectionKey(PuzzleImportBatch.Kind.THEME, "mix"), candidate, request.target());
        for (String theme : candidate.themes()) {
            if (visibleThemes.containsKey(theme)) {
                add(buckets, new CollectionKey(PuzzleImportBatch.Kind.THEME, theme), candidate, request.target());
            }
        }
        if (!candidate.openingTags().isEmpty()) {
            String family = candidate.openingTags().getFirst();
            registerOpening(openings, family, null, PuzzleImportBatch.Kind.OPENING_FAMILY);
            add(buckets, new CollectionKey(PuzzleImportBatch.Kind.OPENING_FAMILY, family),
                    candidate, request.target());
            for (int index = 1; index < candidate.openingTags().size(); index++) {
                String variation = candidate.openingTags().get(index);
                if (registerOpening(openings, variation, family,
                        PuzzleImportBatch.Kind.OPENING_VARIATION)) {
                    add(buckets, new CollectionKey(PuzzleImportBatch.Kind.OPENING_VARIATION, variation),
                            candidate, request.target());
                }
            }
        }
    }

    private static Candidate parse(String[] fields) {
        String id = fields[0];
        if (!EXTERNAL_ID.matcher(id).matches()
                || fields[1].length() < 20 || fields[1].length() > 120
                || !lichessUrl(fields[8])) {
            throw new IllegalArgumentException("Puzzle snapshot contains invalid identity data");
        }
        List<String> moves = tokens(fields[2], UCI, 2, 120, "moves");
        List<String> themes = tokens(fields[7], THEME, 1, 30, "themes");
        List<String> openingTags = tokens(fields[9], OPENING, 0, 16, "opening tags");
        LocalDate dailyDate = null;
        if (!fields[10].isBlank()) {
            if (!DATE.matcher(fields[10]).matches()) {
                throw new IllegalArgumentException("Puzzle snapshot contains invalid daily date");
            }
            try {
                dailyDate = LocalDate.parse(fields[10]);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(
                        "Puzzle snapshot contains invalid daily date", exception);
            }
        }
        int rating = integer(fields[3], 400, 4000, "rating");
        int deviation = integer(fields[4], 0, 500, "rating deviation");
        int popularity = integer(fields[5], -100, 100, "popularity");
        int plays = integer(fields[6], 0, Integer.MAX_VALUE, "play count");
        long score = (long) popularity * 1_000_000_000L
                + Math.min(plays, 999_999_999)
                - (long) deviation * 10_000_000L;
        return new Candidate(id, fields[1], moves, rating, deviation, popularity, plays,
                themes, openingTags, fields[8], dailyDate, score);
    }

    private static PuzzleImportBatch.PuzzleRow validateAndMap(Candidate candidate) {
        try {
            ChessGame game = new ChessGame(candidate.sourceFen());
            String positionFen = null;
            for (int index = 0; index < candidate.moves().size(); index++) {
                String uci = candidate.moves().get(index);
                Move move = game.getMove(NotationType.UCI, uci);
                if (move == null || !game.isLegalMove(move)) {
                    throw new IllegalArgumentException("Puzzle snapshot contains an illegal move line");
                }
                game.playMove(move);
                if (index == 0) {
                    positionFen = game.getFen();
                }
            }
            return new PuzzleImportBatch.PuzzleRow(
                    PROVIDER, candidate.externalId(), candidate.sourceFen(), candidate.moves(),
                    candidate.moves().getFirst(), positionFen,
                    candidate.moves().subList(1, candidate.moves().size()), candidate.gameUrl(),
                    "CC0-1.0", candidate.rating(), candidate.ratingDeviation(),
                    candidate.popularity(), candidate.playCount(), candidate.themes(),
                    candidate.openingTags(), candidate.dailyDate());
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("Puzzle snapshot contains an invalid position", exception);
        }
    }

    private static ValidRequest validate(PuzzleLibrary.ImportRequest request) {
        if (request == null || request.approvedRoot() == null || request.fileName() == null
                || request.snapshotDate() == null
                || !FILE_NAME.matcher(request.fileName()).matches()
                || !Path.of(request.fileName()).getFileName().toString().equals(request.fileName())
                || request.minimumRating() < 400 || request.maximumRating() > 4000
                || request.minimumRating() > request.maximumRating()
                || request.targetPerCollection() < 20 || request.targetPerCollection() > 100
                || (request.downloadChecksum() != null
                        && !CHECKSUM.matcher(request.downloadChecksum()).matches())) {
            throw new IllegalArgumentException("Puzzle import request is invalid");
        }
        try {
            Path configuredRoot = request.approvedRoot().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(configuredRoot) || !Files.isDirectory(configuredRoot)) {
                throw new IllegalArgumentException("Approved import root is invalid");
            }
            Path root = configuredRoot.toRealPath();
            Path file = root.resolve(request.fileName()).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Puzzle snapshot file is invalid");
            }
            Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realFile.startsWith(root) || Files.size(realFile) > MAX_COMPRESSED_BYTES) {
                throw new IllegalArgumentException("Puzzle snapshot file is invalid");
            }
            return new ValidRequest(
                    realFile, request.snapshotDate(), request.downloadChecksum(),
                    request.minimumRating(), request.maximumRating(), request.targetPerCollection());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Puzzle snapshot file is invalid", exception);
        }
    }

    private static InputStream open(Path file) {
        try {
            if (file.getFileName().toString().endsWith(".csv")) {
                return Files.newInputStream(file);
            }
            return ZstdInput.open(file);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Puzzle snapshot could not be opened", exception);
        }
    }

    private static String sha256(Path file) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("Puzzle snapshot checksum could not be calculated", exception);
        }
    }

    private static boolean registerOpening(
            Map<String, PuzzleImportBatch.Classification> openings,
            String key,
            String parent,
            PuzzleImportBatch.Kind kind) {
        if (openings.containsKey(key)) {
            return true;
        }
        if (openings.size() >= MAX_OPENING_CLASSIFICATIONS) {
            return false;
        }
        openings.put(key, new PuzzleImportBatch.Classification(
                kind, key, parent, displayName(key), "Openings", true));
        return true;
    }

    private static void add(
            Map<CollectionKey, PriorityQueue<Candidate>> buckets,
            CollectionKey key,
            Candidate candidate,
            int target) {
        PriorityQueue<Candidate> queue = buckets.computeIfAbsent(
                key, ignored -> new PriorityQueue<>(WORST_FIRST));
        queue.add(candidate);
        if (queue.size() > target) {
            queue.remove();
        }
    }

    private static List<String> tokens(
            String value, Pattern pattern, int minimum, int maximum, String name) {
        List<String> values = value.isBlank() ? List.of() : List.of(value.split(" "));
        if (values.size() < minimum || values.size() > maximum
                || new HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(token -> !pattern.matcher(token).matches())) {
            throw new IllegalArgumentException("Puzzle snapshot contains invalid " + name);
        }
        return values;
    }

    private static int integer(String value, int minimum, int maximum, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Puzzle snapshot contains invalid " + name, exception);
        }
    }

    private static String displayName(String key) {
        String words = key.replace('_', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    private static boolean lichessUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "lichess.org".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record ValidRequest(
            Path file,
            LocalDate snapshotDate,
            String checksum,
            int minimumRating,
            int maximumRating,
            int target) {}

    private record Candidate(
            String externalId,
            String sourceFen,
            List<String> moves,
            int rating,
            int ratingDeviation,
            int popularity,
            int playCount,
            List<String> themes,
            List<String> openingTags,
            String gameUrl,
            LocalDate dailyDate,
            long score) {}

    private record CollectionKey(PuzzleImportBatch.Kind kind, String key) {}

    private static final class BoundedLines {
        private final InputStream input;
        private long total;

        private BoundedLines(InputStream input) {
            this.input = input;
        }

        String next() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            int value;
            while ((value = input.read()) != -1) {
                total++;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("Puzzle snapshot expands beyond the allowed size");
                }
                if (value == '\n') {
                    break;
                }
                if (value != '\r') {
                    if (line.size() >= MAX_RECORD_BYTES) {
                        throw new IllegalArgumentException("Puzzle snapshot record is too long");
                    }
                    line.write(value);
                }
            }
            if (value == -1 && line.size() == 0) {
                return null;
            }
            return line.toString(StandardCharsets.UTF_8);
        }
    }
}
