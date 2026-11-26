package com.chesstweek.shared.chess;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.notation.NotationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplorerPositionTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    void keysTheStartPositionWithoutMoveCounters() {
        ExplorerPosition position = ExplorerPosition.parse(START).orElseThrow();

        assertThat(position.key()).isEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -");
        assertThat(position.fen()).isEqualTo(START);
        assertThat(position.whiteToMove()).isTrue();
    }

    @Test
    void ignoresMoveCountersSoTheSamePositionMeetsAtOneKey() {
        var early = ExplorerPosition.parse(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").orElseThrow();
        var later = ExplorerPosition.parse(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 8 5").orElseThrow();

        assertThat(later).isEqualTo(early);
    }

    @Test
    void dropsAnEnPassantSquareWhenNoCaptureThereIsLegal() {
        var game = new ChessGame();
        game.playMoves(NotationType.SAN, "e4");

        ExplorerPosition position = ExplorerPosition.of(game);

        assertThat(position.key()).endsWith(" b KQkq -");
        assertThat(ExplorerPosition.parse(
                        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
                .orElseThrow()
                .key())
                .isEqualTo(position.key());
    }

    @Test
    void keepsAnEnPassantSquareWhenTheCaptureIsLegal() {
        var game = new ChessGame();
        game.playMoves(NotationType.SAN, "e4 a6 e5 d5");

        ExplorerPosition position = ExplorerPosition.of(game);

        assertThat(position.key()).endsWith(" w KQkq d6");
    }

    @Test
    void transposedMoveOrdersReachTheSameKey() {
        var first = new ChessGame();
        first.playMoves(NotationType.SAN, "d4 Nf6 c4 e6");
        var second = new ChessGame();
        second.playMoves(NotationType.SAN, "c4 e6 d4 Nf6");

        assertThat(ExplorerPosition.of(first)).isEqualTo(ExplorerPosition.of(second));
    }

    @Test
    void rejectsTextThatIsNotALegalStandardPosition() {
        assertThat(ExplorerPosition.parse(null)).isEmpty();
        assertThat(ExplorerPosition.parse("")).isEmpty();
        assertThat(ExplorerPosition.parse("not a fen")).isEmpty();
        assertThat(ExplorerPosition.parse(START + " ")).isEmpty();
        assertThat(ExplorerPosition.parse(START.replace(" 0 1", ""))).isEmpty();
        assertThat(ExplorerPosition.parse("x".repeat(101))).isEmpty();
        // Seven files on the first rank.
        assertThat(ExplorerPosition.parse(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN w KQkq - 0 1")).isEmpty();
        // Two white kings.
        assertThat(ExplorerPosition.parse(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBKKBNR w kq - 0 1")).isEmpty();
        // No black king.
        assertThat(ExplorerPosition.parse("8/8/8/8/8/8/8/4K3 w - - 0 1")).isEmpty();
        // A pawn on the last rank.
        assertThat(ExplorerPosition.parse("P3k3/8/8/8/8/8/8/4K3 w - - 0 1")).isEmpty();
        // Black is to move but White is in check: the side that just moved left its king attacked.
        assertThat(ExplorerPosition.parse("4k3/8/8/8/8/8/8/4K2r b - - 0 1")).isEmpty();
    }

    @Test
    void acceptsAPositionWhereTheSideToMoveIsInCheck() {
        assertThat(ExplorerPosition.parse("4k3/8/8/8/8/8/8/4K2r w - - 0 1")).isPresent();
    }
}
