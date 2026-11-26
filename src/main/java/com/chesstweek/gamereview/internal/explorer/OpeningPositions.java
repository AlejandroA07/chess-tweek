package com.chesstweek.gamereview.internal.explorer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

import com.chesstweek.shared.chess.ExplorerPosition;

/** The opening positions of a standard game, as the My games explorer index stores them. */
public final class OpeningPositions {

    /** Plies indexed per game; later positions rarely repeat across games. */
    public static final int MAX_PLIES = 60;

    private OpeningPositions() {}

    /**
     * Replays space-separated SAN moves from the standard start and returns the position before
     * each of the first {@link #MAX_PLIES} moves with the move played from it. When the game ends
     * inside that range, its final position follows with no next move.
     *
     * @throws IllegalArgumentException when the moves are not legal
     */
    public static List<IndexedPosition> of(String movesSan) {
        Objects.requireNonNull(movesSan);
        String trimmed = movesSan.strip();
        String[] sans = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        var game = new ChessGame();
        var positions = new ArrayList<IndexedPosition>(Math.min(sans.length, MAX_PLIES) + 1);
        int ply = 0;
        for (String san : sans) {
            if (ply == MAX_PLIES) {
                return positions;
            }
            Move move;
            try {
                move = game.getMove(NotationType.SAN, san);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Game moves are not legal", exception);
            }
            if (move == null || !game.isLegalMove(move)) {
                throw new IllegalArgumentException("Game moves are not legal");
            }
            String key = ExplorerPosition.of(game).key();
            String uci = game.getNotation(NotationType.UCI, move);
            String canonicalSan = game.getNotation(NotationType.SAN, move);
            positions.add(new IndexedPosition(ply, key, uci, canonicalSan));
            game.playMove(move);
            ply++;
        }
        positions.add(new IndexedPosition(ply, ExplorerPosition.of(game).key(), null, null));
        return positions;
    }

    public record IndexedPosition(int ply, String key, String nextUci, String nextSan) {

        public IndexedPosition {
            Objects.requireNonNull(key);
            if ((nextUci == null) != (nextSan == null)) {
                throw new IllegalArgumentException("A next move has both notations or neither");
            }
        }
    }
}
