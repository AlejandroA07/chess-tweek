package com.chesstweek.training.internal.performance;

import java.util.Optional;

import com.chesstweek.training.PuzzleTraining.TrainingList;

import static com.chesstweek.training.PuzzleTraining.TrainingList.HARD_TO_SOLVE;
import static com.chesstweek.training.PuzzleTraining.TrainingList.MASTERED;
import static com.chesstweek.training.PuzzleTraining.TrainingList.RETIRING;
import static com.chesstweek.training.PuzzleTraining.TrainingList.WORTH_REPEATING;
import static com.chesstweek.training.PuzzleTraining.TrainingList.WRONG_MOVES;

/**
 * The owner-approved performance list rules, as a pure function of the current list state and one
 * completed attempt. See docs/planning/07-performance-training-lists.md.
 */
public final class PerformanceRules {

    static final int REPETITIONS_TO_ADVANCE = 10;
    static final int RETIRING_SUCCESSES = 5;
    static final int RETIRING_STRIKES = 2;

    private PerformanceRules() {}

    public record State(TrainingList list, int successes, int strikes) {}

    /** A completed attempt; Show solution counts as a hint. */
    public record Outcome(int wrongMoves, boolean hintUsed, long elapsedMillis, long limitMillis) {
        boolean fast() {
            return elapsedMillis <= limitMillis;
        }

        boolean clean() {
            return wrongMoves == 0 && !hintUsed;
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

    public static State apply(Optional<State> current, Outcome outcome) {
        if (current.isEmpty()) {
            return first(outcome);
        }
        State state = current.get();
        if (state.list() == MASTERED) {
            return state;
        }
        if (outcome.hintUsed() || outcome.wrongMoves() >= 2) {
            return fresh(HARD_TO_SOLVE);
        }
        return switch (state.list()) {
            case HARD_TO_SOLVE -> advance(state, WRONG_MOVES, REPETITIONS_TO_ADVANCE);
            case WRONG_MOVES -> outcome.wrongMoves() == 1
                    ? fresh(WRONG_MOVES)
                    : advance(state, WORTH_REPEATING, REPETITIONS_TO_ADVANCE);
            case WORTH_REPEATING -> {
                if (outcome.wrongMoves() == 1) {
                    yield fresh(WRONG_MOVES);
                }
                yield outcome.fast()
                        ? advance(state, RETIRING, REPETITIONS_TO_ADVANCE)
                        : fresh(WORTH_REPEATING);
            }
            case RETIRING -> {
                if (outcome.wrongMoves() == 1) {
                    yield fresh(WRONG_MOVES);
                }
                if (outcome.fast()) {
                    yield advance(state, MASTERED, RETIRING_SUCCESSES);
                }
                int strikes = state.strikes() + 1;
                yield strikes >= RETIRING_STRIKES
                        ? fresh(WORTH_REPEATING)
                        : new State(RETIRING, state.successes(), strikes);
            }
            case MASTERED -> state;
        };
    }

    private static State first(Outcome outcome) {
        if (outcome.hintUsed() || outcome.wrongMoves() >= 2) {
            return fresh(HARD_TO_SOLVE);
        }
        if (outcome.wrongMoves() == 1) {
            return fresh(WRONG_MOVES);
        }
        return fresh(outcome.fast() ? RETIRING : WORTH_REPEATING);
    }

    private static State advance(State state, TrainingList next, int required) {
        int successes = state.successes() + 1;
        return successes >= required
                ? fresh(next)
                : new State(state.list(), successes, state.strikes());
    }

    private static State fresh(TrainingList list) {
        return new State(list, 0, 0);
    }
}
