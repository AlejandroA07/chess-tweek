package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One stored game prepared for review: every position, the phases, and the newest analysis of
 * one side. Owner and opponent findings are always reviewed separately, never combined.
 */
public interface GameReviews {

    /**
     * The account's game, or empty when it is not stored. {@code side} null selects the owner's
     * moves when analyzed, otherwise the opponent's, otherwise no side.
     */
    Optional<GameReview> review(String accountId, String gameId, Side side);

    enum Side {
        MY_MOVES,
        OPPONENT_MOVES
    }

    /**
     * {@code plies} and {@code phases} are empty or null for games that cannot be analyzed.
     * {@code selected} is null when the chosen side has no stored analysis.
     */
    record GameReview(
            CatalogGame game,
            String initialFen,
            List<Ply> plies,
            Phases phases,
            VersionInfo myMoves,
            VersionInfo opponentMoves,
            SideReview selected) {}

    /** The position after the move is {@code fen}. */
    record Ply(int ply, String color, String san, String uci, String fen) {}

    /** Position indexes; a move belongs to the phase of the position it was played from. */
    record Phases(String source, int middlegameStart, Integer endgameStart) {}

    record VersionInfo(
            long id,
            Instant completedAt,
            boolean currentProfile,
            int moves,
            int inaccuracies,
            int mistakes,
            int blunders) {}

    record SideReview(
            Side side,
            long versionId,
            String analyzedColor,
            String engineName,
            long quickNodes,
            long deepNodes,
            Instant completedAt,
            boolean currentProfile,
            Statistics statistics,
            List<PositionEvaluation> evaluations,
            List<JudgedMove> moves) {}

    /**
     * Losses are averages per judged move: expected score in percentage points, and centipawns
     * capped at 1,000 per move with mates counted as 1,000.
     */
    record Statistics(
            int moves,
            int inaccuracies,
            int mistakes,
            int blunders,
            double averageLoss,
            double averageCentipawnLoss,
            Map<String, PhaseStatistics> byPhase) {}

    record PhaseStatistics(int moves, int inaccuracies, int mistakes, int blunders, double averageLoss) {}

    /** A position's value for White: expected score 0 to 2000, plus the engine score when searched. */
    record PositionEvaluation(int index, int whiteExpected, Integer whiteCp, Integer whiteMate) {}

    record JudgedMove(
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
            String bestMoveSan,
            int expectedBefore,
            int expectedAfter,
            int loss,
            String afterTerminal,
            List<Line> before,
            List<Line> after) {}

    /** One engine line from the side to move; {@code pvSan} stops at the first move that is not legal. */
    record Line(
            String move,
            String moveSan,
            Integer cp,
            Integer mate,
            int win,
            int draw,
            int loss,
            List<String> pv,
            List<String> pvSan) {}
}
