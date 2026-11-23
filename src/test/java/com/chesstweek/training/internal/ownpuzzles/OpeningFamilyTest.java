package com.chesstweek.training.internal.ownpuzzles;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningFamilyTest {

    @Test
    void groupsBothSitesNamesByTheirFamily() {
        assertThat(OpeningFamily.of("Indian Game...3.e3 d5 4.Nf3 Be7 5.Bd3")).isEqualTo("Indian Game");
        assertThat(OpeningFamily.of("Indian Game: London System")).isEqualTo("Indian Game");
        assertThat(OpeningFamily.of("Caro-Kann Defense: Advance Variation...4.Nf3"))
                .isEqualTo("Caro-Kann Defense");
        assertThat(OpeningFamily.of("King's Pawn Game")).isEqualTo("King's Pawn Game");
    }

    @Test
    void aMissingOrEmptyNameIsAnUnknownOpening() {
        assertThat(OpeningFamily.of(null)).isEqualTo("Unknown opening");
        assertThat(OpeningFamily.of("   ")).isEqualTo("Unknown opening");
        assertThat(OpeningFamily.of(": Variation")).isEqualTo("Unknown opening");
    }
}
