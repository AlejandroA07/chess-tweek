package com.chesstweek.training.internal.performance;

import java.util.Optional;

import com.chesstweek.training.PuzzleTraining.Performance;
import com.chesstweek.training.PuzzleTraining.TrainingList;

import static com.chesstweek.training.PuzzleTraining.TrainingList.HARD_TO_SOLVE;
import static com.chesstweek.training.PuzzleTraining.TrainingList.MASTERED;
import static com.chesstweek.training.PuzzleTraining.TrainingList.RETIRING;
import static com.chesstweek.training.PuzzleTraining.TrainingList.WORTH_REPEATING;
import static com.chesstweek.training.PuzzleTraining.TrainingList.WRONG_MOVES;

/**
 * The owner-approved performance list rules, as pure functions of the current list state and one
 * attempt. See docs/planning/07-performance-training-lists.md.
 */
public final class PerformanceRules {

    static final int REPETITIONS_TO_ADVANCE = 10;
    static final int RETIRING_SUCCESSES = 5;
    static final int RETIRING_STRIKES = 2;

    private PerformanceRules() {}

    /** A completed attempt; Show solution counts as a hint. */
    public record Outcome(int wrongMoves, boolean hintUsed, long elapsedMillis, long limitMillis) {
        boolean fast() {
            return elapsedMillis <= limitMillis;
        }
    }

    /** 6 seconds for the first owner move plus 5 for each further owner move. */
    public static long fastSolveLimitMillis(int solutionPlies) {
        if (solutionPlies < 1) {
            throw new IllegalArgumentException("A solution has at least one move");
        }
        int ownerMoves = (solutionPlies + 1) / 2;
        return 6_000L + 5_000L * (ownerMoves - 1);
    }

    /** The list state after one completed attempt. */
    public static Performance apply(Optional<Performance> current, Outcome outcome) {
        if (current.isEmpty()) {
            return first(outcome);
        }
        Performance state = current.get();
        Optional<Performance> failure = failure(state, outcome.wrongMoves(), outcome.hintUsed());
        if (failure.isPresent()) {
            return failure.get();
        }
        return switch (state.list()) {
            case HARD_TO_SOLVE, WRONG_MOVES -> advance(state, REPETITIONS_TO_ADVANCE);
            case WORTH_REPEATING -> outcome.fast()
                    ? advance(state, REPETITIONS_TO_ADVANCE)
                    : fresh(WORTH_REPEATING);
            case RETIRING -> {
                if (outcome.fast()) {
                    yield advance(state, RETIRING_SUCCESSES);
                }
                int strikes = state.strikes() + 1;
                yield strikes >= RETIRING_STRIKES
                        ? fresh(WORTH_REPEATING)
                        : new Performance(RETIRING, state.successes(), strikes);
            }
            case MASTERED -> state;
        };
    }

    /**
     * The list movement that a repetition attempt's Wrong Moves or hint so far already decide,
     * applied immediately rather than at completion. Completing the attempt afterwards from the
     * returned state yields the same result as completing it from the original state.
     */
    public static Optional<Performance> immediate(
            Optional<Performance> current, int wrongMoves, boolean hintUsed) {
        return current.flatMap(state -> failure(state, wrongMoves, hintUsed));
    }

    private static Optional<Performance> failure(
            Performance state, int wrongMoves, boolean hintUsed) {
        if (state.list() == MASTERED) {
            return Optional.empty();
        }
        if (hintUsed || wrongMoves >= 2) {
            return Optional.of(fresh(HARD_TO_SOLVE));
        }
        if (wrongMoves == 1 && state.list() != HARD_TO_SOLVE) {
            return Optional.of(fresh(WRONG_MOVES));
        }
        return Optional.empty();
    }

    private static Performance first(Outcome outcome) {
        if (outcome.hintUsed() || outcome.wrongMoves() >= 2) {
            return fresh(HARD_TO_SOLVE);
        }
        if (outcome.wrongMoves() == 1) {
            return fresh(WRONG_MOVES);
        }
        return fresh(outcome.fast() ? RETIRING : WORTH_REPEATING);
    }

    private static Performance advance(Performance state, int required) {
        int successes = state.successes() + 1;
        return successes >= required
                ? fresh(next(state.list()))
                : new Performance(state.list(), successes, state.strikes());
    }

    private static TrainingList next(TrainingList list) {
        return switch (list) {
            case HARD_TO_SOLVE -> WRONG_MOVES;
            case WRONG_MOVES -> WORTH_REPEATING;
            case WORTH_REPEATING -> RETIRING;
            case RETIRING, MASTERED -> MASTERED;
        };
    }

    private static Performance fresh(TrainingList list) {
        return new Performance(list, 0, 0);
    }
}
