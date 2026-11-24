package com.chesstweek.gamereview.internal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import java.util.Set;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.board.Side;
import io.github.wolfraam.chessgame.notation.NotationType;
import org.junit.jupiter.api.Test;

import com.chesstweek.gamereview.GameReviews.Observation;
import com.chesstweek.gamereview.GameReviews.Positional;

class PositionalObservationsTest {

    @Test
    void reportsMaterialOnlyWhenTheBalanceDiffers() {
        assertThat(compare("4k3/8/8/8/8/8/8/R3K3 w - - 0 1", "4k3/8/8/8/8/8/8/4K3 w - - 0 1"))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .contains(tuple("material", "mover", 5, 0));
        assertThat(features(compare("4k3/8/8/8/8/8/8/R3K3 w - - 0 1", "4k3/8/8/8/8/8/8/1R2K3 w - - 0 1")))
                .doesNotContain("material");
    }

    @Test
    void reportsALostBishopPairButNotBishopsOnOtherSquares() {
        assertThat(compare("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1", "4k3/8/8/8/8/8/8/2B1KN2 w - - 0 1"))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("bishop_pair", "mover", 1, 0));
        assertThat(compare("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1", "4k3/8/8/8/8/3B4/8/4KB2 w - - 0 1"))
                .extracting(Observation::feature)
                .doesNotContain("bishop_pair");
    }

    @Test
    void countsPawnsInFrontOfTheKingWithinTwoRanks() {
        assertThat(compare("4k3/8/8/8/8/8/5PPP/6K1 w - - 0 1", "4k3/8/8/8/7P/8/5PP1/6K1 w - - 0 1"))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("king_shelter", "mover", 3, 2));
        assertThat(compare("4k3/8/8/8/8/8/5PPP/6K1 w - - 0 1", "4k3/8/8/8/8/7P/5PP1/6K1 w - - 0 1"))
                .isEmpty();
    }

    @Test
    void ignoresPawnCoverForAKingStillInTheCentre() {
        assertThat(compare("4k3/8/8/8/8/8/3PPP2/4K3 w - - 0 1", "4k3/8/8/8/4P3/8/3P1P2/4K3 w - - 0 1"))
                .isEmpty();
    }

    @Test
    void countsFilesBesideTheKingWithoutItsOwnPawns() {
        assertThat(compare("4k3/8/8/8/8/8/P4PPP/6K1 w - - 0 1", "4k3/8/8/8/8/8/PP3PP1/6K1 w - - 0 1"))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .contains(tuple("open_files_near_king", "mover", 0, 1));
        assertThat(features(compare("4k3/8/8/8/8/8/5PPP/6K1 w - - 0 1", "4k3/8/8/8/7P/8/5PP1/6K1 w - - 0 1")))
                .doesNotContain("open_files_near_king");
    }

    @Test
    void reportsCastlingOnlyWhenOneLineCanNoLongerCastle() {
        String rights = "r3k3/8/8/8/8/8/8/4K2R w Kq - 0 1";
        String lost = "r3k3/8/8/8/8/8/8/4K2R w q - 0 1";
        assertThat(PositionalObservations.compare(Side.WHITE, game(rights), game(lost), Set.of(), Set.of(), false))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("castling", "mover", 1, 0));
        assertThat(PositionalObservations.compare(Side.WHITE, game(rights), game(lost), Set.of(), Set.of(Side.WHITE), false))
                .isEmpty();
    }

    @Test
    void namesTheNewPassedPawnsForBothSides() {
        assertThat(compare("4k3/8/8/3p4/8/8/4P3/4K3 w - - 0 1", "4k3/8/8/3p4/8/8/P7/4K3 w - - 0 1"))
                .filteredOn(observation -> observation.feature().equals("passed_pawns"))
                .extracting(Observation::side, Observation::best, Observation::played, Observation::squares)
                .containsExactly(tuple("mover", 0, 1, List.of("a2")), tuple("opponent", 0, 1, List.of("d5")));
        assertThat(compare("4k3/8/8/8/3P4/8/8/4K3 w - - 0 1", "4k3/8/8/3P4/8/8/8/4K3 w - - 0 1")).isEmpty();
    }

    @Test
    void countsIsolatedPawns() {
        assertThat(compare("4k3/8/8/8/8/8/2PP4/4K3 w - - 0 1", "4k3/8/8/8/8/8/2P1P3/4K3 w - - 0 1"))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("isolated_pawns", "mover", 0, 2));
        assertThat(compare("4k3/8/8/8/8/8/2PP4/4K3 w - - 0 1", "4k3/8/8/8/8/2PP4/8/4K3 w - - 0 1")).isEmpty();
    }

    @Test
    void countsDoubledPawns() {
        assertThat(compare("4k3/8/8/8/8/8/2PPP3/7K w - - 0 1", "4k3/8/8/8/8/2P5/2PP4/7K w - - 0 1"))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("doubled_pawns", "mover", 0, 1));
        assertThat(compare("4k3/8/8/8/8/8/2PPP3/7K w - - 0 1", "4k3/8/8/8/8/2PPP3/8/7K w - - 0 1")).isEmpty();
    }

    @Test
    void countsUndevelopedMinorPiecesOnlyInTheOpening() {
        String developed = "4k3/8/8/8/8/2N5/8/4K3 w - - 0 1";
        String home = "4k3/8/8/8/8/8/8/1N2K3 w - - 0 1";
        assertThat(PositionalObservations.compare(Side.WHITE, game(developed), game(home), Set.of(), Set.of(), true))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("development", "mover", 0, 1));
        assertThat(compare(developed, home)).isEmpty();
    }

    @Test
    void reportsMobilityOnlyForALargeDifference() {
        assertThat(compare("4k3/8/8/8/3Q4/8/PP6/4K3 w - - 0 1", "4k3/8/8/8/8/8/PP6/Q3K3 w - - 0 1"))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("mobility", "mover", 25, 3));
        assertThat(compare("4k3/8/8/8/3Q4/8/PP6/4K3 w - - 0 1", "4k3/8/8/8/4Q3/8/PP6/4K3 w - - 0 1")).isEmpty();
    }

    @Test
    void reportsFromTheMoversViewWhenBlackMoved() {
        assertThat(PositionalObservations.compare(Side.BLACK,
                        game("4k3/8/8/8/8/8/8/R3K3 b - - 0 1"), game("4k3/8/8/8/8/8/8/4K3 b - - 0 1"),
                        Set.of(), Set.of(), false))
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("material", "mover", -5, 0), tuple("mobility", "opponent", 10, 0));
    }

    @Test
    void comparesAfterAPendingRecaptureNotInTheMiddleOfIt() {
        String start = "4k2r/p7/8/8/8/8/P7/4K2R w - - 0 1";
        // The best line's fourth ply is 2...Rxh7, a capture, so the matched depth moves to ply 5.
        Positional positional = observe(start, "a2a4",
                List.of("a2a3", "a7a6", "h1h7", "h8h7", "e1d1", "e8d8"),
                List.of("a7a6", "h1g1", "h8g8", "g1f1", "e8d8"));

        assertThat(positional.plies()).isEqualTo(5);
        assertThat(positional.observations())
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .contains(tuple("material", "mover", -5, 0));
    }

    @Test
    void doesNotCompareJustBeforeACaptureTheLineStillPlays() {
        String start = "4k2r/p7/8/8/8/8/P7/4K2R w - - 0 1";
        // Ply 4, 2...Rh2, is quiet itself, but 3.Rxh2 follows, so the matched depth moves to ply 6.
        Positional positional = observe(start, "a2a4",
                List.of("e1f1", "e8d8", "a2a3", "h8h2", "h1h2", "d8c7"),
                List.of("e8d8", "e1f1", "h8h7", "f1g1", "d8c7"));

        assertThat(positional.plies()).isEqualTo(6);
        assertThat(positional.observations())
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .contains(tuple("material", "mover", 5, 0));
    }

    @Test
    void givesNothingWhenTheLinesNeverSettle() {
        String start = "4k2r/p7/8/8/8/8/P7/4K2R w - - 0 1";

        assertThat(observe(start, "a2a4",
                List.of("a2a3", "a7a6", "h1h7", "h8h7"),
                List.of("a7a6", "h1g1", "h8g8", "g1f1"))).isNull();
        assertThat(observe(start, "a2a4", List.of("a2a3", "a7a6"), List.of("a7a6"))).isNull();
    }

    @Test
    void countsCastlingByThePlayedMove() {
        Positional positional = observe("4k3/p7/8/8/8/8/P7/4K2R w K - 0 1", "e1g1",
                List.of("e1f1", "a7a6", "a2a3", "e8d8"),
                List.of("a7a6", "a2a3", "e8d8"));

        assertThat(positional.plies()).isEqualTo(4);
        assertThat(positional.observations())
                .extracting(Observation::feature, Observation::side, Observation::best, Observation::played)
                .containsExactly(tuple("castling", "mover", 0, 2));
    }

    private static Positional observe(String fen, String played, List<String> best, List<String> reply) {
        ChessGame before = game(fen);
        return PositionalObservations.observe(before, before.getMove(NotationType.UCI, played), best, reply, false);
    }

    private static List<Observation> compare(String best, String played) {
        return PositionalObservations.compare(Side.WHITE, game(best), game(played), Set.of(), Set.of(), false);
    }

    private static List<String> features(List<Observation> observations) {
        return observations.stream().map(Observation::feature).toList();
    }

    private static ChessGame game(String fen) {
        return new ChessGame(fen);
    }
}
