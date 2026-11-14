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
import org.springframework.jdbc.core.JdbcTemplate;

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
import com.chesstweek.gamereview.internal.persistence.GameLookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisVersionPersistenceTest {

    private static final String ACCOUNT = "owner";
    private static final String GAME_ID = "abcd1234";
    private static final String MOVES = "e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#";
    private static final Instant STARTED = Instant.parse("2026-11-14T12:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-11-14T12:01:30Z");

    @Test
    void storesSeparateImmutableVersionsWithEveryJudgedMove(@TempDir Path directory)
            throws Exception {
        try (var application = start(directory)) {
            var jdbc = application.getBean(JdbcTemplate.class);
            long profileId = storeGameAndProfile(application);
            List<TwoPassAnalysis.MoveFinding> findings = findings();

            List<Long> ids = application.getBean(AnalysisVersionStore.class).saveAll(List.of(
                    version(AnalysisVersionStore.Side.OWNER, CatalogGame.Color.WHITE, profileId,
                            findings, ReplayedGame.Color.WHITE),
                    version(AnalysisVersionStore.Side.OPPONENT, CatalogGame.Color.BLACK, profileId,
                            findings, ReplayedGame.Color.BLACK)));

            assertThat(ids).hasSize(2);
            Map<String, Object> owner = jdbc.queryForMap(
                    "SELECT * FROM game_review_analysis_version WHERE id = ?", ids.get(0));
            assertThat(owner).containsEntry("side", "owner")
                    .containsEntry("requested_perspective", "both")
                    .containsEntry("analyzed_color", "white")
                    .containsEntry("phase_source", "inferred")
                    .containsEntry("move_count", 4)
                    .containsEntry("rules_version", 1)
                    .containsEntry("started_at_ms", STARTED.toEpochMilli())
                    .containsEntry("completed_at_ms", COMPLETED.toEpochMilli());

            assertThat(jdbc.queryForList(
                    "SELECT ply FROM game_review_analysis_move WHERE version_id = ? ORDER BY ply",
                    Integer.class, ids.get(0))).containsExactly(1, 3, 5, 7);
            assertThat(jdbc.queryForList(
                    "SELECT ply FROM game_review_analysis_move WHERE version_id = ? ORDER BY ply",
                    Integer.class, ids.get(1))).containsExactly(2, 4, 6);

            Map<String, Object> mate = jdbc.queryForMap(
                    "SELECT * FROM game_review_analysis_move WHERE version_id = ? AND ply = 7",
                    ids.get(0));
            assertThat(mate).containsEntry("san", "Qxf7#")
                    .containsEntry("uci", "h5f7")
                    .containsEntry("after_terminal", "checkmate")
                    // The fake saw no mate before the move, so mating changed the mate state.
                    .containsEntry("pass", "deep")
                    .containsEntry("label", "none");
            assertThat(mate.get("after_lines_json")).isNull();
            assertThat((String) mate.get("before_lines_json"))
                    .isEqualTo("[{\"move\":\"a2a3\",\"cp\":0,\"wdl\":[0,1000,0],\"pv\":[\"a2a3\"]}]");
            assertThat((String) mate.get("fen_before")).startsWith("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/");

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE game_review_analysis_version SET blunders = 1 WHERE id = ?", ids.get(0)))
                    .hasMessageContaining("analysis versions are immutable");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE game_review_analysis_move SET label = 'blunder' WHERE version_id = ?",
                    ids.get(0)))
                    .hasMessageContaining("analysis moves are immutable");
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO game_review_analysis_version (
                        account_id, lichess_game_id, side, requested_perspective, analyzed_color,
                        profile_id, rules_version, phase_source, middlegame_start, move_count,
                        deep_move_count, inaccuracies, mistakes, blunders, total_loss,
                        started_at_ms, completed_at_ms
                    ) VALUES ('owner', 'abcd1234', 'owner', 'opponent_moves', 'white', ?, 1,
                              'inferred', 20, 0, 0, 0, 0, 0, 0, 0, 0)
                    """, profileId))
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM game_review_game WHERE lichess_game_id = ?", GAME_ID))
                    .hasMessageContaining("FOREIGN KEY constraint failed");
        }
    }

    @Test
    void storesNothingWhenAnyVersionFails(@TempDir Path directory) throws Exception {
        try (var application = start(directory)) {
            long profileId = storeGameAndProfile(application);
            List<TwoPassAnalysis.MoveFinding> findings = findings();
            var store = application.getBean(AnalysisVersionStore.class);

            assertThatThrownBy(() -> store.saveAll(List.of(
                    version(AnalysisVersionStore.Side.OWNER, CatalogGame.Color.WHITE, profileId,
                            findings, ReplayedGame.Color.WHITE),
                    version(AnalysisVersionStore.Side.OPPONENT, CatalogGame.Color.BLACK,
                            profileId + 100, findings, ReplayedGame.Color.BLACK))))
                    .isInstanceOf(RuntimeException.class);

            var jdbc = application.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM game_review_analysis_version", Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM game_review_analysis_move", Integer.class)).isZero();
        }
    }

    @Test
    void summarizesTheNewestVersionOfEachSidePerGame(@TempDir Path directory) throws Exception {
        try (var application = start(directory)) {
            long profileId = storeGameAndProfile(application);
            List<TwoPassAnalysis.MoveFinding> findings = findings();
            var store = application.getBean(AnalysisVersionStore.class);
            var summaries = application.getBean(GameAnalysisSummaries.class);
            assertThat(summaries.latest(ACCOUNT, List.of(GAME_ID))).isEmpty();

            store.saveAll(List.of(version(AnalysisVersionStore.Side.OWNER, CatalogGame.Color.WHITE,
                    profileId, findings, ReplayedGame.Color.WHITE)));
            List<Long> newer = store.saveAll(List.of(version(AnalysisVersionStore.Side.OWNER,
                    CatalogGame.Color.WHITE, profileId, findings, ReplayedGame.Color.WHITE)));

            store.saveAll(List.of(version(AnalysisVersionStore.Side.OPPONENT, CatalogGame.Color.BLACK,
                    profileId, findings, ReplayedGame.Color.BLACK)));
            application.getBean(GameCatalogStore.class).save(
                    ACCOUNT, List.of(game("efgh5678")), STARTED);

            Map<String, GameAnalysisSummaries.Summary> latest =
                    summaries.latest(ACCOUNT, List.of(GAME_ID, "efgh5678"));
            assertThat(latest).containsOnlyKeys(GAME_ID);
            GameAnalysisSummaries.Summary summary = latest.get(GAME_ID);
            assertThat(summary.myMoves().id()).isEqualTo(newer.getFirst());
            assertThat(summary.myMoves().moves()).isEqualTo(4);
            assertThat(summary.myMoves().completedAt()).isEqualTo(COMPLETED);
            assertThat(summary.myMoves().currentProfile()).isTrue();
            assertThat(summary.opponentMoves().moves()).isEqualTo(3);

            application.getBean(EngineProfileStore.class).save(new EngineProfile(
                    0, "Stockfish 19", 2, 256, 3, 6, 1_000_000, 1_200_000, 750, 3_000,
                    900_000, 3_600_000, COMPLETED));
            assertThat(summaries.latest(ACCOUNT, List.of(GAME_ID)).get(GAME_ID).myMoves()
                    .currentProfile()).isFalse();
            assertThat(summaries.latest("someone", List.of(GAME_ID))).isEmpty();
            assertThatThrownBy(() -> summaries.latest(ACCOUNT, List.of("bad id")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void findsOnlyTheAccountsOwnGameByAValidId(@TempDir Path directory) throws Exception {
        try (var application = start(directory)) {
            storeGameAndProfile(application);
            var lookup = application.getBean(GameLookup.class);

            assertThat(lookup.find(ACCOUNT, GAME_ID)).get()
                    .extracting(CatalogGame::movesSan).isEqualTo(MOVES);
            assertThat(lookup.find("someone", GAME_ID)).isEmpty();
            assertThat(lookup.find(ACCOUNT, "abcd1234' OR '1'='1")).isEmpty();
            assertThat(lookup.find(ACCOUNT, "zzzz9999")).isEmpty();
        }
    }

    private static long storeGameAndProfile(ConfigurableApplicationContext application) {
        application.getBean(GameCatalogStore.class).save(ACCOUNT, List.of(game()), STARTED);
        return application.getBean(EngineProfileStore.class).save(new EngineProfile(
                0, "Stockfish 19", 2, 256, 3, 6, 1_000_000, 1_180_000, 750, 3_000,
                880_000, 3_540_000, STARTED)).id();
    }

    private static List<TwoPassAnalysis.MoveFinding> findings() throws Exception {
        ReplayedGame game = ReplayedGame.fromSan(MOVES);
        Evaluation level = new Evaluation(List.of(new Evaluation.Line(
                "a2a3", new Score.Centipawns(0), new Wdl(0, 1_000, 0), List.of("a2a3"))));
        return new TwoPassAnalysis((position, nodes) -> level, 880_000, 3_540_000)
                .analyze(game, GamePhases.of(null, game),
                        Set.of(ReplayedGame.Color.WHITE, ReplayedGame.Color.BLACK));
    }

    private static AnalysisVersionStore.NewVersion version(
            AnalysisVersionStore.Side side,
            CatalogGame.Color color,
            long profileId,
            List<TwoPassAnalysis.MoveFinding> findings,
            ReplayedGame.Color mover) {
        ReplayedGame game = ReplayedGame.fromSan(MOVES);
        return new AnalysisVersionStore.NewVersion(
                ACCOUNT, GAME_ID, side, GameAnalysis.Perspective.BOTH, color, profileId, 1,
                GamePhases.of(null, game),
                findings.stream().filter(finding -> finding.move().mover() == mover).toList(),
                STARTED, COMPLETED);
    }

    private static CatalogGame game() {
        return game(GAME_ID);
    }

    private static CatalogGame game(String id) {
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
                CatalogGame.Variant.STANDARD,
                new CatalogGame.TimeControl(300, 0, null),
                null,
                null,
                MOVES,
                null,
                List.of(),
                true);
    }

    private static ConfigurableApplicationContext start(Path directory) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + directory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }
}
