package com.chesstweek.gamereview;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Identifies one game in the catalog: the site it came from and that site's game ID. Two sites can
 * use the same ID, so a game is only ever looked up by both.
 */
public record GameRef(Source source, String id) {

    private static final Pattern LICHESS_ID = Pattern.compile("[A-Za-z0-9]{8,32}");
    private static final Pattern CHESSCOM_ID = Pattern.compile("[0-9]{1,20}");

    public GameRef {
        Objects.requireNonNull(source);
        if (id == null || !source.idPattern.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid game ID for its source");
        }
    }

    public static GameRef lichess(String id) {
        return new GameRef(Source.LICHESS, id);
    }

    /** Parses untrusted path values; empty when either part is not valid. */
    public static Optional<GameRef> parse(String source, String id) {
        Source parsed = Source.fromValue(source).orElse(null);
        if (parsed == null || id == null || !parsed.idPattern.matcher(id).matches()) {
            return Optional.empty();
        }
        return Optional.of(new GameRef(parsed, id));
    }

    public enum Source {
        LICHESS("lichess", LICHESS_ID),
        CHESSCOM("chesscom", CHESSCOM_ID);

        private final String value;
        private final Pattern idPattern;

        Source(String value, Pattern idPattern) {
            this.value = value;
            this.idPattern = idPattern;
        }

        public String value() {
            return value;
        }

        public static Optional<Source> fromValue(String value) {
            for (Source source : values()) {
                if (source.value.equals(value)) {
                    return Optional.of(source);
                }
            }
            return Optional.empty();
        }
    }
}
