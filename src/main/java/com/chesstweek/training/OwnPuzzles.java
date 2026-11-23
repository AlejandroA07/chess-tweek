package com.chesstweek.training;

import java.time.Instant;
import java.util.List;

/**
 * Game-derived Own Puzzles, stored apart from the Lichess Puzzle Library. Each one comes from
 * one owner inaccuracy, mistake, or blunder and keeps its source game, ply, and analysis version
 * as plain scoped values.
 */
public interface OwnPuzzles {

    /**
     * Stores every puzzle whose source position is not stored for the account yet and returns
     * how many were new, so repeating a call never creates a duplicate. A puzzle with an invalid
     * field, or whose engine line or previous move cannot be replayed, is skipped on its own. An
     * unusable opening ECO or name is stored as unknown.
     *
     * @throws IllegalArgumentException when the account or the list is invalid
     */
    int create(String accountId, List<NewOwnPuzzle> puzzles);

    /** Every stored Own Puzzle's source and current tactic labels, for refreshing those labels. */
    List<StoredLabels> labels();

    /**
     * Replaces the tactic labels of the Own Puzzle at {@code source} when they differ from the
     * stored ones, and returns whether anything changed. Nothing else about the puzzle, its
     * attempts, or its Training List changes.
     *
     * @throws IllegalArgumentException when the source or a label is invalid
     */
    boolean replaceTactics(PuzzleSource source, List<Tactic> tactics);

    /** Identifies one stored Own Puzzle by its account and source game position. */
    record PuzzleSource(String accountId, String gameSource, String gameId, int sourcePly) {}

    /** One stored Own Puzzle's source, the analysis version it came from, and its tactic labels. */
    record StoredLabels(PuzzleSource source, long analysisVersionId, List<Tactic> tactics) {

        public StoredLabels {
            tactics = List.copyOf(tactics);
        }
    }

    /**
     * One labelled owner move. The puzzle starts at {@code positionFen}, the position the move was
     * played from. {@code engineLine} is the engine's top line from that position, best move
     * first; the solution is its first three moves, or only its first move when it is shorter.
     * {@code previousPositionFen} and {@code previousMoveUci} are the opponent's move into the
     * position, both null when the owner's move was the game's first.
     */
    record NewOwnPuzzle(
            String gameSource,
            String gameId,
            int sourcePly,
            long analysisVersionId,
            String positionFen,
            String previousPositionFen,
            String previousMoveUci,
            List<String> engineLine,
            String label,
            String phase,
            String openingEco,
            String openingName,
            Instant gamePlayedAt,
            List<Tactic> tactics) {

        public NewOwnPuzzle {
            engineLine = engineLine == null ? List.of() : List.copyOf(engineLine);
            tactics = tactics == null ? List.of() : List.copyOf(tactics);
        }
    }

    /** A proven tactic label: {@code kind} is {@code allowed} or {@code missed}. */
    record Tactic(String theme, String kind) {}
}
