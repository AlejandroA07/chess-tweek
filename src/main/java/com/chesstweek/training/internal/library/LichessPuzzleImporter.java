package com.chesstweek.training.internal.library;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.internal.library.LichessPuzzleRecords.Candidate;
import com.chesstweek.training.internal.library.process.ZstdInput;
import com.chesstweek.training.internal.library.process.ZstdLocator;

@Component
final class LichessPuzzleImporter {

    private static final String PROVIDER = LichessPuzzleRecords.PROVIDER;
    private static final String HEADER = LichessPuzzleRecords.HEADER;
    private static final long MAX_COMPRESSED_BYTES = 10L * 1024 * 1024 * 1024;
    private static final long MAX_DECOMPRESSED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_RECORD_BYTES = LichessPuzzleRecords.MAX_RECORD_BYTES;
    private static final int MAX_OPENING_CLASSIFICATIONS = 1000;
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,255}\\.(?:csv|zst)");
    private static final Pattern CHECKSUM = Pattern.compile("[a-f0-9]{64}");
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingLong(Candidate::score).reversed()
            .thenComparing(Candidate::externalId);
    private static final Comparator<Candidate> WORST_FIRST = BEST_FIRST.reversed();

    private final Clock clock;
    private final ZstdLocator zstdLocator;

    @Autowired
    LichessPuzzleImporter(ZstdLocator zstdLocator) {
        this(Clock.systemUTC(), zstdLocator);
    }

    LichessPuzzleImporter(Clock clock, ZstdLocator zstdLocator) {
        this.clock = clock;
        this.zstdLocator = zstdLocator;
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
            BoundedLineReader lines = new BoundedLineReader(input, MAX_DECOMPRESSED_BYTES, MAX_RECORD_BYTES);
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
                            : LichessPuzzleRecords.displayName(key.key()),
                    valid.target(),
                    ordered.stream().map(candidate -> new PuzzleImportBatch.Member(
                            PROVIDER, candidate.externalId(), candidate.score())).toList()));
        }

        List<PuzzleImportBatch.PuzzleRow> puzzles = selected.values().stream()
                .map(LichessPuzzleRecords::validate)
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
        Candidate candidate = LichessPuzzleRecords.parse(line);
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

    private InputStream open(Path file) {
        try {
            if (file.getFileName().toString().endsWith(".csv")) {
                return Files.newInputStream(file);
            }
            Path zstd = zstdLocator.locate().orElseThrow(
                    () -> new IllegalArgumentException("The zstd prerequisite is not installed"));
            return ZstdInput.open(zstd, file);
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
                kind, key, parent, LichessPuzzleRecords.displayName(key), "Openings", true));
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

    private record ValidRequest(
            Path file,
            LocalDate snapshotDate,
            String checksum,
            int minimumRating,
            int maximumRating,
            int target) {}

    private record CollectionKey(PuzzleImportBatch.Kind kind, String key) {}
}
