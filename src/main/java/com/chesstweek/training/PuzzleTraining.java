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

    /**
     * Starts an attempt on the Puzzle that a guided method's Exercise references. It applies the
     * same rules as every other attempt; the method's own course progress stays separate.
     */
    Optional<Attempt> startForMethod(String accountId, long puzzleId);

    /** The Puzzle of one of the account's guided-method attempts, or empty for any other. */
    Optional<Long> methodAttemptPuzzle(String accountId, long attemptId);

    MoveResult move(String accountId, long attemptId, String moveUci);

    HintResult hint(String accountId, long attemptId);

    RevealResult reveal(String accountId, long attemptId);

    Optional<Performance> performance(String accountId, long puzzleId);

    /** The number of the account's Puzzles in every Training List, in progression order. */
    List<ListCount> listCounts(String accountId);

    /**
     * The account's Mastered Puzzles grouped by the Themes and opening categories of their current
     * observation. A Mastered Puzzle belongs to every category it is a member of.
     */
    MasteredSummary mastered(String accountId);

    /**
     * Starts a Practice Session over one active Training List in a freshly randomized order,
     * replacing the account's previous session. Empty when the list has no Puzzles. Mastered is not
     * an active list; use {@link #startMasteredSession}.
     */
    Optional<Session> startSession(String accountId, TrainingList list);

    /**
     * Starts optional practice of the account's Mastered Puzzles in one category, in a freshly
     * randomized order. Its attempts never change Mastered status or create repetition progress.
     */
    Optional<Session> startMasteredSession(String accountId, PuzzleLibrary.Category category);

    /**
     * Starts an attempt on the session's next Puzzle that has not been presented and is still in the
     * session's list. Every Puzzle is presented at most once per session.
     */
    SessionAttempt nextInSession(String accountId, long sessionId);

    enum Entry {
        RANDOM,
        CATEGORY,
        REPETITION,
        MASTERED_PRACTICE,
        METHOD
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

    record ListCount(TrainingList list, int puzzles) {}

    /** {@code puzzles} in each category is the account's Mastered count, not the library's. */
    record MasteredSummary(int total, List<PuzzleLibrary.CategoryEntry> categories) {}

    record Session(long sessionId, TrainingList list, int total) {}

    enum SessionOutcome {
        STARTED,
        /** Every Puzzle in the session has been presented. */
        COMPLETE,
        NOT_FOUND
    }

    /**
     * {@code position} counts the Puzzles presented so far in the session, including this one;
     * {@code total} adds the Puzzles still to come that are still in the list. A Puzzle that leaves
     * the list before its turn leaves the session, so {@code total} can shrink but {@code position}
     * never skips.
     */
    record SessionAttempt(SessionOutcome outcome, Attempt attempt, int position, int total) {}

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
