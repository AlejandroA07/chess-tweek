package com.chesstweek.training;

import java.util.List;
import java.util.Optional;

/**
 * Account-scoped performance training over normal Lichess Puzzles. Solutions stay server-side:
 * an attempt exposes only its position until moves, a hint, or Show solution reveal more. The
 * first completed attempt classifies a Puzzle into a Training List; later completions apply the
 * rules of its current list (docs/planning/07-performance-training-lists.md).
 */
public interface PuzzleTraining {

    /** Starts an attempt on an unclassified Puzzle, or empty when none is available. */
    Optional<Attempt> startRandom(String accountId);

    /** Starts an attempt on a Puzzle in one category that is not Mastered for the account. */
    Optional<Attempt> startInCategory(String accountId, PuzzleLibrary.Category category);

    MoveResult move(String accountId, long attemptId, String moveUci);

    HintResult hint(String accountId, long attemptId);

    RevealResult reveal(String accountId, long attemptId);

    Optional<Performance> performance(String accountId, long puzzleId);

    enum Entry {
        RANDOM,
        CATEGORY
    }

    enum TrainingList {
        HARD_TO_SOLVE,
        WRONG_MOVES,
        WORTH_REPEATING,
        RETIRING,
        MASTERED
    }

    /**
     * An active attempt. {@code positionFen} is the position after the source setup move, with
     * the owner to move; {@code setupMove} lets a board animate into it.
     */
    record Attempt(
            long attemptId,
            long puzzleId,
            String externalId,
            String positionFen,
            String setupMove,
            int rating,
            int ownerMoves,
            long fastSolveLimitMillis,
            TrainingList currentList) {}

    record Performance(TrainingList list, int successes, int strikes) {}

    /** The classification an attempt produced, and the list it started from (null if new). */
    record Completion(
            TrainingList previousList,
            Performance performance,
            int wrongMoves,
            boolean hintUsed,
            long elapsedMillis,
            long fastSolveLimitMillis,
            String gameUrl,
            List<String> themes) {}

    enum MoveOutcome {
        /** Correct; the opponent replied and the owner moves again. */
        CORRECT,
        /** Correct and the solution is complete; the attempt is classified. */
        SOLVED,
        /** A legal move that is not the solution; the position is unchanged. */
        WRONG,
        ILLEGAL,
        NOT_FOUND,
        FINISHED
    }

    record MoveResult(
            MoveOutcome outcome, String opponentReply, int wrongMoves, Completion completion) {}

    enum HintOutcome {
        SHOWN,
        NOT_FOUND,
        FINISHED
    }

    /** The square of the piece that makes the next correct owner move. */
    record HintResult(HintOutcome outcome, String fromSquare) {}

    enum RevealOutcome {
        REVEALED,
        NOT_FOUND,
        FINISHED
    }

    /** The remaining solution line from the current position, owner move first. */
    record RevealResult(
            RevealOutcome outcome, List<String> remainingMoves, Completion completion) {}
}
