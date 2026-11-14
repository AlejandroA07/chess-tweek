package com.chesstweek.gamereview.internal.engine;

import org.junit.jupiter.api.Test;

import com.chesstweek.gamereview.internal.analysis.Evaluation;
import com.chesstweek.gamereview.internal.analysis.Score;
import com.chesstweek.gamereview.internal.analysis.Wdl;

import static org.assertj.core.api.Assertions.assertThat;

class UciSearchOutputTest {

    @Test
    void keepsTheLatestCompleteLineForEachRankInOrder() {
        var output = new UciSearchOutput();
        output.accept("info depth 10 seldepth 12 multipv 1 score cp 10 wdl 40 940 20 nodes 1000 pv d2d4");
        output.accept("info depth 18 seldepth 25 multipv 2 score cp 15 wdl 50 930 20 nodes 880000 nps 1100000 pv c2c4 e7e5");
        output.accept("info depth 18 seldepth 26 multipv 1 score cp 31 wdl 74 912 14 nodes 880000 nps 1100000 hashfull 300 tbhits 0 time 800 pv e2e4 e7e5 g1f3");
        output.accept("info depth 18 seldepth 22 multipv 3 score mate -4 wdl 0 0 1000 nodes 880000 pv g2g4 e7e5");

        Evaluation evaluation = output.finish("bestmove e2e4 ponder e7e5");

        assertThat(evaluation.lines()).hasSize(3);
        Evaluation.Line best = evaluation.best();
        assertThat(best.move()).isEqualTo("e2e4");
        assertThat(best.score()).isEqualTo(new Score.Centipawns(31));
        assertThat(best.wdl()).isEqualTo(new Wdl(74, 912, 14));
        assertThat(best.pv()).containsExactly("e2e4", "e7e5", "g1f3");
        assertThat(evaluation.lines().get(1).move()).isEqualTo("c2c4");
        assertThat(evaluation.lines().get(2).score()).isEqualTo(new Score.Mate(-4));
    }

    @Test
    void keepsTheBoundLineStockfishReportsLastWhenTheNodeLimitEndsTheSearch() {
        // Real Stockfish 19 output: the node limit stopped depth 19 with rank 1 as a lowerbound
        // whose move is the one Stockfish plays.
        var output = new UciSearchOutput();
        output.accept("info depth 18 seldepth 29 multipv 1 score cp 23 wdl 31 965 4 nodes 755091 nps 1066512 hashfull 15 tbhits 0 time 708 pv g1h1 e7g5 a2a3 g5c1");
        output.accept("info depth 18 seldepth 28 multipv 2 score cp 22 wdl 29 967 4 nodes 755091 nps 1066512 hashfull 15 tbhits 0 time 708 pv a2a3 e7g5 g1h1 g5c1");
        output.accept("info depth 18 seldepth 23 multipv 3 score cp 17 wdl 24 971 5 nodes 755091 nps 1066512 hashfull 15 tbhits 0 time 708 pv a1b1 b7b6 b2b4 e7g5");
        output.accept("info depth 19 seldepth 28 multipv 1 score cp 27 lowerbound wdl 36 961 3 nodes 881038 nps 1055135 hashfull 17 tbhits 0 time 835 pv a2a3 e7g5");
        output.accept("info depth 19 seldepth 28 multipv 2 score cp 23 wdl 31 965 4 nodes 881038 nps 1055135 hashfull 17 tbhits 0 time 835 pv g1h1 e7g5 a2a3 g5c1");
        output.accept("info depth 18 seldepth 25 multipv 3 score cp 17 wdl 24 971 5 nodes 881038 nps 1055135 hashfull 17 tbhits 0 time 835 pv a1b1 e7g5 b2b4 b7b6");

        Evaluation evaluation = output.finish("bestmove a2a3 ponder e7g5");

        assertThat(evaluation.best().move()).isEqualTo("a2a3");
        assertThat(evaluation.best().score()).isEqualTo(new Score.Centipawns(27));
        assertThat(evaluation.best().wdl()).isEqualTo(new Wdl(36, 961, 3));
        assertThat(evaluation.lines()).extracting(Evaluation.Line::move)
                .containsExactly("a2a3", "g1h1", "a1b1");
    }

    @Test
    void ignoresStringAndIncompleteLines() {
        var output = new UciSearchOutput();
        output.accept("info depth 18 multipv 1 score cp 25 wdl 60 930 10 nodes 1 pv e2e4");
        output.accept("info string NNUE evaluation using nn-1111.nnue");
        output.accept("info depth 19 multipv 1 score cp 40 nodes 3 pv d2d4");
        output.accept("info depth 19 multipv 1 score cp 40 wdl 60 930 10 nodes 3");
        output.accept("readyok");

        assertThat(output.finish("bestmove e2e4").best().score()).isEqualTo(new Score.Centipawns(25));
    }

    @Test
    void rejectsOutOfRangeOrMalformedFieldsLineByLine() {
        var output = new UciSearchOutput();
        output.accept("info depth 18 multipv 1 score cp 20 wdl 50 940 10 pv e2e4");
        output.accept("info depth 19 multipv 1 score cp 999999 wdl 50 940 10 pv d2d4");
        output.accept("info depth 19 multipv 1 score cp 20 wdl 500 940 10 pv d2d4");
        output.accept("info depth 19 multipv 1 score cp 20 wdl 50 940 10 pv d2d4;quit");
        output.accept("info depth 19 multipv 1 score cp x wdl 50 940 10 pv d2d4");
        output.accept("info depth 19 multipv 9 score cp 20 wdl 50 940 10 pv d2d4");
        output.accept("info depth 19 multipv 1 score mate 0 wdl 0 0 1000 pv d2d4");
        output.accept("info depth 19 multipv 1 score cp 1234567890123 wdl 50 940 10 pv d2d4");

        assertThat(output.finish("bestmove e2e4").best().move()).isEqualTo("e2e4");
    }

    @Test
    void truncatesVeryLongVariations() {
        var output = new UciSearchOutput();
        String pv = String.join(" ", java.util.Collections.nCopies(100, "g1f3"));
        output.accept("info depth 40 multipv 1 score cp 0 wdl 0 1000 0 pv " + pv);

        assertThat(output.finish("bestmove g1f3").best().pv()).hasSize(Evaluation.Line.MAX_PV_LENGTH);
    }

    @Test
    void failsWhenTheBestLineDisagreesWithBestmoveOrRanksHaveGaps() {
        var disagreeing = new UciSearchOutput();
        disagreeing.accept("info depth 18 multipv 1 score cp 25 wdl 60 930 10 pv e2e4");
        assertThat(disagreeing.finish("bestmove d2d4")).isNull();

        var gapped = new UciSearchOutput();
        gapped.accept("info depth 18 multipv 1 score cp 25 wdl 60 930 10 pv e2e4");
        gapped.accept("info depth 18 multipv 3 score cp 5 wdl 20 960 20 pv g2g3");
        assertThat(gapped.finish("bestmove e2e4")).isNull();

        assertThat(new UciSearchOutput().finish("bestmove e2e4")).isNull();
        var noMove = new UciSearchOutput();
        noMove.accept("info depth 18 multipv 1 score cp 25 wdl 60 930 10 pv e2e4");
        assertThat(noMove.finish("bestmove")).isNull();
    }
}
