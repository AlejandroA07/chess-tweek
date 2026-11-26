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

    /** Every pattern with at least one ladder Puzzle, ordered by group name and display name. */
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
     * Moves the account's place so that the package's next Puzzle is the one at {@code position}
     * (1-based, in rating order over the whole package, counting Mastered Puzzles too).
     */
    JumpOutcome jump(String accountId, String patternKey, RatingPackage ratingPackage, int position);

    /**
     * Draws a new fast ladder for the pattern, replacing the account's previous one: one random
     * Puzzle from each {@link #FAST_STEPS} range, then, in random order, one Puzzle of the pattern
     * that also carries each other pattern Theme, rated 2000-2199 or as near as possible. Mastered
     * Puzzles are skipped. Empty when the pattern is unknown or has no Puzzle to draw.
     */
    Optional<FastLadder> startFast(String accountId, String patternKey);

    /** Starts an attempt on the fast ladder's next Puzzle that has not been presented. */
    FastNext nextFast(String accountId, long fastLadderId);

    /** The rating steps of a fast ladder, lower bound inclusive and upper bound exclusive. */
    List<RatingRange> FAST_STEPS = List.of(
            new RatingRange(0, 800),
            new RatingRange(800, 1000),
            new RatingRange(1000, 1200),
            new RatingRange(1200, 1400),
            new RatingRange(1400, 1600),
            new RatingRange(1600, 1800),
            new RatingRange(1800, 2000),
            new RatingRange(2000, 2200));

    /** Where a combination step is drawn from first; it widens in 200-point steps from here. */
    RatingRange FAST_COMBINATION_RANGE = new RatingRange(2000, 2200);

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

    enum JumpOutcome {
        MOVED,
        /** The position is not between 1 and the package's size. */
        OUT_OF_RANGE,
        NOT_FOUND
    }

    record RatingRange(int low, int high) {}

    record FastLadder(long fastLadderId, int total) {}

    /** A fast ladder step: a rating range, or the other pattern it is combined with. */
    record FastStep(RatingRange range, Pattern combinedWith) {}

    enum FastOutcome {
        STARTED,
        /** Every Puzzle in the fast ladder has been presented. */
        COMPLETE,
        /** Not the account's current fast ladder, for example because a new one was started. */
        NOT_FOUND
    }

    record FastNext(
            FastOutcome outcome, PuzzleTraining.Attempt attempt, int position, int total, FastStep step) {}
}
