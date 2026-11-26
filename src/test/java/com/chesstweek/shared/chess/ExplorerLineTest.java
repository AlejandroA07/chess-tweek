package com.chesstweek.shared.chess;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplorerLineTest {

    private static final ExplorerPosition START = ExplorerPosition.parse(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").orElseThrow();

    @Test
    void playsMovesAndNamesThemInSan() {
        ExplorerLine line = ExplorerLine.play(START, List.of("e2e4", "c7c6", "d2d4", "d7d5")).orElseThrow();

        assertThat(line.steps()).extracting(ExplorerLine.Step::san).containsExactly("e4", "c6", "d4", "d5");
        assertThat(line.end().key()).isEqualTo("rnbqkbnr/pp2pppp/2p5/3p4/3PP3/8/PPP2PPP/RNBQKBNR w KQkq -");
        assertThat(line.end().fen()).endsWith(" 0 3");
    }

    @Test
    void acceptsCastlingWrittenAsTheKingTakingItsRook() {
        List<String> setup = List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5");

        ExplorerLine kingTakesRook = ExplorerLine.play(START, append(setup, "e1h1")).orElseThrow();
        ExplorerLine standard = ExplorerLine.play(START, append(setup, "e1g1")).orElseThrow();

        assertThat(kingTakesRook.steps().getLast().uci()).isEqualTo("e1g1");
        assertThat(kingTakesRook.steps().getLast().san()).isEqualTo("O-O");
        assertThat(kingTakesRook.end()).isEqualTo(standard.end());
    }

    @Test
    void anEmptyLineEndsAtItsStart() {
        assertThat(ExplorerLine.play(START, List.of()).orElseThrow().end()).isEqualTo(START);
    }

    @Test
    void rejectsMalformedIllegalOrTooLongLines() {
        assertThat(ExplorerLine.play(START, List.of("e2e5"))).isEmpty();
        assertThat(ExplorerLine.play(START, List.of("e7e5"))).isEmpty();
        assertThat(ExplorerLine.play(START, List.of("E2E4"))).isEmpty();
        assertThat(ExplorerLine.play(START, List.of(""))).isEmpty();
        assertThat(ExplorerLine.play(START, java.util.Arrays.asList("e2e4", null))).isEmpty();
        assertThat(ExplorerLine.play(START, java.util.Collections.nCopies(201, "g1f3"))).isEmpty();
        assertThat(ExplorerLine.play(START, null)).isEmpty();
    }

    private static List<String> append(List<String> moves, String move) {
        var all = new java.util.ArrayList<>(moves);
        all.add(move);
        return all;
    }
}
