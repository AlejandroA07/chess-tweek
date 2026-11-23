package com.chesstweek.gamereview.internal.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Piece;
import io.github.wolfraam.chessgame.board.PieceType;
import io.github.wolfraam.chessgame.board.Side;
import io.github.wolfraam.chessgame.board.Square;
import io.github.wolfraam.chessgame.move.Move;

import com.chesstweek.gamereview.GameReviews.Tactic;
import com.chesstweek.gamereview.internal.review.TacticDetector.Attacks;

/**
 * The Stage 8B named motifs, each proven inside one replayed engine line. A pattern on the board
 * is never enough: every motif also needs the gain that follows from it in the same line, a
 * capture that leaves the gainer at least {@value #MATERIAL} points ahead ({@link
 * ReplayedLine#wins}). The pattern must appear in the first {@value #PATTERN_PLIES} plies.
 *
 * <ul>
 *   <li><b>Discovered check</b>: a gainer move uncovers a check by another piece, and the gain is
 *       taken by that move or the next gainer move.
 *   <li><b>Discovered attack</b>: a gainer move uncovers a slider's attack on a piece worth at
 *       least a knight that is worth more than the slider or undefended; within the next two
 *       gainer moves the gainer captures on that square, or captures with the piece that moved.
 *   <li><b>Pin</b>: a gainer slider, an enemy piece worth at least a knight, and behind it the
 *       enemy king or a more valuable piece stand on one line; the pinned piece is later captured
 *       on its square while the same three pieces still stand there.
 *   <li><b>Skewer</b>: a gainer slider moves to attack the enemy king or a piece worth more than
 *       the piece behind it; that front piece steps off the line, and the slider takes the piece
 *       behind at once.
 *   <li><b>Removing the defender</b>: the gainer captures the only defender of an enemy piece
 *       worth at least a knight, and takes that piece with its next move.
 *   <li><b>Trapped piece</b>: after a gainer move, an enemy knight, bishop, rook, or queen that is
 *       not pinned to its king stands attacked and losing material, and every legal move of it
 *       does too unless it takes something at least as valuable; it is later captured.
 *   <li><b>Deflection</b>: a gainer check, or a capture that is recaptured, makes the replying
 *       piece leave the only defence of an enemy piece worth at least a knight, which the gainer
 *       takes at once.
 *   <li><b>In-between move</b>: right after the opponent captures, the gainer could recapture but
 *       first gives check or takes a piece worth at least a knight, then recaptures later, ending
 *       at least {@value #MATERIAL} points better than the direct recapture could at best.
 * </ul>
 *
 * Each motif reports its earliest occurrence in the line.
 */
final class LineMotifs {

    /** The pattern must be on the board within this many plies of the line's start. */
    static final int PATTERN_PLIES = 10;
    /** The gain must follow within this many plies of the pattern. */
    static final int GAIN_PLIES = 8;
    /** The longest prefix of a line any motif reads. */
    static final int MAX_PLIES = PATTERN_PLIES + GAIN_PLIES + 2;
    static final int MATERIAL = 2;

    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] STRAIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private LineMotifs() {}

    static List<Tactic> detect(ReplayedLine line, String kind) {
        List<Tactic> found = new ArrayList<>();
        discovered(line, kind).ifPresent(found::add);
        pin(line, kind).ifPresent(found::add);
        skewer(line, kind).ifPresent(found::add);
        removingDefender(line, kind).ifPresent(found::add);
        trapped(line, kind).ifPresent(found::add);
        deflection(line, kind).ifPresent(found::add);
        inBetween(line, kind).ifPresent(found::add);
        return found;
    }

    static Optional<Tactic> discovered(ReplayedLine line, String kind) {
        Side gainer = line.gainer();
        for (int i = 0; i < Math.min(line.size(), PATTERN_PLIES); i += 2) {
            Move move = line.move(i);
            ChessGame before = line.before(i);
            ChessGame after = line.after(i);
            Piece mover = before.getPiece(move.from);
            if (mover.pieceType == PieceType.KING && Math.abs(move.from.x - move.to.x) == 2) {
                continue; // castling moves the rook; that is not a discovery
            }
            for (Square from : after.getOccupiedSquares()) {
                Piece slider = after.getPiece(from);
                if (slider.side != gainer || !isSlider(slider) || from.equals(move.to)) {
                    continue;
                }
                Set<Square> earlier = Attacks.attackedBy(before, from);
                if (!earlier.contains(move.from)) {
                    continue;
                }
                Set<Square> uncovered = new LinkedHashSet<>(Attacks.attackedBy(after, from));
                uncovered.removeAll(earlier);
                List<String> targets = new ArrayList<>();
                boolean check = false;
                for (Square square : uncovered) {
                    Piece target = after.getPiece(square);
                    if (target == null || target.side == gainer) {
                        continue;
                    }
                    if (target.pieceType == PieceType.KING) {
                        check = true;
                        targets.add(square.name);
                    } else if (value(target) >= 3 && (value(target) > value(slider)
                            || !Attacks.isAttacked(after, square, target.side))) {
                        targets.add(square.name);
                    }
                }
                if (targets.isEmpty()) {
                    continue;
                }
                if (check) {
                    for (int j = i; j <= i + 2 && j < line.size(); j += 2) {
                        if (line.isCapture(j) && line.wins(i, j, MATERIAL)) {
                            return Optional.of(tactic("discovered_check", kind, slider, from, targets, i));
                        }
                    }
                    continue;
                }
                for (int j = i + 2; j <= i + 4 && j < line.size(); j += 2) {
                    Move gain = line.move(j);
                    boolean onTarget = targets.contains(gain.to.name) || gain.from.equals(move.to);
                    if (onTarget && line.isCapture(j) && line.wins(i, j, MATERIAL)) {
                        return Optional.of(tactic("discovered_attack", kind, slider, from, targets, i));
                    }
                }
            }
        }
        return Optional.empty();
    }

    static Optional<Tactic> pin(ReplayedLine line, String kind) {
        Side gainer = line.gainer();
        for (int i = 0; i < Math.min(line.size(), PATTERN_PLIES); i += 2) {
            for (Pin pin : pins(line.after(i), gainer)) {
                for (int j = i + 2; j < line.size() && j <= i + GAIN_PLIES; j += 2) {
                    if (!pin.standsIn(line.before(j))) {
                        break;
                    }
                    if (line.move(j).to.equals(pin.pinned) && line.wins(i, j, MATERIAL)) {
                        return Optional.of(tactic("pin", kind, pin.piece, pin.slider,
                                List.of(pin.pinned.name, pin.behind.name), i));
                    }
                }
            }
        }
        return Optional.empty();
    }

    static Optional<Tactic> skewer(ReplayedLine line, String kind) {
        for (int i = 0; i + 2 < line.size() && i < PATTERN_PLIES; i += 2) {
            Move move = line.move(i);
            ChessGame after = line.after(i);
            Piece slider = after.getPiece(move.to);
            if (!isSlider(slider)) {
                continue;
            }
            for (int[] direction : directions(slider)) {
                Square front = firstPiece(after, move.to, direction);
                Square behind = front == null ? null : firstPiece(after, front, direction);
                if (behind == null) {
                    continue;
                }
                Piece first = after.getPiece(front);
                Piece second = after.getPiece(behind);
                if (first.side == slider.side || second.side == slider.side
                        || second.pieceType == PieceType.KING || value(second) < 3) {
                    continue;
                }
                boolean frontWorthMore = first.pieceType == PieceType.KING || value(first) > value(second);
                Move reply = line.move(i + 1);
                Move take = line.move(i + 2);
                if (frontWorthMore && reply.from.equals(front)
                        && take.from.equals(move.to) && take.to.equals(behind)
                        && line.wins(i, i + 2, MATERIAL)) {
                    return Optional.of(tactic("skewer", kind, slider, move.to,
                            List.of(front.name, behind.name), i));
                }
            }
        }
        return Optional.empty();
    }

    static Optional<Tactic> removingDefender(ReplayedLine line, String kind) {
        Side gainer = line.gainer();
        for (int j = 0; j + 2 < line.size() && j < PATTERN_PLIES; j += 2) {
            Piece defender = line.captured(j);
            if (defender == null) {
                continue;
            }
            ChessGame before = line.before(j);
            Square defenderSquare = line.move(j).to;
            Move next = line.move(j + 2);
            Piece guarded = before.getPiece(next.to);
            if (guarded == null || guarded.side == gainer || guarded.pieceType == PieceType.KING
                    || value(guarded) < 3 || next.to.equals(defenderSquare)) {
                continue;
            }
            boolean onlyDefender = Attacks.attackedBy(before, defenderSquare).contains(next.to)
                    && Attacks.attackers(before, next.to, guarded.side).size() == 1;
            if (onlyDefender && line.isCapture(j + 2) && line.wins(j, j + 2, MATERIAL)) {
                return Optional.of(tactic("removing_defender", kind, defender, defenderSquare,
                        List.of(next.to.name), j));
            }
        }
        return Optional.empty();
    }

    static Optional<Tactic> trapped(ReplayedLine line, String kind) {
        Side gainer = line.gainer();
        for (int i = 0; i < Math.min(line.size(), PATTERN_PLIES); i += 2) {
            ChessGame after = line.after(i);
            if (after.isKingAttacked()) {
                continue;
            }
            for (Square square : after.getOccupiedSquares()) {
                Piece piece = after.getPiece(square);
                if (piece.side == gainer || piece.pieceType == PieceType.KING
                        || piece.pieceType == PieceType.PAWN || !isTrapped(after, square, gainer)) {
                    continue;
                }
                Square at = square;
                for (int k = i + 1; k < line.size() && k <= i + GAIN_PLIES; k++) {
                    Move move = line.move(k);
                    if (k % 2 == 1) {
                        if (move.from.equals(at)) {
                            at = move.to;
                        }
                    } else if (move.to.equals(at)) {
                        if (line.wins(i, k, MATERIAL)) {
                            return Optional.of(tactic("trapped_piece", kind, piece, square, List.of(), i));
                        }
                        break;
                    }
                }
            }
        }
        return Optional.empty();
    }

    static Optional<Tactic> deflection(ReplayedLine line, String kind) {
        Side gainer = line.gainer();
        for (int j = 0; j + 2 < line.size() && j < PATTERN_PLIES; j += 2) {
            Move forcing = line.move(j);
            ChessGame after = line.after(j);
            boolean check = after.isKingAttacked();
            if (!check && !line.isCapture(j)) {
                continue;
            }
            Move reply = line.move(j + 1);
            if (!check && !reply.to.equals(forcing.to)) {
                continue;
            }
            Piece defender = after.getPiece(reply.from);
            Move take = line.move(j + 2);
            Piece target = after.getPiece(take.to);
            if (target == null || target.side == gainer || target.pieceType == PieceType.KING
                    || value(target) < 3 || take.to.equals(reply.to)) {
                continue;
            }
            boolean defended = Attacks.attackedBy(after, reply.from).contains(take.to);
            boolean leftUndefended = !Attacks.isAttacked(line.after(j + 1), take.to, target.side);
            if (defended && leftUndefended && line.isCapture(j + 2) && line.wins(j, j + 2, MATERIAL)) {
                return Optional.of(tactic("deflection", kind, defender, reply.from,
                        List.of(take.to.name), j));
            }
        }
        return Optional.empty();
    }

    static Optional<Tactic> inBetween(ReplayedLine line, String kind) {
        Side gainer = line.gainer();
        for (int g = 0; g < Math.min(line.size(), PATTERN_PLIES); g += 2) {
            ChessGame capturedFrom;
            Move capture;
            if (g == 0) {
                if (line.prior() == null) {
                    continue;
                }
                capturedFrom = line.prior().position();
                capture = line.prior().move();
            } else {
                capturedFrom = line.before(g - 1);
                capture = line.move(g - 1);
            }
            Piece taken = capturedFrom.getPiece(capture.to);
            if (taken == null || taken.side != gainer) {
                continue;
            }
            Square square = capture.to;
            ChessGame before = line.before(g);
            Move move = line.move(g);
            boolean couldRecapture = before.getLegalMoves().stream().anyMatch(legal -> legal.to.equals(square));
            if (!couldRecapture || move.to.equals(square)) {
                continue;
            }
            Piece takenNow = line.captured(g);
            boolean forcing = line.after(g).isKingAttacked() || (takenNow != null && value(takenNow) >= 3);
            if (!forcing) {
                continue;
            }
            int directBest = value(before.getPiece(square));
            for (int m = g + 2; m < line.size() && m <= g + GAIN_PLIES; m += 2) {
                if (!line.move(m).to.equals(square) || !line.isCapture(m)) {
                    continue;
                }
                int settled = line.balance(Math.min(m + 2, line.size()));
                if (settled - line.balance(g) >= directBest + MATERIAL) {
                    return Optional.of(tactic("in_between_move", kind, before.getPiece(move.from), move.to,
                            List.of(square.name), g));
                }
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * The piece on {@code square} stands attacked and losing material, is not pinned to its
     * king, and has no legal move that escapes, except one taking something at least as valuable.
     */
    static boolean isTrapped(ChessGame game, Square square, Side attacker) {
        Piece piece = game.getPiece(square);
        if (!badSpot(game, square, piece, attacker) || pinnedToKing(game, square)) {
            return false;
        }
        for (Move move : game.getLegalMoves(square)) {
            Piece taken = game.getPiece(move.to);
            if (taken != null && value(taken) >= value(piece)) {
                return false;
            }
            ChessGame next = game.clone();
            next.playMove(move);
            if (!badSpot(next, move.to, piece, attacker)) {
                return false;
            }
        }
        return true;
    }

    /** Attacked, and either undefended or attacked by something cheaper. */
    private static boolean badSpot(ChessGame game, Square square, Piece piece, Side attacker) {
        List<Square> attackers = Attacks.attackers(game, square, attacker);
        if (attackers.isEmpty()) {
            return false;
        }
        if (!Attacks.isAttacked(game, square, piece.side)) {
            return true;
        }
        return attackers.stream().anyMatch(from -> value(game.getPiece(from)) < value(piece));
    }

    /** Whether removing the piece on {@code square} would expose its own king to an enemy slider. */
    static boolean pinnedToKing(ChessGame game, Square square) {
        Piece piece = game.getPiece(square);
        for (int[] direction : concat(DIAGONAL, STRAIGHT)) {
            Square towardKing = firstPiece(game, square, direction);
            int[] opposite = {-direction[0], -direction[1]};
            Square towardSlider = firstPiece(game, square, opposite);
            if (towardKing == null || towardSlider == null) {
                continue;
            }
            Piece king = game.getPiece(towardKing);
            Piece slider = game.getPiece(towardSlider);
            if (king.pieceType == PieceType.KING && king.side == piece.side
                    && slider.side != piece.side && movesAlong(slider, direction)) {
                return true;
            }
        }
        return false;
    }

    /** A slider of {@code side}, an enemy piece worth at least a knight, and a more valuable piece or the king behind it. */
    record Pin(Square slider, Piece piece, Square pinned, Piece pinnedPiece, Square behind, Piece behindPiece) {

        boolean standsIn(ChessGame game) {
            return piece.equals(game.getPiece(slider))
                    && pinnedPiece.equals(game.getPiece(pinned))
                    && behindPiece.equals(game.getPiece(behind))
                    && pinned.equals(firstPieceFrom(game, slider, pinned))
                    && behind.equals(firstPieceFrom(game, pinned, behind));
        }
    }

    static List<Pin> pins(ChessGame game, Side side) {
        List<Pin> pins = new ArrayList<>();
        for (Square from : game.getOccupiedSquares()) {
            Piece slider = game.getPiece(from);
            if (slider.side != side || !isSlider(slider)) {
                continue;
            }
            for (int[] direction : directions(slider)) {
                Square pinned = firstPiece(game, from, direction);
                Square behind = pinned == null ? null : firstPiece(game, pinned, direction);
                if (behind == null) {
                    continue;
                }
                Piece front = game.getPiece(pinned);
                Piece back = game.getPiece(behind);
                if (front.side == side || back.side == side || front.pieceType == PieceType.KING
                        || value(front) < 3) {
                    continue;
                }
                if (back.pieceType == PieceType.KING || value(back) > value(front)) {
                    pins.add(new Pin(from, slider, pinned, front, behind, back));
                }
            }
        }
        return pins;
    }

    /** The first occupied square from {@code from} toward {@code to}, which must share a line with it. */
    private static Square firstPieceFrom(ChessGame game, Square from, Square to) {
        int[] direction = {Integer.signum(to.x - from.x), Integer.signum(to.y - from.y)};
        return firstPiece(game, from, direction);
    }

    private static Square firstPiece(ChessGame game, Square from, int[] direction) {
        int x = from.x + direction[0];
        int y = from.y + direction[1];
        while (x >= 0 && x < 8 && y >= 0 && y < 8) {
            Square square = Square.fromCoordinates(x, y);
            if (game.getPiece(square) != null) {
                return square;
            }
            x += direction[0];
            y += direction[1];
        }
        return null;
    }

    private static boolean movesAlong(Piece piece, int[] direction) {
        boolean diagonal = direction[0] != 0 && direction[1] != 0;
        return switch (piece.pieceType) {
            case QUEEN -> true;
            case BISHOP -> diagonal;
            case ROOK -> !diagonal;
            default -> false;
        };
    }

    private static int[][] directions(Piece slider) {
        return switch (slider.pieceType) {
            case BISHOP -> DIAGONAL;
            case ROOK -> STRAIGHT;
            default -> concat(DIAGONAL, STRAIGHT);
        };
    }

    private static int[][] concat(int[][] first, int[][] second) {
        int[][] all = new int[first.length + second.length][];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static boolean isSlider(Piece piece) {
        return piece != null && (piece.pieceType == PieceType.BISHOP
                || piece.pieceType == PieceType.ROOK || piece.pieceType == PieceType.QUEEN);
    }

    private static int value(Piece piece) {
        return TacticDetector.value(piece.pieceType);
    }

    private static Tactic tactic(String theme, String kind, Piece piece, Square square, List<String> targets, int index) {
        return new Tactic(theme, kind, piece.pieceType.name().toLowerCase(Locale.ROOT), square.name, targets, index);
    }
}
