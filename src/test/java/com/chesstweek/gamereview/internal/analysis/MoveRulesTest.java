package com.chesstweek.gamereview.internal.analysis;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.chesstweek.gamereview.internal.analysis.MoveRules.After;
import com.chesstweek.gamereview.internal.analysis.MoveRules.Label;
import com.chesstweek.gamereview.internal.analysis.MoveRules.MateEvent;
import com.chesstweek.gamereview.internal.analysis.MoveRules.MateState;
import com.chesstweek.gamereview.internal.analysis.MoveRules.Verdict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoveRulesTest {

    private static final String PLAYED = "e2e4";
    private static final String BEST = "d2d4";

    @Test
    void expectedScoreIsWinPlusHalfTheDrawForTheSideToMove() {
        ExpectedScore score = new Wdl(500, 301, 199).expectedScore();

        assertThat(score.halfPermille()).isEqualTo(1_301);
        assertThat(score.percent()).isEqualTo(65.05);
        assertThat(score.flipped().halfPermille()).isEqualTo(699);
    }

    @Test
    void rejectsWdlThatIsNotThreeNonNegativePermilleValues() {
        assertThatThrownBy(() -> new Wdl(500, 300, 199)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Wdl(-1, 1_001, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesTheMoversScoreBeforeWithTheOpponentsScoreAfterFlipped() {
        // Before: mover 60%. After: opponent to move at 70%, so the mover is at 30%.
        Verdict verdict = MoveRules.judge(
                PLAYED, eval(BEST, centipawns(40), wdl(400, 400, 200)), searched(centipawns(80), wdl(500, 400, 100)));

        assertThat(verdict.before().percent()).isEqualTo(60.0);
        assertThat(verdict.after().percent()).isEqualTo(30.0);
        assertThat(verdict.lossPercentagePoints()).isEqualTo(30.0);
        assertThat(verdict.label()).isEqualTo(Label.MISTAKE);
    }

    @ParameterizedTest
    @CsvSource({
        "159, NONE",
        "160, INACCURACY",
        "359, INACCURACY",
        "360, MISTAKE",
        "699, MISTAKE",
        "700, BLUNDER",
        "2000, BLUNDER"
    })
    void labelsLossAtTheEightEighteenAndThirtyFivePointThresholds(int loss, Label expected) {
        assertThat(MoveRules.label(loss)).isEqualTo(expected);
        assertThat(judgeWithLoss(loss).label()).isEqualTo(expected);
    }

    @Test
    void clampsAnApparentImprovementToZeroLoss() {
        Verdict verdict = MoveRules.judge(
                PLAYED, eval(BEST, centipawns(0), wdl(0, 1_000, 0)), searched(centipawns(-200), wdl(0, 200, 800)));

        assertThat(verdict.after().percent()).isEqualTo(90.0);
        assertThat(verdict.loss()).isZero();
        assertThat(verdict.label()).isEqualTo(Label.NONE);
        assertThat(verdict.needsDeepPass()).isFalse();
    }

    @Test
    void playingTheEnginesBestMoveIsNeverALossOrSearchedAgain() {
        Verdict verdict = MoveRules.judge(
                BEST, eval(BEST, mate(4), wdl(1_000, 0, 0)), searched(centipawns(300), wdl(900, 100, 0)));

        assertThat(verdict.playedBest()).isTrue();
        assertThat(verdict.loss()).isZero();
        assertThat(verdict.mateEvent()).isEqualTo(MateEvent.NONE);
        assertThat(verdict.label()).isEqualTo(Label.NONE);
        assertThat(verdict.needsDeepPass()).isFalse();
    }

    @Test
    void losingAForcedMateIsABlunderEvenWithoutExpectedScoreLoss() {
        Verdict verdict = MoveRules.judge(
                PLAYED, eval(BEST, mate(3), wdl(1_000, 0, 0)), searched(centipawns(-900), wdl(0, 0, 1_000)));

        assertThat(verdict.loss()).isZero();
        assertThat(verdict.mateBefore()).isEqualTo(MateState.MATING);
        assertThat(verdict.mateAfter()).isEqualTo(MateState.NONE);
        assertThat(verdict.mateEvent()).isEqualTo(MateEvent.MISSED_MATE);
        assertThat(verdict.label()).isEqualTo(Label.BLUNDER);
        assertThat(verdict.needsDeepPass()).isTrue();
    }

    @Test
    void allowingAForcedMateIsABlunderEvenBelowTheNumericThreshold() {
        // The draw-heavy WDL keeps the loss at 30 points, under the blunder threshold.
        Verdict verdict = MoveRules.judge(
                PLAYED, eval(BEST, centipawns(0), wdl(0, 1_000, 0)), searched(mate(5), wdl(600, 400, 0)));

        assertThat(verdict.lossPercentagePoints()).isEqualTo(30.0);
        assertThat(verdict.mateAfter()).isEqualTo(MateState.MATED);
        assertThat(verdict.mateEvent()).isEqualTo(MateEvent.ALLOWED_MATE);
        assertThat(verdict.label()).isEqualTo(Label.BLUNDER);
    }

    @Test
    void keepingAForcedMateWithALongerDistanceIsNotAMissedMate() {
        Verdict verdict = MoveRules.judge(
                PLAYED, eval(BEST, mate(2), wdl(1_000, 0, 0)), searched(mate(-6), wdl(0, 0, 1_000)));

        assertThat(verdict.mateAfter()).isEqualTo(MateState.MATING);
        assertThat(verdict.mateEvent()).isEqualTo(MateEvent.NONE);
        assertThat(verdict.label()).isEqualTo(Label.NONE);
        assertThat(verdict.needsDeepPass()).isFalse();
    }

    @Test
    void aPlayerAlreadyBeingMatedDoesNotAllowMateAgain() {
        Verdict verdict = MoveRules.judge(
                PLAYED, eval(BEST, mate(-5), wdl(0, 0, 1_000)), searched(mate(2), wdl(1_000, 0, 0)));

        assertThat(verdict.mateEvent()).isEqualTo(MateEvent.NONE);
        assertThat(verdict.label()).isEqualTo(Label.NONE);
    }

    @Test
    void deliveringCheckmateKeepsTheMateAndLosesNothing() {
        Verdict verdict = MoveRules.judge(
                PLAYED,
                eval(BEST, mate(1), wdl(1_000, 0, 0)),
                new After.Finished(ReplayedGame.Terminal.CHECKMATE));

        assertThat(verdict.after()).isEqualTo(ExpectedScore.WIN);
        assertThat(verdict.mateAfter()).isEqualTo(MateState.MATING);
        assertThat(verdict.loss()).isZero();
        assertThat(verdict.label()).isEqualTo(Label.NONE);
    }

    @Test
    void stalematingFromAWonPositionIsJudgedAsADraw() {
        Verdict verdict = MoveRules.judge(
                PLAYED,
                eval(BEST, centipawns(900), wdl(990, 10, 0)),
                new After.Finished(ReplayedGame.Terminal.STALEMATE));

        assertThat(verdict.after()).isEqualTo(ExpectedScore.DRAW);
        assertThat(verdict.lossPercentagePoints()).isEqualTo(49.5);
        assertThat(verdict.label()).isEqualTo(Label.BLUNDER);
    }

    @Test
    void searchesAgainFromFivePointsOfLossOrAnyForcedMateChange() {
        assertThat(judgeWithLoss(99).needsDeepPass()).isFalse();
        assertThat(judgeWithLoss(100).needsDeepPass()).isTrue();

        Verdict foundMate = MoveRules.judge(
                PLAYED, eval(BEST, centipawns(500), wdl(1_000, 0, 0)), searched(mate(-3), wdl(0, 0, 1_000)));
        assertThat(foundMate.loss()).isZero();
        assertThat(foundMate.mateEvent()).isEqualTo(MateEvent.NONE);
        assertThat(foundMate.needsDeepPass()).isTrue();
    }

    @Test
    void rejectsMalformedEngineLines() {
        assertThatThrownBy(() -> new Evaluation(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Evaluation.Line("e2e9", centipawns(0), wdl(0, 1_000, 0), List.of("e2e9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Evaluation.Line("e2e4", centipawns(0), wdl(0, 1_000, 0), List.of("d2d4")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Score.Mate(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A move judged from a 100% position to one worth exactly {@code loss} units less. */
    private static Verdict judgeWithLoss(int loss) {
        int afterForMover = ExpectedScore.MAX - loss;
        // The opponent's half-permille after the move is the mover's complement.
        int opponent = ExpectedScore.MAX - afterForMover;
        Wdl opponentWdl = opponent <= 1_000
                ? wdl(0, opponent, 1_000 - opponent)
                : wdl(opponent - 1_000, 2_000 - opponent, 0);
        return MoveRules.judge(PLAYED, eval(BEST, centipawns(600), wdl(1_000, 0, 0)),
                searched(centipawns(0), opponentWdl));
    }

    static Evaluation eval(String move, Score score, Wdl wdl) {
        return new Evaluation(List.of(new Evaluation.Line(move, score, wdl, List.of(move))));
    }

    private static After searched(Score score, Wdl wdl) {
        return new After.Searched(eval("a7a6", score, wdl));
    }

    static Score centipawns(int value) {
        return new Score.Centipawns(value);
    }

    static Score mate(int moves) {
        return new Score.Mate(moves);
    }

    static Wdl wdl(int win, int draw, int loss) {
        return new Wdl(win, draw, loss);
    }
}
