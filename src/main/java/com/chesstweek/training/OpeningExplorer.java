package com.chesstweek.training;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.chesstweek.shared.chess.ExplorerPosition;

/**
 * Move statistics from the Lichess opening explorer, asked server-side with the owner's token and
 * cached. The token is used only for the upstream request and never stored or returned.
 */
public interface OpeningExplorer {

    /** Games played by titled masters over the board. */
    Answer masters(String accessToken, ExplorerPosition position);

    /** Lichess games, optionally narrowed to speeds and rating groups (empty means all). */
    Answer lichess(
            String accessToken,
            ExplorerPosition position,
            Set<Speed> speeds,
            Set<RatingGroup> ratings);

    enum Speed {
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

        public static java.util.Optional<Speed> fromValue(String value) {
            for (Speed speed : values()) {
                if (speed.value.equals(value)) {
                    return java.util.Optional.of(speed);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** The Lichess explorer's rating groups; each runs from its value to the next one. */
    enum RatingGroup {
        R0(0),
        R1000(1000),
        R1200(1200),
        R1400(1400),
        R1600(1600),
        R1800(1800),
        R2000(2000),
        R2200(2200),
        R2500(2500);

        private final int lowest;

        RatingGroup(int lowest) {
            this.lowest = lowest;
        }

        public int lowest() {
            return lowest;
        }

        public static java.util.Optional<RatingGroup> fromValue(String value) {
            for (RatingGroup group : values()) {
                if (Integer.toString(group.lowest).equals(value)) {
                    return java.util.Optional.of(group);
                }
            }
            return java.util.Optional.empty();
        }
    }

    record Answer(Status status, Statistics statistics, Long retryAfterSeconds) {

        public enum Status {
            SUCCESS,
            UNAUTHORIZED,
            RATE_LIMITED,
            INVALID_RESPONSE,
            UNAVAILABLE
        }

        public Answer {
            Objects.requireNonNull(status);
            if ((status == Status.SUCCESS) != (statistics != null)) {
                throw new IllegalArgumentException("Only a successful answer has statistics");
            }
        }

        public static Answer success(Statistics statistics) {
            return new Answer(Status.SUCCESS, statistics, null);
        }

        public static Answer failed(Status status, Long retryAfterSeconds) {
            return new Answer(status, null, retryAfterSeconds);
        }
    }

    /**
     * One explorer answer. {@code stale} is true when Lichess could not be reached and an expired
     * cached answer is shown instead.
     */
    record Statistics(
            Opening opening,
            Results results,
            List<NextMove> moves,
            List<TopGame> topGames,
            Instant fetchedAt,
            boolean stale) {

        public Statistics {
            Objects.requireNonNull(results);
            Objects.requireNonNull(fetchedAt);
            moves = List.copyOf(moves);
            topGames = List.copyOf(topGames);
        }

        public Statistics asStale() {
            return new Statistics(opening, results, moves, topGames, fetchedAt, true);
        }
    }

    record Opening(String eco, String name) {

        public Opening {
            Objects.requireNonNull(eco);
            Objects.requireNonNull(name);
        }
    }

    record Results(long white, long draws, long black) {

        public Results {
            if (white < 0 || draws < 0 || black < 0) {
                throw new IllegalArgumentException("Counts cannot be negative");
            }
        }
    }

    record NextMove(String uci, String san, Integer averageRating, Results results, Opening opening) {

        public NextMove {
            Objects.requireNonNull(uci);
            Objects.requireNonNull(san);
            Objects.requireNonNull(results);
        }
    }

    record Player(String name, Integer rating, String title) {

        public Player {
            Objects.requireNonNull(name);
        }
    }

    /** {@code winner} is "white", "black", or null for a draw. */
    record TopGame(String id, String uci, Player white, Player black, String winner, Integer year) {

        public TopGame {
            Objects.requireNonNull(id);
            Objects.requireNonNull(uci);
            Objects.requireNonNull(white);
            Objects.requireNonNull(black);
        }
    }
}
