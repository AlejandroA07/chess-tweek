package com.chesstweek.gamereview.internal.analysis;

/**
 * An expected score held exactly as an integer from 0 (certain loss) to 2000 (certain win), so
 * half a permille of draw probability is never rounded. One percentage point is 20 units.
 */
public record ExpectedScore(int halfPermille) {

    public static final int MAX = 2_000;
    public static final int UNITS_PER_PERCENTAGE_POINT = 20;

    public static final ExpectedScore WIN = new ExpectedScore(MAX);
    public static final ExpectedScore DRAW = new ExpectedScore(MAX / 2);

    public ExpectedScore {
        if (halfPermille < 0 || halfPermille > MAX) {
            throw new IllegalArgumentException("Expected score must be between 0 and 2000 half-permille");
        }
    }

    /** The same score from the other player's perspective. */
    public ExpectedScore flipped() {
        return new ExpectedScore(MAX - halfPermille);
    }

    public double percent() {
        return halfPermille / (double) UNITS_PER_PERCENTAGE_POINT;
    }
}
