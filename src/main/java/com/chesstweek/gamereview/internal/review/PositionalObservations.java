package com.chesstweek.gamereview.internal.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Piece;
import io.github.wolfraam.chessgame.board.PieceType;
import io.github.wolfraam.chessgame.board.Side;
import io.github.wolfraam.chessgame.board.Square;
import io.github.wolfraam.chessgame.move.Move;

import com.chesstweek.gamereview.GameReviews.Observation;
import com.chesstweek.gamereview.GameReviews.Positional;

/**
 * Measured positional differences for a judged move that no tactic explains. Two matched
 * positions are compared: the end of the engine's best line, and the end of the played move
 * followed by the opponent's best reply line, both the same number of plies after the judged
 * position. That depth is the first one from {@link #MIN_PLIES} to {@link #MAX_PLIES} where both
 * positions are settled: the last move was not a capture, the side to move is not in check, and
 * the line's next stored move is not a capture, so a half-finished trade never shows as a
 * material change.
 *
 * <p>King cover and open files are compared only for a king on a wing in both positions. Only
 * features whose difference passes a fixed threshold are reported, and only as
 * observations: Stockfish's evaluation does not break its score into human reasons.
 */
final class PositionalObservations {

    static final int MIN_PLIES = 4;
    static final int MAX_PLIES = 8;
    /** Mobility swings with tactics, so only a difference of this many squares is reported. */
    static final int MOBILITY_DIFFERENCE = 10;

    static final String MOVER = "mover";
    static final String OPPONENT = "opponent";

    private static final List<Square> WHITE_MINOR_STARTS = List.of(Square.B1, Square.G1, Square.C1, Square.F1);
    private static final List<Square> BLACK_MINOR_STARTS = List.of(Square.B8, Square.G8, Square.C8, Square.F8);

    private PositionalObservations() {}

    /**
     * @param before the judged position
     * @param played the played move, legal in {@code before}
     * @param bestPv the engine's best line from {@code before}
     * @param replyPv the opponent's best line after the played move
     * @param opening whether the judged move is in the opening phase (development is measured only then)
     * @return the comparison, or null when the lines never reach a quiet matched depth
     */
    static Positional observe(ChessGame before, Move played, List<String> bestPv, List<String> replyPv, boolean opening) {
        Side mover = before.getSideToMove();
        ReplayedLine best = ReplayedLine.of(before, bestPv, MAX_PLIES + 1, null);
        ChessGame afterPlayed = before.clone();
        afterPlayed.playMove(played);
        ReplayedLine reply = ReplayedLine.of(afterPlayed, replyPv, MAX_PLIES, null);

        int available = Math.min(MAX_PLIES, Math.min(best.size(), reply.size() + 1));
        for (int plies = MIN_PLIES; plies <= available; plies++) {
            if (quiet(best, plies - 1) && quiet(reply, plies - 2)) {
                return new Positional(plies, compare(
                        mover,
                        best.after(plies - 1),
                        reply.after(plies - 2),
                        castled(best, 0, plies - 1),
                        castledAfterPlayed(before, played, reply, plies - 2),
                        opening));
            }
        }
        return null;
    }

    /** The comparison of two end positions, mover's features first within each feature. */
    static List<Observation> compare(
            Side mover, ChessGame best, ChessGame played, Set<Side> castledBest, Set<Side> castledPlayed,
            boolean opening) {
        var observations = new ArrayList<Observation>();
        int materialBest = ReplayedLine.balance(best, mover);
        int materialPlayed = ReplayedLine.balance(played, mover);
        if (materialBest != materialPlayed) {
            observations.add(new Observation("material", MOVER, materialBest, materialPlayed, List.of()));
        }
        forBothSides(mover, (side, who) -> addIfDiffers(
                observations, "bishop_pair", who, bishopPair(best, side), bishopPair(played, side), 1));
        forBothSides(mover, (side, who) -> {
            if (onWing(best, side) && onWing(played, side)) {
                addIfDiffers(observations, "king_shelter", who, kingShelter(best, side), kingShelter(played, side), 1);
            }
        });
        forBothSides(mover, (side, who) -> {
            if (onWing(best, side) && onWing(played, side)) {
                addIfDiffers(observations, "open_files_near_king", who, openFilesNearKing(best, side),
                        openFilesNearKing(played, side), 1);
            }
        });
        forBothSides(mover, (side, who) -> {
            int castlingBest = castling(best, side, castledBest);
            int castlingPlayed = castling(played, side, castledPlayed);
            if ((castlingBest == 0) != (castlingPlayed == 0)) {
                observations.add(new Observation("castling", who, castlingBest, castlingPlayed, List.of()));
            }
        });
        forBothSides(mover, (side, who) -> {
            List<Square> passedBest = passedPawns(best, side);
            List<Square> passedPlayed = passedPawns(played, side);
            if (passedBest.size() != passedPlayed.size()) {
                List<Square> more = passedPlayed.size() > passedBest.size() ? passedPlayed : passedBest;
                List<Square> fewer = more == passedPlayed ? passedBest : passedPlayed;
                List<String> extra = more.stream()
                        .filter(square -> fewer.stream().noneMatch(other -> other.x == square.x))
                        .map(square -> square.name)
                        .toList();
                observations.add(new Observation(
                        "passed_pawns", who, passedBest.size(), passedPlayed.size(), extra));
            }
        });
        forBothSides(mover, (side, who) -> addIfDiffers(
                observations, "isolated_pawns", who, isolatedPawns(best, side), isolatedPawns(played, side), 1));
        forBothSides(mover, (side, who) -> addIfDiffers(
                observations, "doubled_pawns", who, doubledPawns(best, side), doubledPawns(played, side), 1));
        if (opening) {
            forBothSides(mover, (side, who) -> addIfDiffers(
                    observations, "development", who, undeveloped(best, side), undeveloped(played, side), 1));
        }
        forBothSides(mover, (side, who) -> addIfDiffers(
                observations, "mobility", who, mobility(best, side), mobility(played, side), MOBILITY_DIFFERENCE));
        return List.copyOf(observations);
    }

    /** Runs {@code measure} for the mover, then its opponent, with each one's {@code side} name. */
    private static void forBothSides(Side mover, BiConsumer<Side, String> measure) {
        measure.accept(mover, MOVER);
        measure.accept(opposite(mover), OPPONENT);
    }

    private static void addIfDiffers(List<Observation> observations, String feature, String who, int best,
            int played, int threshold) {
        if (Math.abs(played - best) >= threshold) {
            observations.add(new Observation(feature, who, best, played, List.of()));
        }
    }

    /**
     * The position after line move {@code index} is settled: that move was not a capture, the
     * side to move is not in check, and the line's next move, when stored, is not a capture either,
     * so no capture sequence is pending.
     */
    private static boolean quiet(ReplayedLine line, int index) {
        if (index < 0 || takes(line, index) || line.after(index).isKingAttacked()) {
            return false;
        }
        return index + 1 >= line.size() || !takes(line, index + 1);
    }

    /** Whether line move {@code index} captures, en passant included. */
    private static boolean takes(ReplayedLine line, int index) {
        Move move = line.move(index);
        ChessGame before = line.before(index);
        Piece piece = before.getPiece(move.from);
        boolean enPassant = piece != null && piece.pieceType == PieceType.PAWN
                && move.from.x != move.to.x && before.getPiece(move.to) == null;
        return line.isCapture(index) || enPassant;
    }

    /** The sides that castle in moves 0 to {@code last} of the line. */
    private static Set<Side> castled(ReplayedLine line, int first, int last) {
        Set<Side> sides = new LinkedHashSet<>();
        for (int index = first; index <= last; index++) {
            Piece piece = line.before(index).getPiece(line.move(index).from);
            if (isCastling(piece, line.move(index))) {
                sides.add(piece.side);
            }
        }
        return sides;
    }

    private static Set<Side> castledAfterPlayed(ChessGame before, Move played, ReplayedLine reply, int last) {
        Set<Side> sides = castled(reply, 0, last);
        Piece piece = before.getPiece(played.from);
        if (isCastling(piece, played)) {
            sides.add(piece.side);
        }
        return sides;
    }

    private static boolean isCastling(Piece piece, Move move) {
        return piece != null && piece.pieceType == PieceType.KING && Math.abs(move.from.x - move.to.x) == 2;
    }

    /** 2 when the side castled in the line, 1 when it may still castle, 0 when it cannot. */
    static int castling(ChessGame game, Side side, Set<Side> castled) {
        if (castled.contains(side)) {
            return 2;
        }
        String rights = game.getFen().split(" ")[2];
        boolean any = side == Side.WHITE
                ? rights.contains("K") || rights.contains("Q")
                : rights.contains("k") || rights.contains("q");
        return any ? 1 : 0;
    }

    static int bishopPair(ChessGame game, Side side) {
        return game.getSquares(Piece.fromPieceTypeAndSide(PieceType.BISHOP, side)).size() >= 2 ? 1 : 0;
    }

    /**
     * Whether the side's king stands on a wing (the a to c or f to h files), where its pawn cover
     * matters. A king still in the centre loses "cover" with every normal central pawn move, so
     * cover and open files are compared only for a king on a wing in both positions.
     */
    static boolean onWing(ChessGame game, Side side) {
        Square king = king(game, side);
        return king != null && (king.x <= 2 || king.x >= 5);
    }

    /** The side's pawns on the king's file and the files beside it, one or two ranks in front of the king. */
    static int kingShelter(ChessGame game, Side side) {
        Square king = king(game, side);
        if (king == null) {
            return 0;
        }
        int forward = side == Side.WHITE ? 1 : -1;
        int count = 0;
        for (int file = king.x - 1; file <= king.x + 1; file++) {
            for (int step = 1; step <= 2; step++) {
                int rank = king.y + forward * step;
                if (file >= 0 && file < 8 && rank >= 0 && rank < 8
                        && isPawn(game.getPiece(Square.fromCoordinates(file, rank)), side)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Of the king's file and the files beside it, those with none of the side's own pawns. */
    static int openFilesNearKing(ChessGame game, Side side) {
        Square king = king(game, side);
        if (king == null) {
            return 0;
        }
        int count = 0;
        for (int file = Math.max(0, king.x - 1); file <= Math.min(7, king.x + 1); file++) {
            if (pawnsOnFile(game, side, file) == 0) {
                count++;
            }
        }
        return count;
    }

    /** Pawns with no enemy pawn ahead of them on the same or an adjacent file. */
    static List<Square> passedPawns(ChessGame game, Side side) {
        int forward = side == Side.WHITE ? 1 : -1;
        var passed = new ArrayList<Square>();
        for (Square pawn : pawns(game, side)) {
            boolean blocked = false;
            for (Square enemy : pawns(game, opposite(side))) {
                if (Math.abs(enemy.x - pawn.x) <= 1 && (enemy.y - pawn.y) * forward > 0) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                passed.add(pawn);
            }
        }
        passed.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.y, b.y));
        return passed;
    }

    /** Pawns with no friendly pawn on an adjacent file. */
    static int isolatedPawns(ChessGame game, Side side) {
        int count = 0;
        for (Square pawn : pawns(game, side)) {
            boolean left = pawn.x > 0 && pawnsOnFile(game, side, pawn.x - 1) > 0;
            boolean right = pawn.x < 7 && pawnsOnFile(game, side, pawn.x + 1) > 0;
            if (!left && !right) {
                count++;
            }
        }
        return count;
    }

    /** Pawns beyond the first on each file. */
    static int doubledPawns(ChessGame game, Side side) {
        int count = 0;
        for (int file = 0; file < 8; file++) {
            count += Math.max(0, pawnsOnFile(game, side, file) - 1);
        }
        return count;
    }

    /** Knights and bishops still on their starting squares. */
    static int undeveloped(ChessGame game, Side side) {
        List<Square> starts = side == Side.WHITE ? WHITE_MINOR_STARTS : BLACK_MINOR_STARTS;
        int count = 0;
        for (int index = 0; index < starts.size(); index++) {
            Piece piece = game.getPiece(starts.get(index));
            PieceType type = index < 2 ? PieceType.KNIGHT : PieceType.BISHOP;
            if (piece != null && piece.side == side && piece.pieceType == type) {
                count++;
            }
        }
        return count;
    }

    /**
     * Squares the side's knights, bishops, rooks, and queens attack that are not occupied by its
     * own pieces, counted geometrically. Pawns and the king are left out.
     */
    static int mobility(ChessGame game, Side side) {
        int count = 0;
        for (Square from : game.getOccupiedSquares()) {
            Piece piece = game.getPiece(from);
            if (piece.side != side || piece.pieceType == PieceType.PAWN || piece.pieceType == PieceType.KING) {
                continue;
            }
            for (Square square : TacticDetector.Attacks.attackedBy(game, from)) {
                Piece occupant = game.getPiece(square);
                if (occupant == null || occupant.side != side) {
                    count++;
                }
            }
        }
        return count;
    }

    private static List<Square> pawns(ChessGame game, Side side) {
        return List.copyOf(game.getSquares(Piece.fromPieceTypeAndSide(PieceType.PAWN, side)));
    }

    private static int pawnsOnFile(ChessGame game, Side side, int file) {
        int count = 0;
        for (Square pawn : pawns(game, side)) {
            if (pawn.x == file) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPawn(Piece piece, Side side) {
        return piece != null && piece.side == side && piece.pieceType == PieceType.PAWN;
    }

    private static Square king(ChessGame game, Side side) {
        Set<Square> kings = game.getSquares(Piece.fromPieceTypeAndSide(PieceType.KING, side));
        return kings.isEmpty() ? null : kings.iterator().next();
    }

    private static Side opposite(Side side) {
        return side == Side.WHITE ? Side.BLACK : Side.WHITE;
    }
}
