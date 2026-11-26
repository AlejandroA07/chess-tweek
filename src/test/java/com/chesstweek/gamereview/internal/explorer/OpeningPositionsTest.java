package com.chesstweek.gamereview.internal.explorer;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpeningPositionsTest {

    @Test
    void listsEachPositionWithTheMovePlayedFromItAndTheFinalPosition() {
        List<OpeningPositions.IndexedPosition> positions = OpeningPositions.of("e4 e5 Nf3");

        assertThat(positions).extracting(OpeningPositions.IndexedPosition::ply)
                .containsExactly(0, 1, 2, 3);
        assertThat(positions).extracting(OpeningPositions.IndexedPosition::nextUci)
                .containsExactly("e2e4", "e7e5", "g1f3", null);
        assertThat(positions).extracting(OpeningPositions.IndexedPosition::nextSan)
                .containsExactly("e4", "e5", "Nf3", null);
        assertThat(positions.getFirst().key())
                .isEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -");
    }

    @Test
    void stopsAfterTheIndexedPliesWithoutAFinalPosition() {
        String shuffle = String.join(" ", java.util.Collections.nCopies(20, "Nf3 Nf6 Ng1 Ng8"));

        List<OpeningPositions.IndexedPosition> positions = OpeningPositions.of(shuffle);

        assertThat(positions).hasSize(OpeningPositions.MAX_PLIES);
        assertThat(positions.getLast().ply()).isEqualTo(OpeningPositions.MAX_PLIES - 1);
        assertThat(positions.getLast().nextUci()).isNotNull();
    }

    @Test
    void aGameOfExactlyTheIndexedPliesKeepsItsFinalPosition() {
        String shuffle = String.join(" ", java.util.Collections.nCopies(15, "Nf3 Nf6 Ng1 Ng8"));

        List<OpeningPositions.IndexedPosition> positions = OpeningPositions.of(shuffle);

        assertThat(positions).hasSize(OpeningPositions.MAX_PLIES + 1);
        assertThat(positions.getLast().nextUci()).isNull();
    }

    @Test
    void rejectsIllegalMoves() {
        assertThatThrownBy(() -> OpeningPositions.of("e4 e4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpeningPositions.of("Zz9"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyGameHasOnlyTheStartPosition() {
        assertThat(OpeningPositions.of(" ")).singleElement()
                .satisfies(position -> assertThat(position.nextUci()).isNull());
    }
}
