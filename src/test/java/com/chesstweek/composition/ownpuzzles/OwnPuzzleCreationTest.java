package com.chesstweek.composition.ownpuzzles;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.gamereview.AnalysisCompletionListener;
import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameAnalysis.Perspective;
import com.chesstweek.gamereview.GameRef;
import com.chesstweek.gamereview.GameReviews;
import com.chesstweek.gamereview.GameReviews.JudgedMove;
import com.chesstweek.training.OwnPuzzles;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.notation.NotationType;

import static org.assertj.core.api.Assertions.assertThat;

class OwnPuzzleCreationTest {

    private static final String OWNER = "owner";
    private static final GameRef GAME = GameRef.lichess("abcd1234");
    private static final Instant PLAYED = Instant.parse("2026-11-20T18:30:00Z");
    /** 1.e4 e5 2.Nf3 Nc6 3.Bc4 Nf6; the owner plays White. */
    private static final List<String> MOVES =
            List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6");

    @TempDir
    Path directory;

    private final FakeReviews reviews = new FakeReviews();

    @Test
    void aCompletedMyMovesAnalysisCreatesOnePuzzlePerLabelledOwnerMove() {
        try (var application = start()) {
            creation(application).completed(OWNER, GAME, Perspective.MY_MOVES);

            List<Map<String, Object>> rows = jdbc(application).queryForList(
                    "SELECT * FROM training_own_puzzle ORDER BY source_ply");
            assertThat(rows).extracting(row -> row.get("source_ply")).containsExactly(3, 5);

            Map<String, Object> nf3 = rows.get(0);
            assertThat(nf3).containsEntry("account_id", OWNER)
                    .containsEntry("game_source", "lichess")
                    .containsEntry("game_id", "abcd1234")
                    .containsEntry("analysis_version_id", 11)
                    .containsEntry("position_fen", fenBefore(3))
                    .containsEntry("previous_move_uci", "e7e5")
                    .containsEntry("solution_uci", "d2d4 e5d4 g1f3")
                    .containsEntry("label", "inaccuracy")
                    .containsEntry("phase", "opening")
                    .containsEntry("opening_eco", "C50")
                    .containsEntry("opening_name", "Italian Game")
                    .containsEntry("game_played_at_ms", PLAYED.toEpochMilli());

            Map<String, Object> bc4 = rows.get(1);
            assertThat(bc4).containsEntry("previous_move_uci", "b8c6")
                    .containsEntry("solution_uci", "f1b5")
                    .containsEntry("label", "blunder")
                    .containsEntry("phase", "middlegame");
            assertThat(jdbc(application).queryForList("""
                    SELECT p.source_ply || '/' || t.theme || '/' || t.kind
                    FROM training_own_puzzle_tactic t
                    JOIN training_own_puzzle p ON p.id = t.own_puzzle_id
                    """, String.class)).containsExactly("5/fork/missed");
        }
    }

    @Test
    void hearingAboutTheSameAnalysisAgainCreatesNothingNew() {
        try (var application = start()) {
            OwnPuzzleCreation creation = creation(application);

            creation.completed(OWNER, GAME, Perspective.MY_MOVES);
            creation.completed(OWNER, GAME, Perspective.MY_MOVES);

            assertThat(count(application)).isEqualTo(2);
        }
    }

    @Test
    void opponentMovesNeverCreateOwnPuzzles() {
        try (var application = start()) {
            creation(application).completed(OWNER, GAME, Perspective.OPPONENT_MOVES);

            assertThat(reviews.requested).isEmpty();
            assertThat(count(application)).isZero();
        }
    }

    @Test
    void bothSidesCreatePuzzlesOnlyFromTheOwnersMoves() {
        try (var application = start()) {
            creation(application).completed(OWNER, GAME, Perspective.BOTH);

            assertThat(reviews.requested).containsExactly(GameReviews.Side.MY_MOVES);
            assertThat(jdbc(application).queryForList(
                    "SELECT source_ply FROM training_own_puzzle ORDER BY source_ply", Integer.class))
                    .containsExactly(3, 5);
        }
    }

    @Test
    void aGameWithoutAStoredOwnerAnalysisCreatesNothing() {
        try (var application = start()) {
            reviews.ownerAnalyzed = false;

            creation(application).completed(OWNER, GAME, Perspective.MY_MOVES);

            assertThat(count(application)).isZero();
        }
    }

    @Test
    void theApplicationListensForCompletedAnalyses() {
        try (var application = start()) {
            assertThat(application.getBeansOfType(AnalysisCompletionListener.class).values())
                    .hasOnlyElementsOfType(OwnPuzzleCreation.class)
                    .hasSize(1);
        }
    }

    @Test
    void theStartupRefreshReplacesOutdatedTacticLabelsFromTheStoredAnalysis() {
        try (var application = start()) {
            creation(application).completed(OWNER, GAME, Perspective.MY_MOVES);
            List<Long> ids = jdbc(application).queryForList(
                    "SELECT id FROM training_own_puzzle ORDER BY id", Long.class);
            reviews.blunderTactics = List.of(
                    new GameReviews.Tactic("pin", "missed", "bishop", "b5", List.of("c6", "e8"), 0),
                    new GameReviews.Tactic("removing_defender", "allowed", "knight", "c6", List.of("e5"), 2));

            assertThat(refresh(application).refresh().changed()).isEqualTo(1);

            assertThat(tactics(application)).containsExactly("5/pin/missed", "5/removing_defender/allowed");
            assertThat(jdbc(application).queryForList(
                    "SELECT id FROM training_own_puzzle ORDER BY id", Long.class)).isEqualTo(ids);
            // Up-to-date labels are left alone.
            assertThat(refresh(application).refresh().changed()).isZero();
        }
    }

    @Test
    void theStartupRefreshCanRemoveEveryLabel() {
        try (var application = start()) {
            creation(application).completed(OWNER, GAME, Perspective.MY_MOVES);
            reviews.blunderTactics = List.of();

            assertThat(refresh(application).refresh().changed()).isEqualTo(1);

            assertThat(tactics(application)).isEmpty();
        }
    }

    @Test
    void theStartupRefreshKeepsLabelsWhenTheAnalysisIsNotTheOneThePuzzleCameFrom() {
        try (var application = start()) {
            creation(application).completed(OWNER, GAME, Perspective.MY_MOVES);
            reviews.blunderTactics = List.of(new GameReviews.Tactic("pin", "missed", "bishop", "b5", List.of(), 0));
            reviews.ownerVersion = 12;

            assertThat(refresh(application).refresh().changed()).isZero();
            reviews.ownerVersion = 11;
            reviews.ownerAnalyzed = false;
            assertThat(refresh(application).refresh().changed()).isZero();

            assertThat(tactics(application)).containsExactly("5/fork/missed");
        }
    }

    @Test
    void aFailingRefreshNeverStopsTheApplicationStarting() {
        try (var application = start()) {
            creation(application).completed(OWNER, GAME, Perspective.MY_MOVES);
            GameReviews failing = (account, game, side) -> {
                throw new IllegalStateException("Stored move does not replay");
            };
            OwnPuzzles broken = new OwnPuzzles() {
                @Override
                public int create(String accountId, List<OwnPuzzles.NewOwnPuzzle> puzzles) {
                    return 0;
                }

                @Override
                public List<StoredLabels> labels() {
                    throw new IllegalStateException("database unavailable");
                }

                @Override
                public boolean replaceTactics(PuzzleSource source, List<OwnPuzzles.Tactic> tactics) {
                    return false;
                }

                @Override
                public int labelRulesVersion() {
                    return 0;
                }

                @Override
                public void recordLabelRulesVersion(int version) {}
            };

            // One unreadable game is skipped; an unreadable store is logged, not thrown.
            assertThat(new OwnPuzzleTacticRefresh(failing, application.getBean(OwnPuzzles.class)).refresh())
                    .isEqualTo(new OwnPuzzleTacticRefresh.Result(0, 1));
            new OwnPuzzleTacticRefresh(reviews, broken).run(null);
            assertThat(tactics(application)).containsExactly("5/fork/missed");
        }
    }

    @Test
    void startupRefreshesOnceForEachRulesVersionAndThenSkips() {
        try (var application = start()) {
            OwnPuzzles puzzles = application.getBean(OwnPuzzles.class);
            assertThat(puzzles.labelRulesVersion()).isEqualTo(GameReviews.TACTIC_RULES_VERSION);
            creation(application).completed(OWNER, GAME, Perspective.MY_MOVES);
            reviews.blunderTactics = List.of(new GameReviews.Tactic("pin", "missed", "bishop", "b5", List.of(), 0));
            reviews.requested.clear();

            // Labels already match the current rules version: nothing is read or changed.
            refresh(application).run(null);
            assertThat(reviews.requested).isEmpty();
            assertThat(tactics(application)).containsExactly("5/fork/missed");

            // An older recorded version is refreshed once, then recorded.
            puzzles.recordLabelRulesVersion(GameReviews.TACTIC_RULES_VERSION - 1);
            refresh(application).run(null);
            assertThat(tactics(application)).containsExactly("5/pin/missed");
            assertThat(puzzles.labelRulesVersion()).isEqualTo(GameReviews.TACTIC_RULES_VERSION);
            reviews.requested.clear();
            refresh(application).run(null);
            assertThat(reviews.requested).isEmpty();
        }
    }

    @Test
    void aGameThatFailsKeepsTheOldVersionSoTheNextStartRetries() {
        try (var application = start()) {
            OwnPuzzles puzzles = application.getBean(OwnPuzzles.class);
            creation(application).completed(OWNER, GAME, Perspective.MY_MOVES);
            puzzles.recordLabelRulesVersion(GameReviews.TACTIC_RULES_VERSION - 1);
            GameReviews failing = (account, game, side) -> {
                throw new IllegalStateException("Stored move does not replay");
            };

            new OwnPuzzleTacticRefresh(failing, puzzles).run(null);

            assertThat(puzzles.labelRulesVersion()).isEqualTo(GameReviews.TACTIC_RULES_VERSION - 1);
        }
    }

    @Test
    void theApplicationRefreshesLabelsAtStartup() {
        try (var application = start()) {
            assertThat(application.getBeansOfType(OwnPuzzleTacticRefresh.class)).hasSize(1);
        }
    }

    private OwnPuzzleTacticRefresh refresh(ConfigurableApplicationContext application) {
        return new OwnPuzzleTacticRefresh(reviews, application.getBean(OwnPuzzles.class));
    }

    private static List<String> tactics(ConfigurableApplicationContext application) {
        return jdbc(application).queryForList("""
                SELECT p.source_ply || '/' || t.theme || '/' || t.kind
                FROM training_own_puzzle_tactic t
                JOIN training_own_puzzle p ON p.id = t.own_puzzle_id
                ORDER BY p.source_ply, t.theme, t.kind
                """, String.class);
    }

    private OwnPuzzleCreation creation(ConfigurableApplicationContext application) {
        return new OwnPuzzleCreation(reviews, application.getBean(OwnPuzzles.class));
    }

    /** Serves My Moves with two labelled owner moves and Opponent Moves with one labelled move. */
    private static final class FakeReviews implements GameReviews {

        final List<Side> requested = new ArrayList<>();
        boolean ownerAnalyzed = true;
        long ownerVersion = 11;
        List<GameReviews.Tactic> blunderTactics = List.of(new GameReviews.Tactic("fork", "missed", "B", "b5", List.of()));

        @Override
        public Optional<GameReview> review(String accountId, GameRef game, Side side) {
            requested.add(side);
            if (!OWNER.equals(accountId) || !GAME.equals(game)) {
                return Optional.empty();
            }
            SideReview selected = null;
            if (side == Side.MY_MOVES && ownerAnalyzed) {
                selected = sideReview(side, ownerVersion, List.of(
                        judged(1, "none", "opening", List.of("e2e4", "e7e5", "g1f3"), List.of()),
                        judged(3, "inaccuracy", "opening",
                                List.of("d2d4", "e5d4", "g1f3", "g8f6"), List.of()),
                        judged(5, "blunder", "middlegame", List.of("f1b5", "a7a6"), blunderTactics)));
            } else if (side == Side.OPPONENT_MOVES) {
                selected = sideReview(side, 12, List.of(
                        judged(4, "blunder", "opening", List.of("g8f6", "d2d4", "e5d4"),
                                List.of())));
            }
            return Optional.of(new GameReview(
                    catalogGame(), fenBefore(1), plies(), null, null, null, selected));
        }
    }

    private static GameReviews.SideReview sideReview(
            GameReviews.Side side, long versionId, List<JudgedMove> moves) {
        return new GameReviews.SideReview(side, versionId, "white", "Stockfish 19", 1, 1,
                PLAYED, true, null, List.of(), moves);
    }

    private static JudgedMove judged(
            int ply, String label, String phase, List<String> bestLine,
            List<GameReviews.Tactic> tactics) {
        var line = new GameReviews.Line(bestLine.getFirst(), bestLine.getFirst(), 30, null, 100,
                800, 100, bestLine, bestLine);
        return new JudgedMove(ply, MOVES.get(ply - 1), MOVES.get(ply - 1), fenBefore(ply), phase,
                "deep", label, "none", label.equals("none"), bestLine.getFirst(),
                bestLine.getFirst(), 1000, 900, 100, "none", List.of(line), List.of(), tactics, null, null);
    }

    private static List<GameReviews.Ply> plies() {
        var plies = new ArrayList<GameReviews.Ply>();
        for (int ply = 1; ply <= MOVES.size(); ply++) {
            plies.add(new GameReviews.Ply(ply, ply % 2 == 1 ? "white" : "black",
                    MOVES.get(ply - 1), MOVES.get(ply - 1), fenBefore(ply + 1)));
        }
        return plies;
    }

    /** The position move {@code ply} was played from, as the game review reports it. */
    private static String fenBefore(int ply) {
        var game = new ChessGame();
        game.playMoves(NotationType.UCI, MOVES.subList(0, ply - 1));
        return game.getFen();
    }

    private static CatalogGame catalogGame() {
        return new CatalogGame(
                GAME, PLAYED, PLAYED.plusSeconds(600), CatalogGame.Status.RESIGN,
                CatalogGame.Result.LOSS, CatalogGame.Color.WHITE, 1500, null,
                new CatalogGame.Opponent(CatalogGame.OpponentKind.USER, "rival", "Rival", 1480,
                        null, null),
                CatalogGame.Speed.BLITZ, true, CatalogGame.Variant.STANDARD,
                new CatalogGame.TimeControl(300, 0, null),
                new CatalogGame.Opening("C50", "Italian Game", 5), null,
                "e4 e5 Nf3 Nc6 Bc4 Nf6", null, List.of(), true);
    }

    private static int count(ConfigurableApplicationContext application) {
        return jdbc(application).queryForObject(
                "SELECT COUNT(*) FROM training_own_puzzle", Integer.class);
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext application) {
        return application.getBean(JdbcTemplate.class);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + directory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }
}
