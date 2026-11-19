package com.chesstweek.training.internal.library;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

/**
 * The one parser and validator for official Lichess Puzzle CSV records. Every record is checked for
 * bounded identity, ratings, tags, and a fully legal move line before it may reach the library.
 */
public final class LichessPuzzleRecords {

    public static final String PROVIDER = "lichess-puzzle-database";
    public static final String LICENSE = "CC0-1.0";
    public static final String HEADER =
            "PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags,DailyDate";
    public static final int MAX_RECORD_BYTES = 4096;

    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9]{5,8}");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern THEME = Pattern.compile("[A-Za-z0-9]{1,40}");
    private static final Pattern OPENING = Pattern.compile("[A-Za-z0-9_-]{1,120}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern EPOCH_MILLIS = Pattern.compile("[0-9]{12,14}");
    private static final LocalDate EARLIEST_DAILY = LocalDate.of(2015, 1, 1);
    static final int MINIMUM_RATING = 100;

    private LichessPuzzleRecords() {}

    /** A syntactically valid source record whose move line has not yet been replayed. */
    public record Candidate(
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

    /** A rejected source record. The reason is a stable, non-sensitive code shown in Admin. */
    public static final class RecordRejected extends IllegalArgumentException {
        private final String reason;

        RecordRejected(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        RecordRejected(String reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    public static Candidate parse(String line) {
        String[] fields = line.split(",", -1);
        if (fields.length != 11) {
            throw new RecordRejected(
                    "malformed_record", "Puzzle snapshot contains a malformed record");
        }
        return parse(fields);
    }

    /** Returns the stable external ID of a record when it can be read, for issue reporting. */
    public static String externalIdOrNull(String line) {
        int comma = line.indexOf(',');
        String id = comma < 0 ? line : line.substring(0, comma);
        return EXTERNAL_ID.matcher(id).matches() ? id : null;
    }

    private static Candidate parse(String[] fields) {
        String id = fields[0];
        if (!EXTERNAL_ID.matcher(id).matches()
                || fields[1].length() < 20 || fields[1].length() > 120
                || !lichessUrl(fields[8])) {
            throw new RecordRejected(
                    "invalid_identity", "Puzzle snapshot contains invalid identity data");
        }
        List<String> moves = tokens(fields[2], UCI, 2, 120, false, "moves");
        List<String> themes = tokens(fields[7], THEME, 1, 30, true, "themes");
        List<String> openingTags = tokens(fields[9], OPENING, 0, 16, true, "opening tags");
        LocalDate dailyDate = dailyDate(fields[10]);
        int rating = integer(fields[3], MINIMUM_RATING, 4000, "rating");
        int deviation = integer(fields[4], 0, 500, "rating deviation");
        int popularity = integer(fields[5], -100, 100, "popularity");
        int plays = integer(fields[6], 0, Integer.MAX_VALUE, "play count");
        long score = (long) popularity * 1_000_000_000L
                + Math.min(plays, 999_999_999)
                - (long) deviation * 10_000_000L;
        return new Candidate(id, fields[1], moves, rating, deviation, popularity, plays,
                themes, openingTags, fields[8], dailyDate, score);
    }

    /**
     * The official export writes the daily date as epoch milliseconds; an ISO date is also
     * accepted. Either must fall between the start of Lichess daily puzzles and tomorrow.
     */
    private static LocalDate dailyDate(String value) {
        if (value.isBlank()) {
            return null;
        }
        LocalDate date;
        try {
            if (EPOCH_MILLIS.matcher(value).matches()) {
                date = LocalDate.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(value)), ZoneOffset.UTC);
            } else if (DATE.matcher(value).matches()) {
                date = LocalDate.parse(value);
            } else {
                date = null;
            }
        } catch (DateTimeParseException | NumberFormatException exception) {
            date = null;
        }
        if (date == null || date.isBefore(EARLIEST_DAILY)
                || date.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new RecordRejected(
                    "invalid_daily_date", "Puzzle snapshot contains invalid daily date");
        }
        return date;
    }

    /** Replays the complete source line; the first move is the opponent's setup move. */
    public static PuzzleImportBatch.PuzzleRow validate(Candidate candidate) {
        try {
            ChessGame game = new ChessGame(candidate.sourceFen());
            String positionFen = null;
            for (int index = 0; index < candidate.moves().size(); index++) {
                String uci = candidate.moves().get(index);
                Move move = game.getMove(NotationType.UCI, uci);
                if (move == null || !game.isLegalMove(move)) {
                    throw new RecordRejected(
                            "illegal_move_line", "Puzzle snapshot contains an illegal move line");
                }
                game.playMove(move);
                if (index == 0) {
                    positionFen = game.getFen();
                }
            }
            if (positionFen == null || positionFen.length() < 20 || positionFen.length() > 120) {
                throw new RecordRejected(
                        "invalid_position", "Puzzle snapshot contains an invalid position");
            }
            return new PuzzleImportBatch.PuzzleRow(
                    PROVIDER, candidate.externalId(), candidate.sourceFen(), candidate.moves(),
                    candidate.moves().getFirst(), positionFen,
                    candidate.moves().subList(1, candidate.moves().size()), candidate.gameUrl(),
                    LICENSE, candidate.rating(), candidate.ratingDeviation(),
                    candidate.popularity(), candidate.playCount(), candidate.themes(),
                    candidate.openingTags(), candidate.dailyDate());
        } catch (RecordRejected rejected) {
            throw rejected;
        } catch (RuntimeException exception) {
            throw new RecordRejected(
                    "invalid_position", "Puzzle snapshot contains an invalid position", exception);
        }
    }

    public static String displayName(String key) {
        String words = key.replace('_', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    /** Tags are sets; a move line is a sequence and may legitimately repeat a move. */
    private static List<String> tokens(
            String value, Pattern pattern, int minimum, int maximum, boolean distinct, String name) {
        List<String> values = value.isBlank() ? List.of() : List.of(value.split(" "));
        if (values.size() < minimum || values.size() > maximum
                || (distinct && new HashSet<>(values).size() != values.size())
                || values.stream().anyMatch(token -> !pattern.matcher(token).matches())) {
            throw new RecordRejected(
                    "invalid_" + name.replace(' ', '_'),
                    "Puzzle snapshot contains invalid " + name);
        }
        return values;
    }

    private static int integer(String value, int minimum, int maximum, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new RecordRejected(
                    "invalid_" + name.replace(' ', '_'),
                    "Puzzle snapshot contains invalid " + name,
                    exception);
        }
    }

    private static boolean lichessUrl(String value) {
        if (value.length() < 20 || value.length() > 240) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "lichess.org".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
