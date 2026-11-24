package com.chesstweek.gamereview.internal.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Piece;
import io.github.wolfraam.chessgame.board.PieceType;
import io.github.wolfraam.chessgame.board.Side;
import io.github.wolfraam.chessgame.board.Square;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

import com.chesstweek.gamereview.GameReviews.Tactic;

/**
 * Conservative tactic labels for one judged move, proven from the stored engine lines rather than
 * guessed. A label is given only when the line itself shows the gain:
 *
 * <ul>
 *   <li><b>Missed or allowed forced mate</b>: the stored mate event.
 *   <li><b>Back-rank</b>: that mate is delivered by a rook or queen on the mated king's own back
 *       rank, with the king on that rank.
 *   <li><b>Hanging piece</b>: the line's first move for the gaining side captures a knight,
 *       bishop, rook, or queen that no enemy piece defended.
 *   <li><b>Fork</b>: one of the gaining side's moves in the first ten plies of the line lands a piece that
 *       attacks two or more enemy pieces worth at least a knight (or the king), each either the
 *       king, worth more than the attacker, or undefended; and the same side captures on one of
 *       those squares within the next eight plies of the line.
 *   <li><b>Named motifs</b>: discovered checks and attacks, pins, skewers, removing the
 *       defender, trapped pieces, deflections, and in-between moves, each proven by a material gain
 *       later in the same line ({@link LineMotifs}).
 *   <li><b>General tactics</b>: a mistake or blunder with none of the above where the line still
 *       changes material by at least two points.
 * </ul>
 *
 * "Allowed" labels come from the opponent's best reply line after the played move; "missed"
 * labels come from the best line before it. Overloading, interference, clearance, attraction, and
 * x-rays are not labeled. Attacks are counted geometrically (pins are ignored), which can only
 * make a piece look defended, never hanging.
 *
 * <p>Any change here or in {@link LineMotifs} that can change a label must raise
 * {@link com.chesstweek.gamereview.GameReviews#TACTIC_RULES_VERSION}.
 */
final class TacticDetector {

    static final String ALLOWED = "allowed";
    static final String MISSED = "missed";

    private static final int LINE_PLIES = 6;
    /**
     * Forks often follow a forcing sequence (check, trade, then the fork), so the fork may land in
     * the first ten plies and the forked piece may be taken up to eight plies later.
     */
    private static final int FORK_PLIES = 10;
    private static final int FORK_GAIN_PLIES = 8;
    private static final int GENERAL_MATERIAL = 2;

    private TacticDetector() {}

    /** The opponent's move into the judged position and the position it was played from. */
    record Previous(String fenBefore, String uci) {}

    static List<Tactic> detect(
            String fenBefore,
            String played,
            String label,
            String mateEvent,
            List<String> bestPv,
            List<String> replyPv) {
        return detect(fenBefore, played, label, mateEvent, bestPv, replyPv, null);
    }

    /**
     * @param fenBefore position before the played move
     * @param played the played move in UCI
     * @param label the move's severity label
     * @param mateEvent the stored mate event
     * @param bestPv the best line from {@code fenBefore}, or empty
     * @param replyPv the opponent's best line after the played move, or empty
     * @param previous the opponent's move into {@code fenBefore}, or null for the game's first move
     */
    static List<Tactic> detect(
            String fenBefore,
            String played,
            String label,
            String mateEvent,
            List<String> bestPv,
            List<String> replyPv,
            Previous previous) {
        if (label.equals("none")) {
            return List.of();
        }
        var tactics = new ArrayList<Tactic>();
        try {
            ChessGame before = new ChessGame(fenBefore);
            Optional<ChessGame> after = play(before, played);
            if (after.isEmpty()) {
                return List.of();
            }
            if (mateEvent.equals("allowed_mate")) {
                tactics.add(new Tactic("allowed_mate", ALLOWED, null, null, List.of()));
                backRank(after.get(), replyPv, ALLOWED).ifPresent(tactics::add);
            }
            if (mateEvent.equals("missed_mate")) {
                tactics.add(new Tactic("missed_mate", MISSED, null, null, List.of()));
                backRank(before, bestPv, MISSED).ifPresent(tactics::add);
            }
            if (tactics.isEmpty()) {
                Move playedMove = move(before, played).orElseThrow();
                hanging(after.get(), replyPv, ALLOWED).ifPresent(tactics::add);
                fork(after.get(), replyPv, ALLOWED).ifPresent(tactics::add);
                tactics.addAll(LineMotifs.detect(ReplayedLine.of(after.get(), replyPv, LineMotifs.MAX_PLIES,
                        new ReplayedLine.Prior(before, playedMove)), ALLOWED));
                hanging(before, bestPv, MISSED).ifPresent(tactics::add);
                fork(before, bestPv, MISSED).ifPresent(tactics::add);
                tactics.addAll(LineMotifs.detect(ReplayedLine.of(before, bestPv, LineMotifs.MAX_PLIES,
                        prior(previous, before)), MISSED));
            }
            if (tactics.isEmpty() && !label.equals("inaccuracy")) {
                general(after.get(), replyPv, ALLOWED).ifPresent(tactics::add);
                if (tactics.isEmpty()) {
                    general(before, bestPv, MISSED).ifPresent(tactics::add);
                }
            }
        } catch (RuntimeException exception) {
            // An unreadable stored position yields no label rather than a wrong one.
            return List.of();
        }
        return List.copyOf(tactics);
    }

    /** The line's first move captures an undefended knight, bishop, rook, or queen. */
    private static Optional<Tactic> hanging(ChessGame start, List<String> pv, String kind) {
        if (pv.isEmpty()) {
            return Optional.empty();
        }
        Move first = move(start, pv.getFirst()).orElse(null);
        if (first == null) {
            return Optional.empty();
        }
        Piece captured = start.getPiece(first.to);
        if (captured == null || value(captured.pieceType) < 3 || captured.pieceType == PieceType.KING) {
            return Optional.empty();
        }
        if (Attacks.isAttacked(start, first.to, captured.side)) {
            return Optional.empty();
        }
        return Optional.of(new Tactic("hanging_piece", kind, name(captured.pieceType), first.to.name, List.of()));
    }

    /** A move in the line attacks two worthwhile targets, and one of them is then captured. */
    private static Optional<Tactic> fork(ChessGame start, List<String> pv, String kind) {
        ChessGame game = start.clone();
        Side gainer = start.getSideToMove();
        List<Move> moves = new ArrayList<>();
        for (String uci : pv.subList(0, Math.min(pv.size(), FORK_PLIES + FORK_GAIN_PLIES))) {
            Move next = move(game, uci).orElse(null);
            if (next == null) {
                break;
            }
            moves.add(next);
            game.playMove(next);
        }
        ChessGame replay = start.clone();
        for (int index = 0; index < moves.size(); index++) {
            Move candidate = moves.get(index);
            replay.playMove(candidate);
            if (index % 2 != 0 || index >= FORK_PLIES) {
                continue;
            }
            Piece forker = replay.getPiece(candidate.to);
            if (forker == null || forker.side != gainer) {
                continue;
            }
            Set<Square> targets = new LinkedHashSet<>();
            for (Square attacked : Attacks.attackedBy(replay, candidate.to)) {
                Piece target = replay.getPiece(attacked);
                if (target == null || target.side == gainer || value(target.pieceType) < 3) {
                    continue;
                }
                boolean worthwhile = target.pieceType == PieceType.KING
                        || value(target.pieceType) > value(forker.pieceType)
                        || !Attacks.isAttacked(replay, attacked, target.side);
                if (worthwhile) {
                    targets.add(attacked);
                }
            }
            if (targets.size() < 2) {
                continue;
            }
            // The gain must happen in the line: the same side captures on a target square.
            for (int later = index + 2; later < moves.size() && later <= index + FORK_GAIN_PLIES; later += 2) {
                if (targets.contains(moves.get(later).to) && isCapture(start, moves, later)) {
                    List<String> names = targets.stream().map(square -> square.name).toList();
                    return Optional.of(new Tactic(
                            "fork", kind, name(forker.pieceType), candidate.to.name, names, index));
                }
            }
        }
        return Optional.empty();
    }

    /** The line ends in mate given by a rook or queen on the mated king's back rank. */
    private static Optional<Tactic> backRank(ChessGame start, List<String> pv, String kind) {
        ChessGame game = start.clone();
        Move last = null;
        for (String uci : pv) {
            Move next = move(game, uci).orElse(null);
            if (next == null) {
                return Optional.empty();
            }
            game.playMove(next);
            last = next;
        }
        if (last == null || !game.getLegalMoves().isEmpty() || !game.isKingAttacked()) {
            return Optional.empty();
        }
        Piece mater = game.getPiece(last.to);
        Side mated = game.getSideToMove();
        int backRank = mated == Side.WHITE ? 0 : 7;
        Square king = game.getSquares(Piece.fromPieceTypeAndSide(PieceType.KING, mated)).iterator().next();
        boolean heavy = mater.pieceType == PieceType.ROOK || mater.pieceType == PieceType.QUEEN;
        if (heavy && last.to.y == backRank && king.y == backRank) {
            return Optional.of(new Tactic("back_rank", kind, name(mater.pieceType), last.to.name, List.of()));
        }
        return Optional.empty();
    }

    /** No narrower label, but the line changes material by at least two points for the gainer. */
    private static Optional<Tactic> general(ChessGame start, List<String> pv, String kind) {
        Side gainer = start.getSideToMove();
        int startBalance = balance(start, gainer);
        ChessGame game = start.clone();
        int played = 0;
        int endBalance = startBalance;
        for (String uci : pv.subList(0, Math.min(pv.size(), LINE_PLIES))) {
            Move next = move(game, uci).orElse(null);
            if (next == null) {
                break;
            }
            game.playMove(next);
            played++;
            // Measure after the losing side has had its reply.
            if (played % 2 == 0) {
                endBalance = balance(game, gainer);
            }
        }
        if (endBalance - startBalance >= GENERAL_MATERIAL) {
            return Optional.of(new Tactic("general_tactics", kind, null, null, List.of()));
        }
        return Optional.empty();
    }

    private static boolean isCapture(ChessGame start, List<Move> moves, int index) {
        ChessGame game = start.clone();
        for (int played = 0; played < index; played++) {
            game.playMove(moves.get(played));
        }
        return game.getPiece(moves.get(index).to) != null;
    }

    private static int balance(ChessGame game, Side side) {
        int total = 0;
        for (Square square : game.getOccupiedSquares()) {
            Piece piece = game.getPiece(square);
            if (piece.pieceType == PieceType.KING) {
                continue;
            }
            total += piece.side == side ? value(piece.pieceType) : -value(piece.pieceType);
        }
        return total;
    }

    /** The previous move as a line prior, only when it really leads to {@code position}. */
    private static ReplayedLine.Prior prior(Previous previous, ChessGame position) {
        if (previous == null || previous.fenBefore() == null || previous.uci() == null) {
            return null;
        }
        try {
            ChessGame game = new ChessGame(previous.fenBefore());
            Move move = move(game, previous.uci()).orElse(null);
            if (move == null) {
                return null;
            }
            ChessGame next = game.clone();
            next.playMove(move);
            return placement(next.getFen()).equals(placement(position.getFen()))
                    ? new ReplayedLine.Prior(game, move)
                    : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Placement, side to move, castling, and en passant; the move counters are ignored. */
    private static String placement(String fen) {
        String[] fields = fen.split(" ");
        return String.join(" ", Arrays.copyOf(fields, Math.min(fields.length, 4)));
    }

    private static Optional<ChessGame> play(ChessGame start, String uci) {
        ChessGame game = start.clone();
        Optional<Move> move = move(game, uci);
        move.ifPresent(game::playMove);
        return move.map(ignored -> game);
    }

    private static Optional<Move> move(ChessGame game, String uci) {
        try {
            Move move = game.getMove(NotationType.UCI, uci);
            return move != null && game.isLegalMove(move) ? Optional.of(move) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    static int value(PieceType type) {
        return switch (type) {
            case PAWN -> 1;
            case KNIGHT, BISHOP -> 3;
            case ROOK -> 5;
            case QUEEN -> 9;
            case KING -> 100;
        };
    }

    private static String name(PieceType type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Geometric attacks: which squares a piece attacks, ignoring pins and whose move it is. */
    static final class Attacks {

        private static final int[][] KNIGHT = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
        private static final int[][] KING = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        private static final int[][] STRAIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        private Attacks() {}

        /** Whether any piece of {@code defender} attacks {@code square}. */
        static boolean isAttacked(ChessGame game, Square square, Side defender) {
            for (Square from : game.getOccupiedSquares()) {
                Piece piece = game.getPiece(from);
                if (piece.side == defender && !from.equals(square) && attackedBy(game, from).contains(square)) {
                    return true;
                }
            }
            return false;
        }

        /** The squares of every {@code side} piece that attacks {@code square}. */
        static List<Square> attackers(ChessGame game, Square square, Side side) {
            List<Square> attackers = new ArrayList<>();
            for (Square from : game.getOccupiedSquares()) {
                Piece piece = game.getPiece(from);
                if (piece.side == side && !from.equals(square) && attackedBy(game, from).contains(square)) {
                    attackers.add(from);
                }
            }
            return attackers;
        }

        /** Every square the piece on {@code from} attacks. */
        static Set<Square> attackedBy(ChessGame game, Square from) {
            Piece piece = game.getPiece(from);
            Set<Square> squares = new LinkedHashSet<>();
            if (piece == null) {
                return squares;
            }
            switch (piece.pieceType) {
                case PAWN -> {
                    int direction = piece.side == Side.WHITE ? 1 : -1;
                    add(squares, from.x - 1, from.y + direction);
                    add(squares, from.x + 1, from.y + direction);
                }
                case KNIGHT -> steps(squares, from, KNIGHT);
                case KING -> steps(squares, from, KING);
                case BISHOP -> rays(game, squares, from, DIAGONAL);
                case ROOK -> rays(game, squares, from, STRAIGHT);
                case QUEEN -> {
                    rays(game, squares, from, DIAGONAL);
                    rays(game, squares, from, STRAIGHT);
                }
            }
            return squares;
        }

        private static void steps(Set<Square> squares, Square from, int[][] offsets) {
            for (int[] offset : offsets) {
                add(squares, from.x + offset[0], from.y + offset[1]);
            }
        }

        private static void rays(ChessGame game, Set<Square> squares, Square from, int[][] directions) {
            for (int[] direction : directions) {
                int x = from.x + direction[0];
                int y = from.y + direction[1];
                while (x >= 0 && x < 8 && y >= 0 && y < 8) {
                    Square square = Square.fromCoordinates(x, y);
                    squares.add(square);
                    if (game.getPiece(square) != null) {
                        break;
                    }
                    x += direction[0];
                    y += direction[1];
                }
            }
        }

        private static void add(Set<Square> squares, int x, int y) {
            if (x >= 0 && x < 8 && y >= 0 && y < 8) {
                squares.add(Square.fromCoordinates(x, y));
            }
        }
    }
}
