package com.chesstweek.gamereview.internal.review;

import java.util.ArrayList;
import java.util.List;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Piece;
import io.github.wolfraam.chessgame.board.PieceType;
import io.github.wolfraam.chessgame.board.Side;
import io.github.wolfraam.chessgame.board.Square;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

/**
 * One stored engine line replayed position by position, for the side that moves first (the
 * gainer). Moves at even indices are the gainer's. Replay stops at the first move that is not
 * legal. {@code prior} is the opponent's move into the line's first position, when known; it
 * supplies the in-between-move case and a stricter material baseline.
 */
final class ReplayedLine {

    /** The opponent's move into the line's start, and the position it was played from. */
    record Prior(ChessGame position, Move move) {}

    private final Side gainer;
    private final List<ChessGame> positions = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private final Prior prior;

    private ReplayedLine(ChessGame start, List<String> pv, int maxPlies, Prior prior) {
        this.gainer = start.getSideToMove();
        this.prior = prior;
        ChessGame game = start.clone();
        positions.add(game.clone());
        for (String uci : pv.subList(0, Math.min(pv.size(), maxPlies))) {
            Move move = legal(game, uci);
            if (move == null) {
                break;
            }
            game.playMove(move);
            moves.add(move);
            positions.add(game.clone());
        }
    }

    static ReplayedLine of(ChessGame start, List<String> pv, int maxPlies, Prior prior) {
        return new ReplayedLine(start, pv, maxPlies, prior);
    }

    Side gainer() {
        return gainer;
    }

    Prior prior() {
        return prior;
    }

    int size() {
        return moves.size();
    }

    Move move(int index) {
        return moves.get(index);
    }

    /** The position before move {@code index}; {@code before(size())} is the line's end. */
    ChessGame before(int index) {
        return positions.get(index);
    }

    ChessGame after(int index) {
        return positions.get(index + 1);
    }

    /** The piece move {@code index} takes on its destination square, or null (en passant included as null). */
    Piece captured(int index) {
        return before(index).getPiece(move(index).to);
    }

    boolean isCapture(int index) {
        return captured(index) != null;
    }

    /** The gainer's material lead before move {@code index}, kings excluded. */
    int balance(int index) {
        return balance(before(index), gainer);
    }

    /**
     * Whether the gainer's capture at {@code capture} leaves it at least {@code margin} points
     * ahead of where the pattern started at {@code start}. Material is counted after the
     * opponent's reply to the capture, when the line has one, and the baseline is the higher of
     * the position at {@code start} and the position before the prior move, so a recapture of
     * material just lost never counts as a gain.
     */
    boolean wins(int start, int capture, int margin) {
        int settled = balance(Math.min(capture + 2, size()));
        int baseline = balance(start);
        if (prior != null) {
            baseline = Math.max(baseline, balance(prior.position(), gainer));
        }
        return settled - baseline >= margin;
    }

    static int balance(ChessGame game, Side side) {
        int total = 0;
        for (Square square : game.getOccupiedSquares()) {
            Piece piece = game.getPiece(square);
            if (piece.pieceType == PieceType.KING) {
                continue;
            }
            int value = TacticDetector.value(piece.pieceType);
            total += piece.side == side ? value : -value;
        }
        return total;
    }

    static Move legal(ChessGame game, String uci) {
        try {
            Move move = game.getMove(NotationType.UCI, uci);
            return move != null && game.isLegalMove(move) ? move : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
