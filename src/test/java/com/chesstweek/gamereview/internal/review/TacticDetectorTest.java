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
                new Tactic("fork", "missed", "queen", "a5", List.of("e1", "f5")));
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

        assertThat(tactics).containsExactly(new Tactic("fork", "allowed", "knight", "f7", List.of("h8", "d8")));
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

        assertThat(winning).containsExactly(new Tactic("fork", "allowed", "knight", "c2", List.of("e1", "a1")));
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
}
