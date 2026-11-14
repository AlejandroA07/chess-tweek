package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Owner-requested Stockfish analysis of one stored game at a time. Analysis runs in the
 * background; callers poll {@link #current} for progress. Every completed analysis stores new
 * immutable versions, one per analyzed side, and never changes an older one.
 */
public interface GameAnalysis {

    StartResult start(String accountId, String gameId, Perspective perspective);

    /** The account's running analysis, or its most recent finished one. */
    Optional<Job> current(String accountId);

    /** Asks the account's running analysis to stop after its current search. */
    Optional<Job> cancel(String accountId);

    enum Perspective {
        MY_MOVES,
        OPPONENT_MOVES,
        BOTH
    }

    record StartResult(Outcome outcome, Job job) {

        public enum Outcome {
            STARTED,
            GAME_NOT_FOUND,
            NOT_ELIGIBLE,
            BUSY,
            NO_PROFILE
        }
    }

    /**
     * A snapshot of one analysis. {@code judged} and {@code total} count moves in the current
     * {@code pass}. {@code versionIds} are the stored versions once completed.
     */
    record Job(
            String gameId,
            Perspective perspective,
            State state,
            Pass pass,
            int judged,
            int total,
            Failure failure,
            Instant startedAt,
            Instant finishedAt,
            List<Long> versionIds) {

        public Job {
            versionIds = versionIds == null ? List.of() : List.copyOf(versionIds);
        }
    }

    enum State {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELED
    }

    enum Pass {
        STARTING,
        QUICK,
        DEEP,
        SAVING
    }

    /** Why an analysis failed; each is recoverable by fixing the cause and starting again. */
    enum Failure {
        ENGINE_BUSY,
        ENGINE_UNAVAILABLE,
        ENGINE_FAILED,
        PROFILE_OUTDATED,
        STORAGE_FAILED
    }
}
