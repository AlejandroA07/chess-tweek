package com.chesstweek.gamereview.internal.review;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chesstweek.gamereview.internal.persistence.AnalysisVersionReader.StoredLine;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionReader.StoredMove;

import static org.assertj.core.api.Assertions.assertThat;

class GameReviewServiceTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    void convertsAVariationToSanAndStopsAtTheFirstIllegalMove() {
        assertThat(GameReviewService.san(START, List.of("e2e4", "e7e5", "g1f3", "b8c6")))
                .containsExactly("e4", "e5", "Nf3", "Nc6");
        assertThat(GameReviewService.san(START, List.of("e2e4", "e2e4", "g1f3")))
                .containsExactly("e4");
        assertThat(GameReviewService.san(START, List.of("e7e5"))).isEmpty();
    }

    @Test
    void capsCentipawnLossAndCountsMatesAsTheCap() {
        assertThat(GameReviewService.centipawnLoss(move(false, cp(120), cp(30), "none"))).isEqualTo(150);
        assertThat(GameReviewService.centipawnLoss(move(false, cp(50), cp(-80), "none"))).isZero();
        assertThat(GameReviewService.centipawnLoss(move(false, mate(3), cp(-200), "none"))).isEqualTo(800);
        assertThat(GameReviewService.centipawnLoss(move(false, cp(2_500), mate(2), "none"))).isEqualTo(1_000);
        assertThat(GameReviewService.centipawnLoss(move(false, cp(400), null, "stalemate"))).isEqualTo(400);
        assertThat(GameReviewService.centipawnLoss(move(true, cp(400), cp(-100), "none"))).isZero();
    }

    private static StoredLine cp(int value) {
        return new StoredLine("e2e4", value, null, 0, 1_000, 0, List.of("e2e4"));
    }

    private static StoredLine mate(int moves) {
        return new StoredLine("e2e4", null, moves, 1_000, 0, 0, List.of("e2e4"));
    }

    private static StoredMove move(boolean playedBest, StoredLine before, StoredLine after, String terminal) {
        return new StoredMove(1, "e4", "e2e4", START, "opening", "quick", "none", "none", playedBest,
                "d2d4", 1_000, 1_000, 0, terminal, List.of(before), after == null ? List.of() : List.of(after));
    }
}
