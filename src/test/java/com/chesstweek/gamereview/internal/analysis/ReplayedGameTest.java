package com.chesstweek.gamereview.internal.analysis;

import org.junit.jupiter.api.Test;

import com.chesstweek.gamereview.internal.analysis.ReplayedGame.Color;
import com.chesstweek.gamereview.internal.analysis.ReplayedGame.Terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayedGameTest {

    @Test
    void replaysSanIntoEveryPositionWithUciMovesAndHistory() {
        ReplayedGame game = ReplayedGame.fromSan("e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#");

        assertThat(game.plyCount()).isEqualTo(7);
        assertThat(game.positions()).hasSize(8);
        assertThat(game.moves()).extracting(ReplayedGame.PlayedMove::uci)
                .containsExactly("e2e4", "e7e5", "d1h5", "b8c6", "f1c4", "g8f6", "h5f7");
        assertThat(game.moves().get(1).mover()).isEqualTo(Color.BLACK);
        assertThat(game.before(1).fen())
                .isEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        assertThat(game.before(1).sideToMove()).isEqualTo(Color.WHITE);
        assertThat(game.before(3).movesFromStart()).containsExactly("e2e4", "e7e5");
        assertThat(game.after(7).terminal()).isEqualTo(Terminal.CHECKMATE);
        assertThat(game.after(6).terminal()).isEqualTo(Terminal.NONE);
    }

    @Test
    void countsQueensAndStandardNonPawnMaterial() {
        ReplayedGame game = ReplayedGame.fromSan("e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#");

        assertThat(game.before(1).queens()).isEqualTo(2);
        assertThat(game.before(1).nonPawnMaterial()).isEqualTo(62);
    }

    @Test
    void recognizesStalemate() {
        ReplayedGame game = ReplayedGame.fromSan(
                "e3 a5 Qh5 Ra6 Qxa5 h5 h4 Rah6 Qxc7 f6 Qxd7+ Kf7 Qxb7 Qd3 Qxb8 Qh7 Qxc8 Kg6 Qe6");

        assertThat(game.after(game.plyCount()).terminal()).isEqualTo(Terminal.STALEMATE);
    }

    @Test
    void acceptsAGameWithNoMoves() {
        ReplayedGame game = ReplayedGame.fromSan("  ");

        assertThat(game.plyCount()).isZero();
        assertThat(game.positions()).hasSize(1);
    }

    @Test
    void rejectsIllegalOrMalformedMoves() {
        assertThatThrownBy(() -> ReplayedGame.fromSan("e4 e4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplayedGame.fromSan("e4 Zz9"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsGamesLongerThanTheReplayBound() {
        String moves = "Nf3 Nf6 Ng1 Ng8 ".repeat(ReplayedGame.MAX_PLIES / 4 + 1);

        assertThatThrownBy(() -> ReplayedGame.fromSan(moves))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
