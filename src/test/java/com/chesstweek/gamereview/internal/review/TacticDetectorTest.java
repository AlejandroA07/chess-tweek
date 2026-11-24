package com.chesstweek.gamereview.internal.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chesstweek.gamereview.GameReviews.Tactic;

class TacticDetectorTest {

    @Test
    void findsTheMissedQueenForkInTheOwnersCaroKann() {
        // 6...e6 was a mistake; the best line 6...Bf5 7.Bxf5 Qa5+ 8.Bd2 Qxf5 wins the bishop back
        // with a queen check that also attacks f5.
        List<Tactic> tactics = TacticDetector.detect(
                "rnbqkb1r/pp2pppp/2p5/8/3PB3/8/PPP2PPP/R1BQK1NR b KQkq - 0 6",
                "e7e6",
                "mistake",
                "none",
                List.of("c8f5", "e4f5", "d8a5", "c1d2", "a5f5", "g1f3", "e7e6"),
                List.of("g1f3", "b8d7", "d1e2", "d7f6"));

        assertThat(tactics).containsExactly(
                new Tactic("fork", "missed", "queen", "a5", List.of("e1", "f5"), 2));
    }

    @Test
    void findsAnAllowedForkAfterAForcingSequenceInTheOwnersGame() {
        // 8...Nd7 allowed 9.dxe6 fxe6 10.Ng5 Nf6 11.Bg6+ hxg6 12.Qxd8+ Kxd8 13.Nf7+, forking the
        // king and the rook on h8, which falls after 14.Bf4 e5 15.Bxe5 Kc6 16.Nxh8 (the stored line).
        List<Tactic> tactics = TacticDetector.detect(
                "rnbqkb1r/pp3ppp/4p3/2pP4/4B3/5N2/PPP2PPP/R1BQK2R b KQkq - 0 8",
                "b8d7",
                "mistake",
                "none",
                List.of("f7f5", "e4d3", "d8d5", "d1e2"),
                List.of("d5e6", "f7e6", "f3g5", "d7f6", "e4g6", "h7g6", "d1d8", "e8d8", "g5f7", "d8c7",
                        "c1f4", "e6e5", "f4e5", "c7c6", "f7h8", "c8f5", "e1c1"));

        assertThat(tactics).containsExactly(new Tactic("fork", "allowed", "knight", "f7", List.of("h8", "d8"), 8));
    }

    @Test
    void labelsAnUndefendedPieceTakenByTheReply() {
        List<Tactic> tactics = TacticDetector.detect(
                "4k3/8/8/3q4/8/8/3N4/4K3 w - - 0 1", "d2f3", "blunder", "none",
                List.of("e1d1"), List.of("d5f3"));

        assertThat(tactics).containsExactly(new Tactic("hanging_piece", "allowed", "knight", "f3", List.of()));
    }

    @Test
    void doesNotCallADefendedPieceHanging() {
        List<Tactic> tactics = TacticDetector.detect(
                "4k3/8/8/3q4/8/8/3N2P1/4K3 w - - 0 1", "d2f3", "blunder", "none",
                List.of("e1d1"), List.of("d5f3", "g2f3"));

        assertThat(tactics).isEmpty();
    }

    @Test
    void labelsAMissedHangingPiece() {
        List<Tactic> tactics = TacticDetector.detect(
                "4k3/8/8/3r4/8/8/8/3QK3 w - - 0 1", "e1f2", "mistake", "none",
                List.of("d1d5"), List.of("d5d1"));

        assertThat(tactics).contains(new Tactic("hanging_piece", "missed", "rook", "d5", List.of()));
    }

    @Test
    void labelsAnAllowedKnightForkOnlyWhenTheLineWinsTheTarget() {
        String fen = "4k3/8/8/8/3n4/8/7P/R3K3 w - - 0 1";

        List<Tactic> winning = TacticDetector.detect(
                fen, "h2h3", "blunder", "none", List.of("a1a8"), List.of("d4c2", "e1d2", "c2a1"));
        List<Tactic> unproven = TacticDetector.detect(
                fen, "h2h3", "blunder", "none", List.of("a1a8"), List.of("d4c2", "e1d2", "e8e7"));

        assertThat(winning).containsExactly(new Tactic("fork", "allowed", "knight", "c2", List.of("e1", "a1"), 0));
        assertThat(unproven).isEmpty();
    }

    @Test
    void labelsAMissedBackRankMate() {
        List<Tactic> tactics = TacticDetector.detect(
                "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1", "g1f1", "blunder", "missed_mate",
                List.of("a1a8"), List.of("g8f8"));

        assertThat(tactics).containsExactly(
                new Tactic("missed_mate", "missed", null, null, List.of()),
                new Tactic("back_rank", "missed", "rook", "a8", List.of()));
    }

    @Test
    void labelsAnAllowedBackRankMate() {
        List<Tactic> tactics = TacticDetector.detect(
                "r5k1/5ppp/8/8/8/8/5PPP/1R4K1 b - - 0 1", "a8a2", "blunder", "allowed_mate",
                List.of("g8f8"), List.of("b1b8"));

        assertThat(tactics).containsExactly(
                new Tactic("allowed_mate", "allowed", null, null, List.of()),
                new Tactic("back_rank", "allowed", "rook", "b8", List.of()));
    }

    @Test
    void doesNotCallAMateOnAnotherRankBackRank() {
        // Scholar's mate: Qxf7# is not on Black's back rank.
        List<Tactic> tactics = TacticDetector.detect(
                "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3", "g8f6", "blunder",
                "allowed_mate", List.of("d8e7"), List.of("h5f7"));

        assertThat(tactics).containsExactly(new Tactic("allowed_mate", "allowed", null, null, List.of()));
    }

    @Test
    void fallsBackToGeneralTacticsWhenTheLineWinsMaterialWithoutANarrowerPattern() {
        // The rook on f3 is defended, but Nxf3 gxf3 still wins the exchange.
        List<Tactic> tactics = TacticDetector.detect(
                "4k3/8/8/8/3n4/5R2/6P1/4K3 w - - 0 1", "e1f1", "mistake", "none",
                List.of("f3f8"), List.of("d4f3", "g2f3"));

        assertThat(tactics).containsExactly(new Tactic("general_tactics", "allowed", null, null, List.of()));
    }

    @Test
    void givesNoLabelToGoodMovesInaccuraciesWithoutPatternsOrUnreadableData() {
        String fen = "4k3/8/8/8/3n4/5R2/6P1/4K3 w - - 0 1";

        assertThat(TacticDetector.detect(fen, "e1f1", "none", "none", List.of(), List.of())).isEmpty();
        assertThat(TacticDetector.detect(fen, "e1f1", "inaccuracy", "none", List.of("f3f8"), List.of("d4f3", "g2f3")))
                .isEmpty();
        assertThat(TacticDetector.detect("not a fen", "e2e4", "blunder", "none", List.of(), List.of())).isEmpty();
        assertThat(TacticDetector.detect(fen, "a1a2", "blunder", "none", List.of(), List.of())).isEmpty();
    }

    @Test
    void attacksStopAtTheFirstPieceInALine() {
        var game = new io.github.wolfraam.chessgame.ChessGame("4k3/8/8/8/8/8/R2p4/4K3 w - - 0 1");
        var attacked = TacticDetector.Attacks.attackedBy(game, io.github.wolfraam.chessgame.board.Square.A2)
                .stream().map(square -> square.name).toList();

        assertThat(attacked).contains("b2", "c2", "d2", "a1", "a8").doesNotContain("e2");
    }

    // Stage 8B named motifs: each has a proving line and a near miss that must not be labelled.

    @Test
    void labelsAMissedDiscoveredCheckThatWinsTheQueen() {
        // 1.Bxa8+ uncovers the rook's check on e8; the bishop takes the defended queen.
        String fen = "qr2k3/8/8/8/4B3/8/8/4R1K1 w - - 0 1";

        assertThat(missed(fen, "e4a8", "e8d7")).containsExactly(
                new Tactic("discovered_check", "missed", "rook", "e1", List.of("e8"), 0));
        // Near miss: the bishop uncovers the same check but nothing is won.
        assertThat(missed(fen, "e4h7", "e8f8")).isEmpty();
    }

    @Test
    void labelsAMissedDiscoveredAttackOnTheQueen() {
        // 1.Nf5 uncovers the rook on the queen and attacks the rook on e7; 3.Nxe7+ wins it.
        String fen = "6k1/3qr3/8/8/3N4/8/8/3R2K1 w - - 0 1";

        assertThat(missed(fen, "d4f5", "d7a4", "f5e7", "g8h8")).containsExactly(
                new Tactic("discovered_attack", "missed", "rook", "d1", List.of("d7"), 0));
        // Near miss: the queen is uncovered too, but the line never takes anything.
        assertThat(missed(fen, "d4b3", "d7a4", "b3c5", "a4c6")).isEmpty();
    }

    @Test
    void labelsAMissedPinOnlyWhenThePinnedPieceIsWon() {
        // 1.Re1 pins the knight to the king; 3.d6 attacks it and 5.dxe7 wins it (the rook guards e7).
        String fen = "4k3/p3n3/8/3P4/8/8/8/R5K1 w - - 0 1";

        assertThat(missed(fen, "a1e1", "a7a6", "d5d6", "a6a5", "d6e7", "e8d7")).containsExactly(
                new Tactic("pin", "missed", "rook", "e1", List.of("e7", "e8"), 0));
        // Near miss: the pinned knight is taken by the rook, which the king recaptures.
        assertThat(missed(fen, "a1e1", "a7a6", "e1e7", "e8e7")).isEmpty();
    }

    @Test
    void labelsAMissedPinThatWinsAPieceThePinnedPieceGuards() {
        // 1.Bb5 pins the knight to the king; 3.Rxe5+ takes the bishop the knight was guarding,
        // and the pinned knight cannot take back.
        assertThat(missed("4k3/p7/2n5/4b3/8/8/8/4RBK1 w - - 0 1", "f1b5", "a7a6", "e1e5", "e8f8"))
                .containsExactly(new Tactic("pin", "missed", "bishop", "b5", List.of("c6", "e8", "e5"), 0));
        // Near miss: with the king on g8 nothing is pinned. The line stops before Nxe5, so only the
        // pin rule keeps the label away.
        assertThat(missed("6k1/p7/2n5/4b3/8/8/8/4RBK1 w - - 0 1", "f1b5", "a7a6", "e1e5")).isEmpty();
    }

    @Test
    void labelsAMissedSkewerOfTheKingAndRook() {
        assertThat(missed("4k2r/8/8/8/8/8/8/R5K1 w - - 0 1", "a1a8", "e8e7", "a8h8")).containsExactly(
                new Tactic("skewer", "missed", "rook", "a8", List.of("e8", "h8"), 0));
        // Near miss: the king steps to g7, where it recaptures on h8.
        assertThat(missed("5k1r/8/8/8/8/8/8/R5K1 w - - 0 1", "a1a8", "f8g7", "a8h8", "g7h8")).isEmpty();
    }

    @Test
    void labelsAnAllowedSkewerFromTheReplyLine() {
        // 1...h6 walks into 2.Ra8+ Ke7 3.Rxh8.
        assertThat(TacticDetector.detect("4k2r/7p/8/8/8/8/8/R5K1 b - - 0 1", "h7h6", "blunder", "none",
                List.of("e8g8"), List.of("a1a8", "e8e7", "a8h8")))
                .containsExactly(new Tactic("skewer", "allowed", "rook", "a8", List.of("e8", "h8"), 0));
    }

    @Test
    void anAllowedPatternDoesNotCountTheRecaptureOfThePlayedCaptureAsAGain() {
        // After 1...Rxe1+ 2.Rxe1 the knight is pinned, and 3.Rxe7+ Kxe7 takes it. Counted from
        // after 1...Rxe1+ that looks like a gain, but from before it White has lost material.
        assertThat(TacticDetector.detect("4k3/4n2p/8/8/8/8/4r3/R3R1K1 b - - 0 1", "e2e1", "blunder", "none",
                List.of("e8d7"), List.of("a1e1", "h7h6", "e1e7", "e8e7")))
                .noneMatch(tactic -> tactic.theme().equals("pin"));
    }

    @Test
    void labelsAMissedRemovalOfTheOnlyDefender() {
        // 1.Bxc6 removes the only defender of the bishop on d4, which 2.Rxd4 then wins.
        assertThat(missed("6k1/1p6/2n5/1B6/3b4/8/8/3R3K w - - 0 1", "b5c6", "b7c6", "d1d4"))
                .containsExactly(new Tactic("removing_defender", "missed", "knight", "c6", List.of("d4"), 0));
        // Near miss: the pawn on e5 still defends d4. The line stops before exd4, so only the
        // only-defender rule keeps the label away.
        assertThat(missed("6k1/1p6/2n5/1B2p3/3b4/8/8/3R3K w - - 0 1", "b5c6", "b7c6", "d1d4")).isEmpty();
    }

    @Test
    void labelsARemovalOfTheDefenderWhoseGainComesTwoMovesLater() {
        String fen = "6k1/1p6/2n5/1B6/3b4/8/8/3R3K w - - 0 1";

        assertThat(missed(fen, "b5c6", "b7c6", "h1g2", "g8f7", "d1d4")).containsExactly(
                new Tactic("removing_defender", "missed", "knight", "c6", List.of("d4"), 0));
        // Near miss: the bishop is taken only on the third move after the defender falls.
        assertThat(missed(fen, "b5c6", "b7c6", "h1g2", "g8f7", "g2f3", "f7e7", "d1d4")).isEmpty();
    }

    @Test
    void labelsADeflectionWhoseGainComesTwoMovesLater() {
        String fen = "8/6k1/5n2/8/8/8/1B6/3R2K1 w - - 0 1";

        assertThat(missed(fen, "d1d7", "g7g8", "d7d8", "g8h7", "b2f6")).containsExactly(
                new Tactic("deflection", "missed", "king", "g7", List.of("f6"), 0));
        // Near miss: the king comes back to g7 and defends the knight again before it is taken.
        assertThat(missed(fen, "d1d7", "g7g8", "d7d8", "g8g7", "b2f6")).isEmpty();
    }

    @Test
    void labelsAMissedTrappedPiece() {
        // 1.Ra1 attacks the bishop on a2: b1 is covered by the rook and b3 by the c2 pawn.
        assertThat(missed("6k1/7p/8/8/8/1P6/b1P5/2R3K1 w - - 0 1", "c1a1", "h7h6", "a1a2"))
                .containsExactly(new Tactic("trapped_piece", "missed", "bishop", "a2", List.of(), 0));
        // Near miss: without the c2 pawn, Bxb3 escapes, so the bishop was not trapped.
        assertThat(missed("6k1/7p/8/8/8/1P6/b7/2R3K1 w - - 0 1", "c1a1", "h7h6", "a1a2")).isEmpty();
    }

    @Test
    void labelsAMissedDeflectionOfTheOnlyDefender() {
        // 1.Rd7+ drives the king from g7, the knight's only defender, and 2.Bxf6 wins it.
        assertThat(missed("8/6k1/5n2/8/8/8/1B6/3R2K1 w - - 0 1", "d1d7", "g7g8", "b2f6"))
                .containsExactly(new Tactic("deflection", "missed", "king", "g7", List.of("f6"), 0));
        // Near miss: the knight on h5 still defends f6. The line stops before Nxf6, so only the
        // left-undefended rule keeps the label away.
        assertThat(missed("8/6k1/5n2/7n/8/8/1B6/3R2K1 w - - 0 1", "d1d7", "g7g8", "b2f6")).isEmpty();
        // Near miss: a capture recaptured by the defender deflects only when it took at least as
        // much as the defender is worth. Bxc6 takes a pawn; Rxc6 leaves the queen on a4, and Rxa4
        // wins it, but a pawn never forced the rook away.
        assertThat(LineMotifs.deflection(ReplayedLine.of(
                new io.github.wolfraam.chessgame.ChessGame("7k/8/r1p5/8/q7/8/6B1/R5K1 w - - 0 1"),
                List.of("g2c6", "a6c6", "a1a4"), LineMotifs.MAX_PLIES, null), "missed")).isEmpty();
    }

    @Test
    void labelsAnAllowedInBetweenMoveAfterThePlayedCapture() {
        // 1...Bxf3 allows 2.Qxd8+ before gxf3 recaptures the bishop.
        List<Tactic> tactics = TacticDetector.detect(
                IN_BETWEEN, "g4f3", "blunder", "none", List.of("g8h7"), List.of("d1d8", "g8h7", "g2f3"));

        assertThat(tactics).containsExactly(
                new Tactic("hanging_piece", "allowed", "queen", "d8", List.of()),
                new Tactic("in_between_move", "allowed", "queen", "d8", List.of("f3"), 0));
    }

    @Test
    void doesNotCallAnInBetweenMoveThatOnlyTradesBackToTheSameMaterial() {
        // The rook on a8 defends the queen, so Qxd8+ Rxd8 gxf3 ends where gxf3 would have.
        List<Tactic> tactics = TacticDetector.detect(
                "r2q2k1/8/8/8/6b1/5N2/6P1/3Q2K1 b - - 0 1", "g4f3", "blunder", "none",
                List.of("g8h7"), List.of("d1d8", "a8d8", "g2f3"));

        assertThat(tactics).isEmpty();
    }

    @Test
    void labelsAMissedInBetweenMoveFromThePreviousCapture() {
        // Black's previous move 1...Bxf3 took the knight; the owner recaptured at once, but 2.Qxd8+
        // first was better. Without the previous move there is no capture to answer.
        String fen = after(IN_BETWEEN, "g4f3");
        List<String> best = List.of("d1d8", "g8h7", "g2f3");
        var previous = new TacticDetector.Previous(IN_BETWEEN, "g4f3");

        assertThat(TacticDetector.detect(fen, "g2f3", "mistake", "none", best, List.of(), previous))
                .contains(new Tactic("in_between_move", "missed", "queen", "d8", List.of("f3"), 0));
        assertThat(TacticDetector.detect(fen, "g2f3", "mistake", "none", best, List.of(), null))
                .noneMatch(tactic -> tactic.theme().equals("in_between_move"));
        // A previous move that does not lead to this position is ignored.
        var wrong = new TacticDetector.Previous(IN_BETWEEN, "d8d7");
        assertThat(TacticDetector.detect(fen, "g2f3", "mistake", "none", best, List.of(), wrong))
                .noneMatch(tactic -> tactic.theme().equals("in_between_move"));
    }

    @Test
    void aPieceIsNotTrappedWhileItIsPinnedToItsKing() {
        var game = new io.github.wolfraam.chessgame.ChessGame("4k3/4n3/8/8/8/8/8/4R1K1 b - - 0 1");

        assertThat(LineMotifs.pinnedToKing(game, io.github.wolfraam.chessgame.board.Square.E7)).isTrue();
        assertThat(LineMotifs.isTrapped(
                game, io.github.wolfraam.chessgame.board.Square.E7, io.github.wolfraam.chessgame.board.Side.WHITE))
                .isFalse();
    }

    /** 3...Bxf3 is available: White's queen on d1 faces the undefended queen on d8. */
    private static final String IN_BETWEEN = "3q2k1/8/8/8/6b1/5N2/6P1/3Q2K1 b - - 0 1";

    /**
     * Labels when the owner played a legal king move instead of {@code best}; only the best line
     * is read, so the played move itself does not matter.
     */
    private static List<Tactic> missed(String fen, String... best) {
        var game = new io.github.wolfraam.chessgame.ChessGame(fen);
        var king = game.getSquares(io.github.wolfraam.chessgame.board.Piece.fromPieceTypeAndSide(
                io.github.wolfraam.chessgame.board.PieceType.KING, game.getSideToMove())).iterator().next();
        var quiet = game.getLegalMoves(king).iterator().next();
        String played = quiet.from.name + quiet.to.name;
        return TacticDetector.detect(fen, played, "mistake", "none", List.of(best), List.of());
    }

    private static String after(String fen, String uci) {
        var game = new io.github.wolfraam.chessgame.ChessGame(fen);
        game.playMove(io.github.wolfraam.chessgame.notation.NotationType.UCI, uci);
        return game.getFen();
    }
}
