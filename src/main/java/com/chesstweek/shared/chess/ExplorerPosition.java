package com.chesstweek.shared.chess;

import java.util.Optional;
import java.util.regex.Pattern;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Piece;
import io.github.wolfraam.chessgame.board.PieceType;
import io.github.wolfraam.chessgame.board.Side;
import io.github.wolfraam.chessgame.board.Square;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

/**
 * A standard chess position as the opening explorer looks it up. Two positions are the same when
 * their pieces, side to move, castling rights, and en passant capture match; the move counters are
 * ignored, and an en passant square counts only when a legal en passant capture exists. This is
 * the convention the Lichess explorer uses, so transpositions meet at one key.
 *
 * <p>{@link #parse(String)} is the only way to build one from untrusted text.
 */
public final class ExplorerPosition {

    private static final int MAX_FEN_LENGTH = 100;
    private static final Pattern FEN = Pattern.compile(
            "[1-8pnbrqkPNBRQK/]{15,71} [wb] (-|K?Q?k?q?) (-|[a-h][36]) \\d{1,3} \\d{1,4}");

    private final String fen;
    private final String key;

    private ExplorerPosition(String fen, String key) {
        this.fen = fen;
        this.key = key;
    }

    /** Parses a full FEN of a legal standard position; empty when it is not one. */
    public static Optional<ExplorerPosition> parse(String fen) {
        if (fen == null || fen.length() > MAX_FEN_LENGTH || !FEN.matcher(fen).matches()) {
            return Optional.empty();
        }
        String placement = fen.substring(0, fen.indexOf(' '));
        if (!validPlacement(placement)) {
            return Optional.empty();
        }
        ChessGame game;
        try {
            game = new ChessGame(fen);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        return validKings(game) ? Optional.of(of(game)) : Optional.empty();
    }

    /** The position a library game is in now; the game must hold a legal standard position. */
    public static ExplorerPosition of(ChessGame game) {
        String full = game.getFen();
        String[] fields = full.split(" ");
        if (fields.length != 6) {
            throw new IllegalArgumentException("Unexpected FEN from the chess library");
        }
        String enPassant = legalEnPassant(game, fields[3]) ? fields[3] : "-";
        String key = fields[0] + " " + fields[1] + " " + fields[2] + " " + enPassant;
        String canonical = key + " " + fields[4] + " " + fields[5];
        return new ExplorerPosition(canonical, key);
    }

    /** The full FEN with an en passant square only when a capture there is legal. */
    public String fen() {
        return fen;
    }

    /** The lookup key: pieces, side to move, castling rights, and a legal en passant square. */
    public String key() {
        return key;
    }

    /** Whether White is to move. */
    public boolean whiteToMove() {
        return key.split(" ")[1].equals("w");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExplorerPosition position && position.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return fen;
    }

    private static boolean validPlacement(String placement) {
        String[] ranks = placement.split("/", -1);
        if (ranks.length != 8) {
            return false;
        }
        for (String rank : ranks) {
            int files = 0;
            for (char symbol : rank.toCharArray()) {
                files += Character.isDigit(symbol) ? symbol - '0' : 1;
            }
            if (files != 8) {
                return false;
            }
        }
        return true;
    }

    private static boolean validKings(ChessGame game) {
        int whiteKings = 0;
        int blackKings = 0;
        for (Square square : game.getOccupiedSquares()) {
            Piece piece = game.getPiece(square);
            if (piece.pieceType == PieceType.KING) {
                if (piece.side == Side.WHITE) {
                    whiteKings++;
                } else {
                    blackKings++;
                }
            }
            if (piece.pieceType == PieceType.PAWN) {
                if ("1".equals(square.row) || "8".equals(square.row)) {
                    return false;
                }
            }
        }
        if (whiteKings != 1 || blackKings != 1) {
            return false;
        }
        // The side that just moved may not be left in check.
        String fen = game.getFen();
        String[] fields = fen.split(" ");
        fields[1] = fields[1].equals("w") ? "b" : "w";
        fields[3] = "-";
        try {
            return !new ChessGame(String.join(" ", fields)).isKingAttacked();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean legalEnPassant(ChessGame game, String square) {
        if ("-".equals(square)) {
            return false;
        }
        for (Move move : game.getLegalMoves()) {
            String uci = game.getNotation(NotationType.UCI, move);
            String san = game.getNotation(NotationType.SAN, move);
            if (uci.substring(2, 4).equals(square)
                    && san.length() >= 2
                    && san.charAt(0) >= 'a'
                    && san.charAt(0) <= 'h'
                    && san.charAt(1) == 'x') {
                return true;
            }
        }
        return false;
    }
}
