package com.chesstweek.shared.chess;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Piece;
import io.github.wolfraam.chessgame.board.PieceType;
import io.github.wolfraam.chessgame.board.Square;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

/**
 * A line of legal moves from a start position, as the opening explorer walks it. Moves are UCI;
 * castling may also be written as the king taking its own rook ({@code e1h1}), the form the Lichess
 * explorer uses, and is stored in the standard form ({@code e1g1}).
 */
public record ExplorerLine(ExplorerPosition start, List<Step> steps) {

    public static final int MAX_MOVES = 200;
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");

    public ExplorerLine {
        Objects.requireNonNull(start);
        steps = List.copyOf(steps);
    }

    /** Plays the moves from the start; empty when any move is malformed or not legal. */
    public static Optional<ExplorerLine> play(ExplorerPosition start, List<String> moves) {
        Objects.requireNonNull(start);
        if (moves == null || moves.size() > MAX_MOVES) {
            return Optional.empty();
        }
        ChessGame game;
        try {
            game = new ChessGame(start.fen());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        var steps = new ArrayList<Step>(moves.size());
        for (String text : moves) {
            if (text == null || !UCI.matcher(text).matches()) {
                return Optional.empty();
            }
            String uci = standardCastling(game, text);
            Move move;
            try {
                move = game.getMove(NotationType.UCI, uci);
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
            if (move == null || !game.isLegalMove(move)) {
                return Optional.empty();
            }
            String san = game.getNotation(NotationType.SAN, move);
            game.playMove(move);
            steps.add(new Step(uci, san, ExplorerPosition.of(game)));
        }
        return Optional.of(new ExplorerLine(start, steps));
    }

    /** The position at the end of the line. */
    public ExplorerPosition end() {
        return steps.isEmpty() ? start : steps.getLast().position();
    }

    public record Step(String uci, String san, ExplorerPosition position) {

        public Step {
            Objects.requireNonNull(uci);
            Objects.requireNonNull(san);
            Objects.requireNonNull(position);
        }
    }

    private static String standardCastling(ChessGame game, String uci) {
        Square from = Square.fromName(uci.substring(0, 2));
        Square to = Square.fromName(uci.substring(2, 4));
        Piece mover = game.getPiece(from);
        Piece target = game.getPiece(to);
        if (from == null || to == null || mover == null || target == null || mover.pieceType != PieceType.KING
                || target.pieceType != PieceType.ROOK || mover.side != target.side
                || !from.row.equals(to.row)) {
            return uci;
        }
        String file = to.file;
        String kingFile = "h".equals(file) ? "g" : "a".equals(file) ? "c" : null;
        return kingFile == null ? uci : uci.substring(0, 2) + kingFile + uci.charAt(3);
    }
}
