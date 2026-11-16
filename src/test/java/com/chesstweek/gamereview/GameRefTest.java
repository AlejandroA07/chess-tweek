package com.chesstweek.gamereview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameRefTest {

    @Test
    void parsesEachSourceWithItsOwnIdFormat() {
        assertThat(GameRef.parse("lichess", "ye7Uy9js"))
                .contains(new GameRef(GameRef.Source.LICHESS, "ye7Uy9js"));
        assertThat(GameRef.parse("chesscom", "128564733217"))
                .contains(new GameRef(GameRef.Source.CHESSCOM, "128564733217"));
        assertThat(GameRef.parse("chesscom", "7")).isPresent();
    }

    @Test
    void rejectsUnknownSourcesAndIdsOutsideTheSourceFormat() {
        assertThat(GameRef.parse("chess.com", "128564733217")).isEmpty();
        assertThat(GameRef.parse("LICHESS", "ye7Uy9js")).isEmpty();
        assertThat(GameRef.parse(null, "ye7Uy9js")).isEmpty();
        assertThat(GameRef.parse("lichess", null)).isEmpty();
        assertThat(GameRef.parse("lichess", "short")).isEmpty();
        assertThat(GameRef.parse("lichess", "abcd1234' OR '1'='1")).isEmpty();
        assertThat(GameRef.parse("lichess", "a".repeat(33))).isEmpty();
        assertThat(GameRef.parse("chesscom", "ye7Uy9js")).isEmpty();
        assertThat(GameRef.parse("chesscom", "")).isEmpty();
        assertThat(GameRef.parse("chesscom", "1".repeat(21))).isEmpty();
        assertThat(GameRef.parse("chesscom", "-12345")).isEmpty();
    }

    @Test
    void refusesToBuildAnInvalidReference() {
        assertThatThrownBy(() -> GameRef.lichess("bad id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameRef(GameRef.Source.CHESSCOM, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameRef(null, "ye7Uy9js"))
                .isInstanceOf(NullPointerException.class);
    }
}
