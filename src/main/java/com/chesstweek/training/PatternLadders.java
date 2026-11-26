package com.chesstweek.training;

import java.util.List;
import java.util.Optional;

/**
 * Pattern ladders (Stage 9): one pattern Theme climbed by rating. The owner picks a pattern and a
 * fixed rating package and plays its quality-gated Puzzles from the lowest rating to the highest,
 * skipping Puzzles they have Mastered. Each pattern and package remembers where the owner stopped.
 * Attempts are ordinary Puzzle attempts, so they update the same Training List state as every
 * other Puzzle; ladders reference shared Puzzles and never copy them.
 */
public interface PatternLadders {

    /** Every pattern with at least one ladder Puzzle, in catalog group and display-name order. */
    List<Pattern> patterns();

    /** One pattern's rating packages with their sizes and the account's progress. */
    Optional<Ladder> ladder(String accountId, String patternKey);

    /**
     * Starts an attempt on the package's next Puzzle after where the account stopped, and moves
     * the account's place there.
     */
    Next next(String accountId, String patternKey, RatingPackage ratingPackage);

    /** Forgets where the account stopped, so the package starts again from its lowest rating. */
    boolean restart(String accountId, String patternKey, RatingPackage ratingPackage);

    /**
     * A fixed rating range, lower bound inclusive and upper bound exclusive. Bounds are multiples
     * of 100 so a package's size is a sum of stored 100-point band counts.
     */
    enum RatingPackage {
        UP_TO_1000("up-to-1000", 0, 1000),
        FROM_1000("1000-1400", 1000, 1400),
        FROM_1400("1400-1800", 1400, 1800),
        FROM_1800("1800-2200", 1800, 2200),
        FROM_2200("2200-2600", 2200, 2600),
        FROM_2600("2600-plus", 2600, 4100);

        private final String key;
        private final int low;
        private final int high;

        RatingPackage(String key, int low, int high) {
            this.key = key;
            this.low = low;
            this.high = high;
        }

        /** The stable URL and storage form. */
        public String key() {
            return key;
        }

        public int low() {
            return low;
        }

        public int high() {
            return high;
        }

        public static Optional<RatingPackage> fromKey(String key) {
            for (RatingPackage value : values()) {
                if (value.key.equals(key)) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        }
    }

    record Pattern(String key, String displayName, String groupName, int puzzles) {}

    /** {@code progress} is null until the account starts the package. */
    record PackageEntry(RatingPackage ratingPackage, int puzzles, Progress progress) {}

    /** How many Puzzles were presented since the package was (re)started, and the last rating. */
    record Progress(int presented, int lastRating) {}

    record Ladder(Pattern pattern, List<PackageEntry> packages) {}

    enum NextOutcome {
        STARTED,
        /** No Puzzle remains after the account's place in the package. */
        COMPLETE,
        NOT_FOUND
    }

    record Next(NextOutcome outcome, PuzzleTraining.Attempt attempt, Progress progress) {}
}
