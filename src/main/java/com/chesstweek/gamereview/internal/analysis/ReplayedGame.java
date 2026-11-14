package com.chesstweek.gamereview.internal.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Piece;
import io.github.wolfraam.chessgame.board.PieceType;
import io.github.wolfraam.chessgame.board.Side;
import io.github.wolfraam.chessgame.board.Square;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

/**
 * A standard game replayed from its SAN moves into every position it passed through. Position
 * {@code i} is the board after {@code i} plies; move {@code i} (1-based) is played from position
 * {@code i - 1}. Replay is the only place SAN is trusted, so every later rule works from
 * library-validated positions and UCI moves.
 */
public record ReplayedGame(List<Position> positions, List<PlayedMove> moves) {

    public static final int MAX_PLIES = 1_200;

    public ReplayedGame {
        positions = List.copyOf(positions);
        moves = List.copyOf(moves);
        if (positions.size() != moves.size() + 1) {
            throw new IllegalArgumentException("A replay has one more position than moves");
        }
    }

    /** Replays space-separated SAN moves from the standard start; illegal input is rejected. */
    public static ReplayedGame fromSan(String movesSan) {
        Objects.requireNonNull(movesSan);
        String trimmed = movesSan.strip();
        List<String> sans = trimmed.isEmpty() ? List.of() : List.of(trimmed.split("\\s+"));
        if (sans.size() > MAX_PLIES) {
            throw new IllegalArgumentException("Game is too long to analyze");
        }
        var game = new ChessGame();
        var positions = new ArrayList<Position>(sans.size() + 1);
        var moves = new ArrayList<PlayedMove>(sans.size());
        var history = new ArrayList<String>(sans.size());
        positions.add(position(game, 0, history));
        for (String san : sans) {
            Move move;
            try {
                move = game.getMove(NotationType.SAN, san);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Game moves are not legal", exception);
            }
            if (move == null || !game.isLegalMove(move)) {
                throw new IllegalArgumentException("Game moves are not legal");
            }
            Color mover = color(game.getSideToMove());
            String uci = game.getNotation(NotationType.UCI, move);
            game.playMove(move);
            history.add(uci);
            moves.add(new PlayedMove(moves.size() + 1, mover, san, uci));
            positions.add(position(game, history.size(), history));
        }
        return new ReplayedGame(positions, moves);
    }

    public int plyCount() {
        return moves.size();
    }

    /** The position move {@code ply} was played from. */
    public Position before(int ply) {
        return positions.get(checkedPly(ply) - 1);
    }

    /** The position move {@code ply} produced. */
    public Position after(int ply) {
        return positions.get(checkedPly(ply));
    }

    private int checkedPly(int ply) {
        if (ply < 1 || ply > plyCount()) {
            throw new IllegalArgumentException("No such move in this game");
        }
        return ply;
    }

    private static Position position(ChessGame game, int index, List<String> history) {
        int queens = 0;
        int nonPawnMaterial = 0;
        for (Square square : game.getOccupiedSquares()) {
            Piece piece = game.getPiece(square);
            if (piece.pieceType == PieceType.QUEEN) {
                queens++;
            }
            nonPawnMaterial += materialPoints(piece.pieceType);
        }
        boolean noLegalMoves = game.getLegalMoves().isEmpty();
        Terminal terminal = !noLegalMoves
                ? Terminal.NONE
                : game.isKingAttacked() ? Terminal.CHECKMATE : Terminal.STALEMATE;
        return new Position(
                index,
                game.getFen(),
                color(game.getSideToMove()),
                List.copyOf(history),
                queens,
                nonPawnMaterial,
                terminal);
    }

    /** Standard material points for non-pawn pieces; pawns and kings count zero. */
    static int materialPoints(PieceType type) {
        return switch (type) {
            case KNIGHT, BISHOP -> 3;
            case ROOK -> 5;
            case QUEEN -> 9;
            case PAWN, KING -> 0;
        };
    }

    private static Color color(Side side) {
        return side == Side.WHITE ? Color.WHITE : Color.BLACK;
    }

    public enum Color {
        WHITE,
        BLACK
    }

    /** Whether the side to move has no legal moves, and why. */
    public enum Terminal {
        NONE,
        CHECKMATE,
        STALEMATE
    }

    /**
     * A position the engine can be asked about. {@code movesFromStart} lets the engine see the
     * game history, so repetitions and the fifty-move rule are judged from the real game.
     */
    public record Position(
            int index,
            String fen,
            Color sideToMove,
            List<String> movesFromStart,
            int queens,
            int nonPawnMaterial,
            Terminal terminal) {

        public Position {
            Objects.requireNonNull(fen);
            Objects.requireNonNull(sideToMove);
            Objects.requireNonNull(terminal);
            movesFromStart = List.copyOf(movesFromStart);
        }
    }

    public record PlayedMove(int ply, Color mover, String san, String uci) {}
}
