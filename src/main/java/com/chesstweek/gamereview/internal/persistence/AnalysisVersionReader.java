package com.chesstweek.gamereview.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Reads stored analysis versions back for review. */
public interface AnalysisVersionReader {

    /** The account's newest version of one side of a game, if that side was ever analyzed. */
    Optional<StoredVersion> newest(String accountId, String gameId, AnalysisVersionStore.Side side);

    /** Every judged move of a version, in ply order. */
    List<StoredMove> moves(long versionId);

    record StoredVersion(
            long id,
            AnalysisVersionStore.Side side,
            String analyzedColor,
            String phaseSource,
            int middlegameStart,
            Integer endgameStart,
            Instant completedAt,
            boolean currentProfile,
            String engineName,
            long quickNodes,
            long deepNodes,
            int moveCount,
            int inaccuracies,
            int mistakes,
            int blunders) {}

    /** Lines are best first, from the side to move in their position; {@code after} is empty when the game ended. */
    record StoredMove(
            int ply,
            String san,
            String uci,
            String fenBefore,
            String phase,
            String pass,
            String label,
            String mateEvent,
            boolean playedBest,
            String bestMove,
            int expectedBefore,
            int expectedAfter,
            int loss,
            String afterTerminal,
            List<StoredLine> before,
            List<StoredLine> after) {}

    /** Exactly one of {@code cp} and {@code mate} is set. */
    record StoredLine(String move, Integer cp, Integer mate, int win, int draw, int loss, List<String> pv) {}
}
