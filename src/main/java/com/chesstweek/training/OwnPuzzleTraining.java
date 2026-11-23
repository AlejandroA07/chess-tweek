package com.chesstweek.training;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.chesstweek.training.PuzzleTraining.HintResult;
import com.chesstweek.training.PuzzleTraining.ListCount;
import com.chesstweek.training.PuzzleTraining.MoveOutcome;
import com.chesstweek.training.PuzzleTraining.Performance;
import com.chesstweek.training.PuzzleTraining.RevealOutcome;
import com.chesstweek.training.PuzzleTraining.Session;
import com.chesstweek.training.PuzzleTraining.SessionOutcome;
import com.chesstweek.training.PuzzleTraining.TrainingList;

/**
 * Account-scoped performance training over the owner's game-derived Own Puzzles. It applies the
 * same rules as {@link PuzzleTraining} to completely separate attempts, Training List state, and
 * sessions (docs/planning/07-performance-training-lists.md). A New Own Puzzle has never completed
 * an attempt and is not discarded; Discard is allowed only before classification, and Restore
 * returns a discarded puzzle to New.
 */
public interface OwnPuzzleTraining {

    /** New and Discarded counts, Training List counts, and groups over every kept Own Puzzle. */
    Overview overview(String accountId);

    /** New Own Puzzles, newest source game first. */
    Page newPuzzles(String accountId, int offset, int limit);

    /** Discarded Own Puzzles, newest source game first. */
    Page discarded(String accountId, int offset, int limit);

    /** The Own Puzzles in one Training List, newest source game first. */
    Page inList(String accountId, TrainingList list, int offset, int limit);

    /** The number of the account's Own Puzzles in every Training List, in progression order. */
    List<ListCount> listCounts(String accountId);

    ChangeOutcome discard(String accountId, long ownPuzzleId);

    ChangeOutcome restore(String accountId, long ownPuzzleId);

    /** Starts an attempt on the newest New Own Puzzle, or empty when none is New. */
    Optional<Attempt> startNext(String accountId);

    /** Starts an attempt on one New Own Puzzle, or empty when it is not New for the account. */
    Optional<Attempt> start(String accountId, long ownPuzzleId);

    MoveResult move(String accountId, long attemptId, String moveUci);

    HintResult hint(String accountId, long attemptId);

    RevealResult reveal(String accountId, long attemptId);

    Optional<Performance> performance(String accountId, long ownPuzzleId);

    /** Mastered Own Puzzles grouped by opening family, game phase, and tactic label. */
    Groups mastered(String accountId);

    /**
     * Starts a Practice Session over one active Training List in a freshly randomized order,
     * replacing the account's previous Own Puzzle session. Empty when the list is empty or is
     * Mastered.
     */
    Optional<Session> startSession(String accountId, TrainingList list);

    /** Starts optional practice of Mastered Own Puzzles in one group; it never changes state. */
    Optional<Session> startMasteredSession(String accountId, GroupKind kind, String key);

    SessionAttempt nextInSession(String accountId, long sessionId);

    enum GroupKind {
        OPENING,
        PHASE,
        TACTIC
    }

    enum ChangeOutcome {
        DONE,
        NOT_FOUND,
        /** Discard refused: the Own Puzzle already has a Training List. */
        CLASSIFIED,
        /** Restore refused: the Own Puzzle is not discarded. */
        NOT_DISCARDED
    }

    /** The source game position an Own Puzzle was created from. */
    record SourceGame(String source, String gameId, int ply) {}

    /** {@code kind} is {@code allowed} or {@code missed}. */
    record Tactic(String theme, String kind) {}

    /** An Own Puzzle without its solution. */
    record OwnPuzzle(
            long id,
            String label,
            String phase,
            String openingFamily,
            String openingName,
            List<Tactic> tactics,
            SourceGame sourceGame,
            Instant gamePlayedAt) {}

    record Page(int total, List<OwnPuzzle> puzzles) {}

    /** One group and its number of Own Puzzles. */
    record GroupCount(String key, int puzzles) {}

    /** Tactic groups count each tactic theme once per puzzle; a puzzle without one is not counted. */
    record Groups(
            int total,
            List<GroupCount> openings,
            List<GroupCount> phases,
            List<GroupCount> tactics) {}

    record Overview(int newPuzzles, int discarded, List<ListCount> lists, Groups groups) {}

    /**
     * An active attempt. {@code positionFen} has the owner to move; {@code previousMove} is the
     * opponent's move into it, so a board can animate into the position, or null at the game's
     * first move.
     */
    record Attempt(
            long attemptId,
            OwnPuzzle puzzle,
            String positionFen,
            String previousMove,
            int ownerMoves,
            long fastSolveLimitMillis,
            TrainingList currentList) {}

    /** The classification an attempt produced, and the list it started from (null if new). */
    record Completion(
            TrainingList previousList,
            Performance performance,
            int wrongMoves,
            boolean hintUsed,
            long elapsedMillis,
            long fastSolveLimitMillis,
            SourceGame sourceGame) {}

    record MoveResult(
            MoveOutcome outcome, String opponentReply, int wrongMoves, Completion completion) {}

    record RevealResult(RevealOutcome outcome, List<String> remainingMoves, Completion completion) {}

    record SessionAttempt(SessionOutcome outcome, Attempt attempt, int position, int total) {}
}
