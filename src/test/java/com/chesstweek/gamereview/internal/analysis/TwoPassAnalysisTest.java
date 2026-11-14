package com.chesstweek.gamereview.internal.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.chesstweek.gamereview.internal.analysis.MoveRules.Label;
import com.chesstweek.gamereview.internal.analysis.MoveRules.MateEvent;
import com.chesstweek.gamereview.internal.analysis.ReplayedGame.Color;
import com.chesstweek.gamereview.internal.analysis.TwoPassAnalysis.MoveFinding;
import com.chesstweek.gamereview.internal.analysis.TwoPassAnalysis.Pass;
import com.chesstweek.gamereview.internal.analysis.TwoPassAnalysis.SearchFailedException;

import static com.chesstweek.gamereview.internal.analysis.MoveRulesTest.centipawns;
import static com.chesstweek.gamereview.internal.analysis.MoveRulesTest.eval;
import static com.chesstweek.gamereview.internal.analysis.MoveRulesTest.mate;
import static com.chesstweek.gamereview.internal.analysis.MoveRulesTest.wdl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwoPassAnalysisTest {

    private static final long QUICK = 880_000;
    private static final long DEEP = 3_540_000;
    private static final String SCHOLARS_MATE = "e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#";

    private final FakeEngine engine = new FakeEngine();

    @Test
    void searchesEachPositionOfTheRequestedSideOnceAtTheQuickBudget() throws Exception {
        ReplayedGame game = ReplayedGame.fromSan("e4 e5 Nf3 Nc6");

        List<MoveFinding> findings = analyze(game, Set.of(Color.WHITE));

        assertThat(findings).extracting(finding -> finding.move().san()).containsExactly("e4", "Nf3");
        assertThat(findings).extracting(MoveFinding::pass).containsOnly(Pass.QUICK);
        assertThat(engine.searches).containsExactly(
                new Search(0, QUICK), new Search(1, QUICK), new Search(2, QUICK), new Search(3, QUICK));
    }

    @Test
    void bothSidesShareThePositionsBetweenTheirMoves() throws Exception {
        ReplayedGame game = ReplayedGame.fromSan("e4 e5 Nf3 Nc6");

        List<MoveFinding> findings = analyze(game, Set.of(Color.WHITE, Color.BLACK));

        assertThat(findings).extracting(finding -> finding.move().mover())
                .containsExactly(Color.WHITE, Color.BLACK, Color.WHITE, Color.BLACK);
        assertThat(engine.searches).extracting(Search::position).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void searchesBothPositionsOfAFivePointLossAgainAtTheDeepBudgetAndJudgesFromThoseAlone()
            throws Exception {
        ReplayedGame game = ReplayedGame.fromSan("e4 e5 Nf3 Nc6");
        // Black's Nc6 from position 3: quick sees a 30-point loss, deep sees a 6-point loss.
        engine.at(3, QUICK, eval("g8f6", centipawns(0), wdl(0, 1_000, 0)));
        engine.at(4, QUICK, eval("f1b5", centipawns(150), wdl(400, 600, 0)));
        engine.at(3, DEEP, eval("g8f6", centipawns(0), wdl(0, 1_000, 0)));
        engine.at(4, DEEP, eval("f1b5", centipawns(20), wdl(120, 880, 0)));

        List<MoveFinding> findings = analyze(game, Set.of(Color.BLACK));

        MoveFinding nc6 = findings.get(1);
        assertThat(nc6.pass()).isEqualTo(Pass.DEEP);
        assertThat(nc6.verdict().lossPercentagePoints()).isEqualTo(6.0);
        assertThat(nc6.verdict().label()).isEqualTo(Label.NONE);
        assertThat(nc6.before().best().wdl()).isEqualTo(wdl(0, 1_000, 0));
        assertThat(findings.get(0).pass()).isEqualTo(Pass.QUICK);
        assertThat(engine.searches).endsWith(new Search(3, DEEP), new Search(4, DEEP));
        assertThat(engine.searches).filteredOn(search -> search.nodes() == DEEP).hasSize(2);
    }

    @Test
    void aLossJustUnderFivePointsIsNotSearchedAgain() throws Exception {
        ReplayedGame game = ReplayedGame.fromSan("e4 e5");
        engine.at(1, QUICK, eval("c7c5", centipawns(0), wdl(0, 1_000, 0)));
        engine.at(2, QUICK, eval("g1f3", centipawns(30), wdl(98, 901, 1)));

        List<MoveFinding> findings = analyze(game, Set.of(Color.BLACK));

        assertThat(findings.getFirst().verdict().loss()).isEqualTo(97);
        assertThat(findings.getFirst().pass()).isEqualTo(Pass.QUICK);
        assertThat(engine.searches).extracting(Search::nodes).containsOnly(QUICK);
    }

    @Test
    void aForcedMateChangeIsSearchedAgainAndADeepMissedMateIsABlunder() throws Exception {
        // The fake claims White had a forced mate starting with Bc4 and that Qh5 lost it.
        ReplayedGame game = ReplayedGame.fromSan("e4 e5 Qh5 Nc6");
        engine.at(2, QUICK, eval("f1c4", mate(9), wdl(1_000, 0, 0)));
        engine.at(3, QUICK, eval("b8c6", centipawns(-800), wdl(0, 0, 1_000)));
        engine.at(2, DEEP, eval("f1c4", mate(7), wdl(1_000, 0, 0)));
        engine.at(3, DEEP, eval("b8c6", centipawns(-900), wdl(0, 0, 1_000)));

        MoveFinding qh5 = analyze(game, Set.of(Color.WHITE)).get(1);

        assertThat(qh5.pass()).isEqualTo(Pass.DEEP);
        assertThat(qh5.verdict().loss()).isZero();
        assertThat(qh5.verdict().mateEvent()).isEqualTo(MateEvent.MISSED_MATE);
        assertThat(qh5.verdict().label()).isEqualTo(Label.BLUNDER);
        assertThat(qh5.before().best().score()).isEqualTo(mate(7));
    }

    @Test
    void neverSearchesAPositionThatEndedTheGame() throws Exception {
        ReplayedGame game = ReplayedGame.fromSan(SCHOLARS_MATE);
        engine.at(6, QUICK, eval("h5f7", mate(1), wdl(1_000, 0, 0)));

        MoveFinding mateMove = analyze(game, Set.of(Color.WHITE)).getLast();

        assertThat(mateMove.after()).isInstanceOf(MoveRules.After.Finished.class);
        assertThat(mateMove.verdict().label()).isEqualTo(Label.NONE);
        assertThat(engine.searches).extracting(Search::position).doesNotContain(7);
    }

    @Test
    void attachesThePhaseOfEachMove() throws Exception {
        ReplayedGame game = ReplayedGame.fromSan("e4 e5 Nf3 Nc6");
        GamePhases phases = new GamePhases(GamePhases.Source.SUPPLIED, 2, null);

        List<MoveFinding> findings = new TwoPassAnalysis(engine, QUICK, DEEP)
                .analyze(game, phases, Set.of(Color.WHITE));

        assertThat(findings).extracting(MoveFinding::phase)
                .containsExactly(GamePhases.Phase.OPENING, GamePhases.Phase.MIDDLEGAME);
    }

    @Test
    void aFailedSearchFailsTheWholeAnalysis() {
        ReplayedGame game = ReplayedGame.fromSan("e4 e5 Nf3 Nc6");
        engine.failAt = 2;

        assertThatThrownBy(() -> analyze(game, Set.of(Color.WHITE)))
                .isInstanceOf(SearchFailedException.class);
    }

    @Test
    void aSearchThatReturnsNothingFailsTheAnalysis() {
        ReplayedGame game = ReplayedGame.fromSan("e4");

        assertThatThrownBy(() -> new TwoPassAnalysis((position, nodes) -> null, QUICK, DEEP)
                        .analyze(game, GamePhases.of(null, game), Set.of(Color.WHITE)))
                .isInstanceOf(SearchFailedException.class);
    }

    @Test
    void rejectsInvalidBudgetsAndAnEmptySideSelection() {
        assertThatThrownBy(() -> new TwoPassAnalysis(engine, 0, DEEP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TwoPassAnalysis(engine, DEEP, QUICK))
                .isInstanceOf(IllegalArgumentException.class);
        ReplayedGame game = ReplayedGame.fromSan("e4");
        assertThatThrownBy(() -> analyze(game, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<MoveFinding> analyze(ReplayedGame game, Set<Color> sides) throws SearchFailedException {
        return new TwoPassAnalysis(engine, QUICK, DEEP).analyze(game, GamePhases.of(null, game), sides);
    }

    private record Search(int position, long nodes) {}

    /** Answers from a table keyed by position and budget; unknown positions are dead level. */
    private static final class FakeEngine implements TwoPassAnalysis.PositionSearch {

        final List<Search> searches = new ArrayList<>();
        final Map<Search, Evaluation> answers = new HashMap<>();
        Integer failAt;

        void at(int position, long nodes, Evaluation evaluation) {
            answers.put(new Search(position, nodes), evaluation);
        }

        @Override
        public Evaluation search(ReplayedGame.Position position, long nodes) throws SearchFailedException {
            var search = new Search(position.index(), nodes);
            searches.add(search);
            if (failAt != null && failAt == position.index()) {
                throw new SearchFailedException("engine stopped");
            }
            return answers.getOrDefault(search, eval("a2a3", centipawns(0), wdl(0, 1_000, 0)));
        }
    }
}
