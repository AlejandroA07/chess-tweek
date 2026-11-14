package com.chesstweek.gamereview.internal.analysis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.internal.analysis.GamePhases.Phase;
import com.chesstweek.gamereview.internal.analysis.GamePhases.Source;
import com.chesstweek.gamereview.internal.analysis.ReplayedGame.Color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GamePhasesTest {

    private static final int FULL = 62;

    @Test
    void prefersTheSuppliedLichessDivision() {
        GamePhases phases = GamePhases.of(
                new CatalogGame.PhaseDivision(14, 60), syntheticGame(80, index -> material(2, FULL)));

        assertThat(phases.source()).isEqualTo(Source.SUPPLIED);
        assertThat(phases.phaseOfMove(14)).isEqualTo(Phase.OPENING);
        assertThat(phases.phaseOfMove(15)).isEqualTo(Phase.MIDDLEGAME);
        assertThat(phases.phaseOfMove(60)).isEqualTo(Phase.MIDDLEGAME);
        assertThat(phases.phaseOfMove(61)).isEqualTo(Phase.ENDGAME);
    }

    @Test
    void suppliedDivisionWithoutAnEndgameNeverReachesTheEndgame() {
        GamePhases phases = GamePhases.of(
                new CatalogGame.PhaseDivision(18, null), syntheticGame(80, index -> material(0, 0)));

        assertThat(phases.source()).isEqualTo(Source.SUPPLIED);
        assertThat(phases.phaseOfMove(80)).isEqualTo(Phase.MIDDLEGAME);
    }

    @Test
    void suppliedEndgameWithoutAMiddlegameRunsStraightFromTheOpening() {
        GamePhases phases = GamePhases.of(
                new CatalogGame.PhaseDivision(null, 30), syntheticGame(40, index -> material(2, FULL)));

        assertThat(phases.phaseOfMove(30)).isEqualTo(Phase.OPENING);
        assertThat(phases.phaseOfMove(31)).isEqualTo(Phase.ENDGAME);
    }

    @Test
    void infersTheOpeningThroughPlyTwentyWhenNoDivisionIsSupplied() {
        GamePhases phases = GamePhases.of(null, syntheticGame(60, index -> material(2, FULL)));

        assertThat(phases.source()).isEqualTo(Source.INFERRED);
        assertThat(phases.phaseOfMove(1)).isEqualTo(Phase.OPENING);
        assertThat(phases.phaseOfMove(20)).isEqualTo(Phase.OPENING);
        assertThat(phases.phaseOfMove(21)).isEqualTo(Phase.MIDDLEGAME);
        assertThat(phases.phaseOfMove(60)).isEqualTo(Phase.MIDDLEGAME);
        assertThat(phases.endgameStart()).isNull();
    }

    @Test
    void treatsAnEmptySuppliedDivisionAsMissing() {
        GamePhases phases = GamePhases.of(
                new CatalogGame.PhaseDivision(null, null), syntheticGame(30, index -> material(2, FULL)));

        assertThat(phases.source()).isEqualTo(Source.INFERRED);
    }

    @Test
    void infersTheEndgameOnceQueensAreGoneAndNonPawnMaterialIsAtMostTwenty() {
        GamePhases phases = GamePhases.of(null, syntheticGame(70, index -> index < 40
                ? material(2, FULL)
                : index < 50 ? material(0, 21) : material(0, 20)));

        assertThat(phases.endgameStart()).isEqualTo(50);
        assertThat(phases.phaseOfMove(50)).isEqualTo(Phase.MIDDLEGAME);
        assertThat(phases.phaseOfMove(51)).isEqualTo(Phase.ENDGAME);
    }

    @Test
    void aRemainingQueenKeepsTheGameOutOfTheEndgame() {
        GamePhases phases = GamePhases.of(null, syntheticGame(70, index -> material(1, 9)));

        assertThat(phases.endgameStart()).isNull();
    }

    @Test
    void theInferredEndgameNeverStartsInsideTheOpeningAndNeverEnds() {
        GamePhases phases = GamePhases.of(null, syntheticGame(40, index -> index < 30
                ? material(0, 10)
                : material(1, 19)));

        assertThat(phases.endgameStart()).isEqualTo(20);
        assertThat(phases.phaseOfMove(20)).isEqualTo(Phase.OPENING);
        assertThat(phases.phaseOfMove(21)).isEqualTo(Phase.ENDGAME);
        assertThat(phases.phaseOfMove(40)).isEqualTo(Phase.ENDGAME);
    }

    @Test
    void rejectsOutOfOrderBoundaries() {
        ReplayedGame game = syntheticGame(80, index -> material(2, FULL));

        assertThatThrownBy(() -> GamePhases.of(new CatalogGame.PhaseDivision(50, 40), game))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GamePhases.of(null, game).phaseOfMove(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record Material(int queens, int nonPawn) {}

    private static Material material(int queens, int nonPawn) {
        return new Material(queens, nonPawn);
    }

    private interface MaterialAt {
        Material at(int index);
    }

    /** A game whose positions carry only the material the phase rules read. */
    private static ReplayedGame syntheticGame(int plies, MaterialAt materialAt) {
        var positions = new ArrayList<ReplayedGame.Position>();
        var moves = new ArrayList<ReplayedGame.PlayedMove>();
        for (int index = 0; index <= plies; index++) {
            Material material = materialAt.at(index);
            Color toMove = index % 2 == 0 ? Color.WHITE : Color.BLACK;
            positions.add(new ReplayedGame.Position(
                    index, "fen-" + index, toMove, List.of(), material.queens(), material.nonPawn(),
                    ReplayedGame.Terminal.NONE));
            if (index > 0) {
                moves.add(new ReplayedGame.PlayedMove(index, toMove.opponent(), "m", "a2a3"));
            }
        }
        return new ReplayedGame(positions, moves);
    }
}
