package com.chesstweek.gamereview.internal.analysis;

/**
 * Stockfish win/draw/loss output in permille from the side to move. The three values always sum
 * to 1000.
 */
public record Wdl(int win, int draw, int loss) {

    public Wdl {
        if (win < 0 || draw < 0 || loss < 0 || win + draw + loss != 1000) {
            throw new IllegalArgumentException("WDL must be three non-negative permille values summing to 1000");
        }
    }

    /** Win probability plus half the draw probability, for the side to move. */
    public ExpectedScore expectedScore() {
        return new ExpectedScore(2 * win + draw);
    }
}
