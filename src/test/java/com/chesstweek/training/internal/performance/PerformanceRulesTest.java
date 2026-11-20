package com.chesstweek.training.internal.performance;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.PuzzleTraining.TrainingList;

import static com.chesstweek.training.PuzzleTraining.TrainingList.HARD_TO_SOLVE;
import static com.chesstweek.training.PuzzleTraining.TrainingList.MASTERED;
import static com.chesstweek.training.PuzzleTraining.TrainingList.RETIRING;
import static com.chesstweek.training.PuzzleTraining.TrainingList.WORTH_REPEATING;
import static com.chesstweek.training.PuzzleTraining.TrainingList.WRONG_MOVES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The decision table in docs/planning/07-performance-training-lists.md. */
class PerformanceRulesTest {

    private static final long LIMIT = 11_000;

    @ParameterizedTest(name = "first attempt: hint={0} wrong={1} elapsed={2} -> {3}")
    @CsvSource({
            "true, 0, 1000, HARD_TO_SOLVE",
            "true, 1, 99000, HARD_TO_SOLVE",
            "false, 2, 1000, HARD_TO_SOLVE",
            "false, 5, 1000, HARD_TO_SOLVE",
            "false, 1, 1000, WRONG_MOVES",
            "false, 1, 99000, WRONG_MOVES",
            "false, 0, 11001, WORTH_REPEATING",
            "false, 0, 11000, RETIRING",
            "false, 0, 0, RETIRING",
    })
    void classifiesTheFirstCompletedAttemptByPriority(
            boolean hint, int wrong, long elapsed, TrainingList expected) {
        assertThat(PerformanceRules.apply(Optional.empty(), outcome(hint, wrong, elapsed)))
                .isEqualTo(new PuzzleTraining.Performance(expected, 0, 0));
    }

    @Test
    void hardToSolveCountsCompletionsWithAtMostOneWrongMoveRegardlessOfTime() {
        assertThat(apply(HARD_TO_SOLVE, 3, 0, false, 0, 99_000)).isEqualTo(state(HARD_TO_SOLVE, 4, 0));
        assertThat(apply(HARD_TO_SOLVE, 3, 0, false, 1, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 4, 0));
        assertThat(apply(HARD_TO_SOLVE, 3, 0, false, 2, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 0, 0));
        assertThat(apply(HARD_TO_SOLVE, 3, 0, true, 0, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 0, 0));
        assertThat(apply(HARD_TO_SOLVE, 9, 0, false, 1, 1_000)).isEqualTo(state(WRONG_MOVES, 0, 0));
    }

    @Test
    void wrongMovesCountsCleanSolutionsRegardlessOfTime() {
        assertThat(apply(WRONG_MOVES, 3, 0, false, 0, 99_000)).isEqualTo(state(WRONG_MOVES, 4, 0));
        assertThat(apply(WRONG_MOVES, 3, 0, false, 1, 1_000)).isEqualTo(state(WRONG_MOVES, 0, 0));
        assertThat(apply(WRONG_MOVES, 3, 0, false, 2, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 0, 0));
        assertThat(apply(WRONG_MOVES, 3, 0, true, 0, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 0, 0));
        assertThat(apply(WRONG_MOVES, 9, 0, false, 0, 99_000)).isEqualTo(state(WORTH_REPEATING, 0, 0));
    }

    @Test
    void worthRepeatingCountsOnlyFastCleanSolutions() {
        assertThat(apply(WORTH_REPEATING, 3, 0, false, 0, LIMIT)).isEqualTo(state(WORTH_REPEATING, 4, 0));
        assertThat(apply(WORTH_REPEATING, 3, 0, false, 0, LIMIT + 1)).isEqualTo(state(WORTH_REPEATING, 0, 0));
        assertThat(apply(WORTH_REPEATING, 3, 0, false, 1, 1_000)).isEqualTo(state(WRONG_MOVES, 0, 0));
        assertThat(apply(WORTH_REPEATING, 3, 0, false, 2, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 0, 0));
        assertThat(apply(WORTH_REPEATING, 3, 0, true, 0, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 0, 0));
        assertThat(apply(WORTH_REPEATING, 9, 0, false, 0, 1_000)).isEqualTo(state(RETIRING, 0, 0));
    }

    @Test
    void retiringKeepsSuccessesThroughOneTimeStrikeAndMastersAtFive() {
        assertThat(apply(RETIRING, 2, 0, false, 0, LIMIT)).isEqualTo(state(RETIRING, 3, 0));
        assertThat(apply(RETIRING, 2, 0, false, 0, LIMIT + 1)).isEqualTo(state(RETIRING, 2, 1));
        assertThat(apply(RETIRING, 4, 1, false, 0, 1_000)).isEqualTo(state(MASTERED, 0, 0));
        assertThat(apply(RETIRING, 4, 1, false, 0, LIMIT + 1)).isEqualTo(state(WORTH_REPEATING, 0, 0));
        assertThat(apply(RETIRING, 4, 1, false, 1, 1_000)).isEqualTo(state(WRONG_MOVES, 0, 0));
        assertThat(apply(RETIRING, 4, 1, false, 2, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 0, 0));
        assertThat(apply(RETIRING, 4, 1, true, 0, 1_000)).isEqualTo(state(HARD_TO_SOLVE, 0, 0));
    }

    @Test
    void masteredIsTerminal() {
        assertThat(apply(MASTERED, 0, 0, true, 3, 99_000)).isEqualTo(state(MASTERED, 0, 0));
    }

    @Test
    void appliesARepetitionDemotionAsSoonAsTheAttemptDecidesIt() {
        assertThat(PerformanceRules.immediate(Optional.empty(), 2, true)).isEmpty();
        assertThat(PerformanceRules.immediate(Optional.of(state(RETIRING, 3, 1)), 0, true))
                .contains(state(HARD_TO_SOLVE, 0, 0));
        assertThat(PerformanceRules.immediate(Optional.of(state(WRONG_MOVES, 3, 0)), 2, false))
                .contains(state(HARD_TO_SOLVE, 0, 0));
        assertThat(PerformanceRules.immediate(Optional.of(state(WORTH_REPEATING, 3, 0)), 1, false))
                .contains(state(WRONG_MOVES, 0, 0));
        assertThat(PerformanceRules.immediate(Optional.of(state(WRONG_MOVES, 3, 0)), 1, false))
                .contains(state(WRONG_MOVES, 0, 0));
        assertThat(PerformanceRules.immediate(Optional.of(state(HARD_TO_SOLVE, 3, 0)), 1, false))
                .isEmpty();
        assertThat(PerformanceRules.immediate(Optional.of(state(RETIRING, 3, 1)), 0, false))
                .isEmpty();
        assertThat(PerformanceRules.immediate(Optional.of(state(MASTERED, 0, 0)), 2, true))
                .isEmpty();
    }

    @Test
    void completingAfterAnImmediateDemotionMatchesCompletingWithoutIt() {
        for (TrainingList list : TrainingList.values()) {
            for (int successes : new int[] {0, 4, 9}) {
                for (int strikes = 0; strikes <= 1; strikes++) {
                    PuzzleTraining.Performance current = state(list, successes, strikes);
                    for (int wrong = 0; wrong <= 3; wrong++) {
                        for (boolean hint : new boolean[] {false, true}) {
                            for (long elapsed : new long[] {1_000, LIMIT + 1}) {
                                var outcome = outcome(hint, wrong, elapsed);
                                PuzzleTraining.Performance demoted = PerformanceRules
                                        .immediate(Optional.of(current), wrong, hint)
                                        .orElse(current);
                                assertThat(PerformanceRules.apply(Optional.of(demoted), outcome))
                                        .as("%s wrong=%d hint=%s elapsed=%d",
                                                current, wrong, hint, elapsed)
                                        .isEqualTo(PerformanceRules.apply(
                                                Optional.of(current), outcome));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void scalesTheFastSolveLimitWithOwnerMovesOnly() {
        assertThat(PerformanceRules.fastSolveLimitMillis(1)).isEqualTo(6_000);
        assertThat(PerformanceRules.fastSolveLimitMillis(2)).isEqualTo(6_000);
        assertThat(PerformanceRules.fastSolveLimitMillis(3)).isEqualTo(11_000);
        assertThat(PerformanceRules.fastSolveLimitMillis(4)).isEqualTo(11_000);
        assertThat(PerformanceRules.fastSolveLimitMillis(7)).isEqualTo(21_000);
        assertThatThrownBy(() -> PerformanceRules.fastSolveLimitMillis(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PuzzleTraining.Performance apply(
            TrainingList list, int successes, int strikes, boolean hint, int wrong, long elapsed) {
        return PerformanceRules.apply(
                Optional.of(state(list, successes, strikes)), outcome(hint, wrong, elapsed));
    }

    private static PuzzleTraining.Performance state(TrainingList list, int successes, int strikes) {
        return new PuzzleTraining.Performance(list, successes, strikes);
    }

    private static PerformanceRules.Outcome outcome(boolean hint, int wrong, long elapsed) {
        return new PerformanceRules.Outcome(wrong, hint, elapsed, LIMIT);
    }
}
