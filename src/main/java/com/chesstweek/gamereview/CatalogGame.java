package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CatalogGame(
        String id,
        Instant playedAt,
        Instant lastMoveAt,
        Status status,
        Result ownerResult,
        Color ownerColor,
        Integer ownerRating,
        Integer ownerRatingChange,
        Opponent opponent,
        Speed speed,
        boolean rated,
        Variant variant,
        TimeControl timeControl,
        Opening opening,
        PhaseDivision phaseDivision,
        String movesSan,
        String pgn,
        List<Integer> clocks,
        boolean analysisEligible) {

    public CatalogGame {
        Objects.requireNonNull(id);
        Objects.requireNonNull(playedAt);
        Objects.requireNonNull(lastMoveAt);
        Objects.requireNonNull(status);
        Objects.requireNonNull(ownerResult);
        Objects.requireNonNull(ownerColor);
        Objects.requireNonNull(opponent);
        Objects.requireNonNull(speed);
        Objects.requireNonNull(variant);
        Objects.requireNonNull(movesSan);
        clocks = clocks == null ? List.of() : List.copyOf(clocks);
    }

    public enum Status {
        MATE("mate"),
        RESIGN("resign"),
        STALEMATE("stalemate"),
        TIMEOUT("timeout"),
        DRAW("draw"),
        OUT_OF_TIME("outoftime"),
        CHEAT("cheat"),
        UNKNOWN_FINISH("unknownFinish"),
        INSUFFICIENT_MATERIAL_CLAIM("insufficientMaterialClaim"),
        VARIANT_END("variantEnd");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Status fromValue(String value) {
            for (Status status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown finished-game status");
        }
    }

    public enum Result {
        WIN("win"),
        DRAW("draw"),
        LOSS("loss");

        private final String value;

        Result(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Result fromValue(String value) {
            return switch (value) {
                case "win" -> WIN;
                case "draw" -> DRAW;
                case "loss" -> LOSS;
                default -> throw new IllegalArgumentException("Unknown game result");
            };
        }
    }

    public enum Color {
        WHITE("white"),
        BLACK("black");

        private final String value;

        Color(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Color fromValue(String value) {
            return switch (value) {
                case "white" -> WHITE;
                case "black" -> BLACK;
                default -> throw new IllegalArgumentException("Unknown game color");
            };
        }

        public Color opposite() {
            return this == WHITE ? BLACK : WHITE;
        }
    }

    public enum OpponentKind {
        USER("user"),
        AI("ai"),
        ANONYMOUS("anonymous");

        private final String value;

        OpponentKind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static OpponentKind fromValue(String value) {
            return switch (value) {
                case "user" -> USER;
                case "ai" -> AI;
                case "anonymous" -> ANONYMOUS;
                default -> throw new IllegalArgumentException("Unknown opponent kind");
            };
        }
    }

    public enum Speed {
        ULTRA_BULLET("ultraBullet"),
        BULLET("bullet"),
        BLITZ("blitz"),
        RAPID("rapid"),
        CLASSICAL("classical"),
        CORRESPONDENCE("correspondence");

        private final String value;

        Speed(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Speed fromValue(String value) {
            for (Speed speed : values()) {
                if (speed.value.equals(value)) {
                    return speed;
                }
            }
            throw new IllegalArgumentException("Unknown game speed");
        }
    }

    public enum Variant {
        STANDARD("standard"),
        CHESS960("chess960"),
        CRAZYHOUSE("crazyhouse"),
        ANTICHESS("antichess"),
        ATOMIC("atomic"),
        HORDE("horde"),
        KING_OF_THE_HILL("kingOfTheHill"),
        RACING_KINGS("racingKings"),
        THREE_CHECK("threeCheck"),
        FROM_POSITION("fromPosition");

        private final String value;

        Variant(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Variant fromValue(String value) {
            for (Variant variant : values()) {
                if (variant.value.equals(value)) {
                    return variant;
                }
            }
            throw new IllegalArgumentException("Unknown game variant");
        }
    }

    public record Opponent(
            OpponentKind kind,
            String userId,
            String name,
            Integer rating,
            String title,
            Integer aiLevel) {}

    public record TimeControl(
            Integer initialSeconds, Integer incrementSeconds, Integer daysPerTurn) {}

    public record Opening(String eco, String name, Integer ply) {}

    public record PhaseDivision(Integer middlePly, Integer endPly) {}
}
