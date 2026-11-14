package com.chesstweek.gamereview.internal.analysis;

import java.util.Objects;

/**
 * The fixed rules that turn two searches into a move verdict. Everything is from the moving
 * player's perspective: the position before the move has that player to move, and the position
 * after it has the opponent to move.
 *
 * <ul>
 *   <li>Loss is the drop in expected score, clamped at zero. Playing the engine's best move is
 *       never a loss, because the two searches can disagree slightly about the same line.
 *   <li>Inaccuracy: 8 to under 18 percentage points. Mistake: 18 to under 35. Blunder: 35 or
 *       more.
 *   <li>Losing a forced mate, or allowing one, is a blunder whatever the loss.
 *   <li>A move is searched again at the deep budget when it lost at least 5 percentage points
 *       or changed the forced-mate state.
 * </ul>
 */
public final class MoveRules {

    /** Stored with every analysis version; increase it whenever these rules change. */
    public static final int RULES_VERSION = 1;

    public static final int INACCURACY_LOSS = 8 * ExpectedScore.UNITS_PER_PERCENTAGE_POINT;
    public static final int MISTAKE_LOSS = 18 * ExpectedScore.UNITS_PER_PERCENTAGE_POINT;
    public static final int BLUNDER_LOSS = 35 * ExpectedScore.UNITS_PER_PERCENTAGE_POINT;
    public static final int DEEP_PASS_LOSS = 5 * ExpectedScore.UNITS_PER_PERCENTAGE_POINT;

    private MoveRules() {}

    public static Verdict judge(String playedUci, Evaluation before, After after) {
        Objects.requireNonNull(playedUci);
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        Evaluation.Line best = before.best();
        ExpectedScore scoreBefore = best.wdl().expectedScore();
        MateState stateBefore = MateState.ofSideToMove(best.score());
        ExpectedScore scoreAfter = after.expectedScoreForMover();
        MateState stateAfter = after.mateStateForMover();

        if (playedUci.equals(best.move())) {
            return new Verdict(true, scoreBefore, scoreAfter, 0, stateBefore, stateAfter,
                    MateEvent.NONE, Label.NONE);
        }
        int loss = Math.max(0, scoreBefore.halfPermille() - scoreAfter.halfPermille());
        MateEvent mateEvent = mateEvent(stateBefore, stateAfter);
        Label label = mateEvent != MateEvent.NONE ? Label.BLUNDER : label(loss);
        return new Verdict(
                false, scoreBefore, scoreAfter, loss, stateBefore, stateAfter, mateEvent, label);
    }

    static Label label(int loss) {
        if (loss >= BLUNDER_LOSS) {
            return Label.BLUNDER;
        }
        if (loss >= MISTAKE_LOSS) {
            return Label.MISTAKE;
        }
        if (loss >= INACCURACY_LOSS) {
            return Label.INACCURACY;
        }
        return Label.NONE;
    }

    private static MateEvent mateEvent(MateState before, MateState after) {
        if (before == MateState.MATING && after != MateState.MATING) {
            return MateEvent.MISSED_MATE;
        }
        if (before != MateState.MATED && after == MateState.MATED) {
            return MateEvent.ALLOWED_MATE;
        }
        return MateEvent.NONE;
    }

    /** The position after the move: either a search with the opponent to move, or a finished game. */
    public sealed interface After {

        ExpectedScore expectedScoreForMover();

        MateState mateStateForMover();

        record Searched(Evaluation evaluation) implements After {

            public Searched {
                Objects.requireNonNull(evaluation);
            }

            @Override
            public ExpectedScore expectedScoreForMover() {
                return evaluation.best().wdl().expectedScore().flipped();
            }

            @Override
            public MateState mateStateForMover() {
                return MateState.ofSideToMove(evaluation.best().score()).flipped();
            }
        }

        /** The move ended the game on the board, so no search is needed or possible. */
        record Finished(ReplayedGame.Terminal terminal) implements After {

            public Finished {
                if (terminal == null || terminal == ReplayedGame.Terminal.NONE) {
                    throw new IllegalArgumentException("A finished position is checkmate or stalemate");
                }
            }

            @Override
            public ExpectedScore expectedScoreForMover() {
                return terminal == ReplayedGame.Terminal.CHECKMATE ? ExpectedScore.WIN : ExpectedScore.DRAW;
            }

            @Override
            public MateState mateStateForMover() {
                return terminal == ReplayedGame.Terminal.CHECKMATE ? MateState.MATING : MateState.NONE;
            }
        }
    }

    /** Whether a player has a forced mate, is being forcibly mated, or neither. */
    public enum MateState {
        NONE,
        MATING,
        MATED;

        static MateState ofSideToMove(Score score) {
            if (score instanceof Score.Mate(int moves)) {
                return moves > 0 ? MATING : MATED;
            }
            return NONE;
        }

        MateState flipped() {
            return switch (this) {
                case NONE -> NONE;
                case MATING -> MATED;
                case MATED -> MATING;
            };
        }
    }

    public enum MateEvent {
        NONE,
        MISSED_MATE,
        ALLOWED_MATE
    }

    public enum Label {
        NONE,
        INACCURACY,
        MISTAKE,
        BLUNDER
    }

    /**
     * {@code loss} is in half-permille of expected score; 20 units are one percentage point.
     * {@code playedBest} moves are never searched again.
     */
    public record Verdict(
            boolean playedBest,
            ExpectedScore before,
            ExpectedScore after,
            int loss,
            MateState mateBefore,
            MateState mateAfter,
            MateEvent mateEvent,
            Label label) {

        public boolean needsDeepPass() {
            return !playedBest && (loss >= DEEP_PASS_LOSS || mateBefore != mateAfter);
        }

        public double lossPercentagePoints() {
            return loss / (double) ExpectedScore.UNITS_PER_PERCENTAGE_POINT;
        }
    }
}
