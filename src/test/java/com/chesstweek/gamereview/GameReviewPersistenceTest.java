package com.chesstweek.gamereview;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.gamereview.internal.analysis.Evaluation;
import com.chesstweek.gamereview.internal.analysis.GamePhases;
import com.chesstweek.gamereview.internal.analysis.ReplayedGame;
import com.chesstweek.gamereview.internal.analysis.Score;
import com.chesstweek.gamereview.internal.analysis.TwoPassAnalysis;
import com.chesstweek.gamereview.internal.analysis.Wdl;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionStore;
import com.chesstweek.gamereview.internal.persistence.EngineProfileStore;
import com.chesstweek.gamereview.internal.persistence.GameCatalogStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameReviewPersistenceTest {

    private static final String ACCOUNT = "owner";
    private static final String GAME_ID = "abcd1234";
    private static final String MOVES = "e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#";
    private static final Instant STARTED = Instant.parse("2026-11-14T12:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-11-14T12:01:30Z");

    @Test
    void reviewsTheOwnersMovesByDefaultWithEveryPositionAndReadableLines(@TempDir Path directory)
            throws Exception {
        try (var application = start(directory)) {
            storeAnalyzedGame(application);

            GameReviews.GameReview review = reviews(application).review(ACCOUNT, GAME_ID, null).orElseThrow();

            assertThat(review.initialFen()).startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w");
            assertThat(review.plies()).extracting(GameReviews.Ply::san)
                    .containsExactly("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#");
            assertThat(review.plies().getLast().fen()).startsWith("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/");
            assertThat(review.plies().get(1).color()).isEqualTo("black");
            assertThat(review.phases()).isEqualTo(new GameReviews.Phases("inferred", 20, null));
            assertThat(review.myMoves().moves()).isEqualTo(4);
            assertThat(review.opponentMoves().blunders()).isEqualTo(1);

            GameReviews.SideReview mine = review.selected();
            assertThat(mine.side()).isEqualTo(GameReviews.Side.MY_MOVES);
            assertThat(mine.analyzedColor()).isEqualTo("white");
            assertThat(mine.engineName()).isEqualTo("Stockfish 19");
            assertThat(mine.quickNodes()).isEqualTo(880_000);
            assertThat(mine.completedAt()).isEqualTo(COMPLETED);
            assertThat(mine.currentProfile()).isTrue();
            assertThat(mine.moves()).extracting(GameReviews.JudgedMove::ply).containsExactly(1, 3, 5, 7);

            GameReviews.JudgedMove mate = mine.moves().getLast();
            assertThat(mate.playedBest()).isTrue();
            assertThat(mate.bestMoveSan()).isEqualTo("Qxf7#");
            assertThat(mate.afterTerminal()).isEqualTo("checkmate");
            assertThat(mate.after()).isEmpty();
            assertThat(mate.before().getFirst().pvSan()).containsExactly("Qxf7#");
            assertThat(mate.before().getFirst().mate()).isEqualTo(1);

            assertThat(mine.evaluations()).extracting(GameReviews.PositionEvaluation::index)
                    .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
            GameReviews.PositionEvaluation beforeMate = mine.evaluations().get(6);
            assertThat(beforeMate.whiteExpected()).isEqualTo(2_000);
            assertThat(beforeMate.whiteMate()).isEqualTo(1);
            assertThat(mine.evaluations().getLast().whiteExpected()).isEqualTo(2_000);
            assertThat(mine.statistics().blunders()).isZero();
        }
    }

    @Test
    void reviewsTheOpponentsMovesSeparately(@TempDir Path directory) throws Exception {
        try (var application = start(directory)) {
            storeAnalyzedGame(application);

            GameReviews.SideReview theirs = reviews(application)
                    .review(ACCOUNT, GAME_ID, GameReviews.Side.OPPONENT_MOVES).orElseThrow().selected();

            assertThat(theirs.side()).isEqualTo(GameReviews.Side.OPPONENT_MOVES);
            assertThat(theirs.analyzedColor()).isEqualTo("black");
            assertThat(theirs.moves()).extracting(GameReviews.JudgedMove::ply).containsExactly(2, 4, 6);

            GameReviews.JudgedMove nf6 = theirs.moves().getLast();
            assertThat(nf6.san()).isEqualTo("Nf6");
            assertThat(nf6.label()).isEqualTo("blunder");
            assertThat(nf6.mateEvent()).isEqualTo("allowed_mate");
            assertThat(nf6.pass()).isEqualTo("deep");
            assertThat(nf6.bestMoveSan()).isEqualTo("Qe7");
            assertThat(nf6.after().getFirst().pvSan()).containsExactly("Qxf7#");
            assertThat(nf6.loss()).isEqualTo(1_000);

            GameReviews.Statistics statistics = theirs.statistics();
            assertThat(statistics.moves()).isEqualTo(3);
            assertThat(statistics.blunders()).isEqualTo(1);
            assertThat(statistics.averageLoss()).isEqualTo(16.7);
            assertThat(statistics.averageCentipawnLoss()).isEqualTo(333.3);
            assertThat(statistics.byPhase()).containsOnlyKeys("opening", "middlegame", "endgame");
            assertThat(statistics.byPhase().get("opening"))
                    .isEqualTo(new GameReviews.PhaseStatistics(3, 0, 0, 1, 16.7, 333.3));
            assertThat(statistics.byPhase().get("endgame").moves()).isZero();
        }
    }

    @Test
    void showsAnUnanalyzedGameWithInferredPhasesAndNoSelectedSide(@TempDir Path directory) {
        try (var application = start(directory)) {
            application.getBean(GameCatalogStore.class).save(ACCOUNT, List.of(game(GAME_ID, true)), STARTED);

            GameReviews.GameReview review = reviews(application).review(ACCOUNT, GAME_ID, null).orElseThrow();

            assertThat(review.plies()).hasSize(7);
            assertThat(review.myMoves()).isNull();
            assertThat(review.opponentMoves()).isNull();
            assertThat(review.selected()).isNull();
            assertThat(review.phases().source()).isEqualTo("inferred");
            assertThat(reviews(application).review(ACCOUNT, GAME_ID, GameReviews.Side.MY_MOVES)
                    .orElseThrow().selected()).isNull();
        }
    }

    @Test
    void neverReviewsAnotherAccountsGameOrReplaysAnUnsupportedVariant(@TempDir Path directory) {
        try (var application = start(directory)) {
            application.getBean(GameCatalogStore.class).save(
                    ACCOUNT, List.of(game(GAME_ID, true), game("variant1", false)), STARTED);

            assertThat(reviews(application).review("someone", GAME_ID, null)).isEmpty();
            assertThat(reviews(application).review(ACCOUNT, "missing1", null)).isEmpty();
            GameReviews.GameReview variant = reviews(application).review(ACCOUNT, "variant1", null).orElseThrow();
            assertThat(variant.plies()).isEmpty();
            assertThat(variant.phases()).isNull();
            assertThat(variant.selected()).isNull();
        }
    }

    @Test
    void reportsCorruptStoredEvidenceAsInvalidInsteadOfReturningIt(@TempDir Path directory) {
        for (String lines : List.of(
                "[]",
                "not json",
                "{\"move\":\"e2e4\"}",
                "[{\"move\":\"e2e4\",\"cp\":10,\"wdl\":[500,500,1],\"pv\":[\"e2e4\"]}]",
                "[{\"move\":\"e2e4\",\"cp\":\"10\",\"wdl\":[0,1000,0],\"pv\":[\"e2e4\"]}]",
                "[{\"move\":\"e2e4\",\"cp\":10,\"mate\":3,\"wdl\":[0,1000,0],\"pv\":[\"e2e4\"]}]",
                "[{\"move\":\"e2e4\",\"cp\":10,\"wdl\":[0,1000,0],\"pv\":[\"d2d4\"]}]",
                "[{\"move\":\"e2e4\",\"cp\":10,\"wdl\":[0,1000,0],\"pv\":[\"e2e4\",\"x;quit\"]}]")) {
            Path run = directory.resolve(Integer.toString(lines.hashCode()));
            try (var application = start(run)) {
                insertRawMove(application, lines, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");

                assertThatThrownBy(() -> reviews(application).review(ACCOUNT, GAME_ID, null))
                        .as(lines)
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void reportsAStoredMoveThatDoesNotReplayAsInvalid(@TempDir Path directory) {
        try (var application = start(directory)) {
            insertRawMove(application,
                    "[{\"move\":\"e2e4\",\"cp\":10,\"wdl\":[0,1000,0],\"pv\":[\"e2e4\"]}]",
                    "8/8/8/8/8/8/8/8 w - - 0 1 not-a-position");

            assertThatThrownBy(() -> reviews(application).review(ACCOUNT, GAME_ID, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** Writes one owner version and move directly, bypassing the store's own validation. */
    private static void insertRawMove(ConfigurableApplicationContext application, String beforeLines, String fen) {
        application.getBean(GameCatalogStore.class).save(ACCOUNT, List.of(game(GAME_ID, true)), STARTED);
        long profileId = application.getBean(EngineProfileStore.class).save(new EngineProfile(
                0, "Stockfish 19", 2, 256, 3, 6, 1_000_000, 1_180_000, 750, 3_000,
                880_000, 3_540_000, STARTED)).id();
        var jdbc = application.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("""
                INSERT INTO game_review_analysis_version (
                    account_id, lichess_game_id, side, requested_perspective, analyzed_color,
                    profile_id, rules_version, phase_source, middlegame_start, move_count,
                    deep_move_count, inaccuracies, mistakes, blunders, total_loss,
                    started_at_ms, completed_at_ms
                ) VALUES (?, ?, 'owner', 'my_moves', 'white', ?, 1, 'inferred', 20, 1, 0, 0, 0, 0, 0, 0, 0)
                """, ACCOUNT, GAME_ID, profileId);
        long versionId = jdbc.queryForObject("SELECT MAX(id) FROM game_review_analysis_version", Long.class);
        jdbc.update("""
                INSERT INTO game_review_analysis_move (
                    version_id, ply, san, uci, fen_before, phase, pass, label, mate_event,
                    played_best, best_move, expected_before, expected_after, loss, after_terminal,
                    before_lines_json, after_lines_json
                ) VALUES (?, 1, 'e4', 'e2e4', ?, 'opening', 'quick', 'none', 'none', 0, 'd2d4',
                          1000, 1000, 0, 'none', ?, ?)
                """, versionId, fen, beforeLines,
                "[{\"move\":\"e7e5\",\"cp\":0,\"wdl\":[0,1000,0],\"pv\":[\"e7e5\"]}]");
    }

    private static GameReviews reviews(ConfigurableApplicationContext application) {
        return application.getBean(GameReviews.class);
    }

    /** Scholar's mate, both sides analyzed by a scripted engine: Nf6 allows mate in one. */
    private static void storeAnalyzedGame(ConfigurableApplicationContext application) throws Exception {
        application.getBean(GameCatalogStore.class).save(ACCOUNT, List.of(game(GAME_ID, true)), STARTED);
        long profileId = application.getBean(EngineProfileStore.class).save(new EngineProfile(
                0, "Stockfish 19", 2, 256, 3, 6, 1_000_000, 1_180_000, 750, 3_000,
                880_000, 3_540_000, STARTED)).id();
        ReplayedGame game = ReplayedGame.fromSan(MOVES);
        GamePhases phases = GamePhases.of(null, game);
        Map<Integer, Evaluation> scripted = Map.of(
                5, line("d8e7", new Score.Centipawns(0), new Wdl(0, 1_000, 0), List.of("d8e7", "g1f3")),
                6, line("h5f7", new Score.Mate(1), new Wdl(1_000, 0, 0), List.of("h5f7")));
        Evaluation level = line("a2a3", new Score.Centipawns(0), new Wdl(0, 1_000, 0), List.of("a2a3"));
        List<TwoPassAnalysis.MoveFinding> findings = new TwoPassAnalysis(
                (position, nodes) -> scripted.getOrDefault(position.index(), level), 880_000, 3_540_000)
                .analyze(game, phases, Set.of(ReplayedGame.Color.WHITE, ReplayedGame.Color.BLACK));
        application.getBean(AnalysisVersionStore.class).saveAll(List.of(
                version(AnalysisVersionStore.Side.OWNER, CatalogGame.Color.WHITE, profileId, phases,
                        findings, ReplayedGame.Color.WHITE),
                version(AnalysisVersionStore.Side.OPPONENT, CatalogGame.Color.BLACK, profileId, phases,
                        findings, ReplayedGame.Color.BLACK)));
    }

    private static Evaluation line(String move, Score score, Wdl wdl, List<String> pv) {
        return new Evaluation(List.of(new Evaluation.Line(move, score, wdl, pv)));
    }

    private static AnalysisVersionStore.NewVersion version(
            AnalysisVersionStore.Side side,
            CatalogGame.Color color,
            long profileId,
            GamePhases phases,
            List<TwoPassAnalysis.MoveFinding> findings,
            ReplayedGame.Color mover) {
        return new AnalysisVersionStore.NewVersion(
                ACCOUNT, GAME_ID, side, GameAnalysis.Perspective.BOTH, color, profileId, 1, phases,
                findings.stream().filter(finding -> finding.move().mover() == mover).toList(),
                STARTED, COMPLETED);
    }

    private static CatalogGame game(String id, boolean standard) {
        return new CatalogGame(
                id,
                Instant.parse("2026-11-13T10:00:00Z"),
                Instant.parse("2026-11-13T10:05:00Z"),
                CatalogGame.Status.MATE,
                CatalogGame.Result.WIN,
                CatalogGame.Color.WHITE,
                1500,
                null,
                new CatalogGame.Opponent(CatalogGame.OpponentKind.USER, "rival", "Rival", 1480, null, null),
                CatalogGame.Speed.BLITZ,
                true,
                standard ? CatalogGame.Variant.STANDARD : CatalogGame.Variant.CHESS960,
                new CatalogGame.TimeControl(300, 0, null),
                null,
                null,
                MOVES,
                null,
                List.of(),
                standard);
    }

    private static ConfigurableApplicationContext start(Path directory) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + directory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }
}
