package com.chesstweek.gamereview;

import java.nio.file.Path;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.notation.NotationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.shared.chess.ExplorerPosition;

import static org.assertj.core.api.Assertions.assertThat;

class MyGamesExplorerPersistenceTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    void countsTheOwnersGamesThroughAPositionIncludingTranspositions(@TempDir Path directory) {
        try (var application = start(directory)) {
            var jdbc = application.getBean(JdbcTemplate.class);
            var explorer = application.getBean(MyGamesExplorer.class);
            insert(jdbc, "owner", "lichess", "game0001", 1_000, "white", "win", "rapid", "standard",
                    "d4 Nf6 c4 e6 Nc3 Bb4");
            insert(jdbc, "owner", "chesscom", "101", 2_000, "black", "win", "blitz", "standard",
                    "c4 e6 d4 Nf6 Nf3 d5");
            insert(jdbc, "owner", "lichess", "game0002", 3_000, "white", "draw", "rapid", "standard",
                    "d4 d5 c4");
            insert(jdbc, "owner", "lichess", "game0003", 4_000, "white", "loss", "rapid", "chess960",
                    "d4 Nf6 c4 e6");
            insert(jdbc, "someone", "lichess", "game0004", 5_000, "white", "win", "rapid", "standard",
                    "d4 Nf6 c4 e6 g3");

            var statistics = explorer.statistics(
                    "owner", after("d4 Nf6 c4 e6"), MyGamesExplorer.Filter.any());

            // White won game0001; Black (the owner) won 101. Game0003 is not standard chess.
            assertThat(statistics.results()).isEqualTo(new MyGamesExplorer.Results(1, 0, 1));
            assertThat(statistics.moves()).extracting(MyGamesExplorer.NextMove::san)
                    .containsExactlyInAnyOrder("Nc3", "Nf3");
            assertThat(statistics.recentGames()).extracting(game -> game.ref().id())
                    .containsExactly("101", "game0001");
            assertThat(statistics.recentGames().getFirst().nextSan()).isEqualTo("Nf3");

            var fromStart = explorer.statistics(
                    "owner", ExplorerPosition.parse(START).orElseThrow(), MyGamesExplorer.Filter.any());
            assertThat(fromStart.results()).isEqualTo(new MyGamesExplorer.Results(1, 1, 1));
            assertThat(fromStart.moves()).first().satisfies(move -> {
                assertThat(move.san()).isEqualTo("d4");
                assertThat(move.results()).isEqualTo(new MyGamesExplorer.Results(1, 1, 0));
            });
        }
    }

    @Test
    void narrowsByColorSpeedAndSite(@TempDir Path directory) {
        try (var application = start(directory)) {
            var jdbc = application.getBean(JdbcTemplate.class);
            var explorer = application.getBean(MyGamesExplorer.class);
            insert(jdbc, "owner", "lichess", "game0001", 1_000, "white", "win", "rapid", "standard", "e4 c6");
            insert(jdbc, "owner", "chesscom", "101", 2_000, "black", "loss", "blitz", "standard", "e4 c6");
            ExplorerPosition position = after("e4");

            assertThat(explorer.statistics("owner", position, new MyGamesExplorer.Filter(
                            CatalogGame.Color.BLACK, null, null)).recentGames())
                    .extracting(game -> game.ref().id()).containsExactly("101");
            assertThat(explorer.statistics("owner", position, new MyGamesExplorer.Filter(
                            null, CatalogGame.Speed.RAPID, null)).recentGames())
                    .extracting(game -> game.ref().id()).containsExactly("game0001");
            assertThat(explorer.statistics("owner", position, new MyGamesExplorer.Filter(
                            null, null, GameRef.Source.CHESSCOM)).recentGames())
                    .extracting(game -> game.ref().id()).containsExactly("101");
        }
    }

    @Test
    void indexesGamesStoredAfterTheFirstRequestAndCountsARepeatedPositionOnce(
            @TempDir Path directory) {
        try (var application = start(directory)) {
            var jdbc = application.getBean(JdbcTemplate.class);
            var explorer = application.getBean(MyGamesExplorer.class);
            ExplorerPosition position = after("Nf3");
            insert(jdbc, "owner", "lichess", "game0001", 1_000, "white", "win", "rapid", "standard",
                    "Nf3 Nf6 Ng1 Ng8 Nf3 d5");
            assertThat(explorer.statistics("owner", position, MyGamesExplorer.Filter.any())
                            .moves())
                    .singleElement()
                    .satisfies(move -> assertThat(move.san()).isEqualTo("Nf6"));

            insert(jdbc, "owner", "lichess", "game0002", 2_000, "white", "draw", "rapid", "standard",
                    "Nf3 d5");
            insert(jdbc, "owner", "lichess", "game0003", 3_000, "white", "draw", "rapid", "standard",
                    "Nf3 Nf6 Nf6");

            var statistics = explorer.statistics("owner", position, MyGamesExplorer.Filter.any());
            assertThat(statistics.results()).isEqualTo(new MyGamesExplorer.Results(1, 1, 0));
            assertThat(statistics.moves()).extracting(MyGamesExplorer.NextMove::san)
                    .containsExactlyInAnyOrder("Nf6", "d5");
            assertThat(jdbc.queryForObject(
                    "SELECT indexed_plies FROM game_review_position_index_game WHERE game_id = 'game0003'",
                    Integer.class)).isZero();
        }
    }

    @Test
    void indexesAGameAgainWhenItsMovesChange(@TempDir Path directory) {
        try (var application = start(directory)) {
            var jdbc = application.getBean(JdbcTemplate.class);
            var explorer = application.getBean(MyGamesExplorer.class);
            ExplorerPosition position = after("e4");
            insert(jdbc, "owner", "lichess", "game0001", 1_000, "white", "win", "rapid", "standard", "e4 e5");
            assertThat(explorer.statistics("owner", position, MyGamesExplorer.Filter.any()).moves())
                    .extracting(MyGamesExplorer.NextMove::san).containsExactly("e5");

            jdbc.update("UPDATE game_review_game SET moves_san = 'e4 c5' WHERE game_id = 'game0001'");
            jdbc.update("UPDATE game_review_game SET owner_rating = 1500 WHERE game_id = 'game0001'");

            assertThat(explorer.statistics("owner", position, MyGamesExplorer.Filter.any()).moves())
                    .extracting(MyGamesExplorer.NextMove::san).containsExactly("c5");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM game_review_game_position WHERE game_id = 'game0001'",
                    Integer.class)).isEqualTo(3);
        }
    }

    private static ExplorerPosition after(String moves) {
        var game = new ChessGame();
        game.playMoves(NotationType.SAN, moves);
        return ExplorerPosition.of(game);
    }

    private static void insert(
            JdbcTemplate jdbc,
            String accountId,
            String source,
            String gameId,
            long playedAt,
            String ownerColor,
            String ownerResult,
            String speed,
            String variant,
            String moves) {
        jdbc.update("""
                INSERT INTO game_review_game (
                    account_id, source, game_id, played_at_ms, last_move_at_ms, status,
                    owner_result, owner_color, opponent_kind, opponent_user_id, opponent_name,
                    speed, rated, variant, moves_san, clocks_json, analysis_eligible,
                    retrieved_at_ms)
                VALUES (?, ?, ?, ?, ?, 'resign', ?, ?, 'user', 'rival', 'Rival', ?, 1, ?, ?, '[]', ?, 0)
                """,
                accountId, source, gameId, playedAt, playedAt + 600, ownerResult, ownerColor,
                speed, variant, moves, "standard".equals(variant) ? 1 : 0);
    }

    private static ConfigurableApplicationContext start(Path directory) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--chess-tweek.database.path=" + directory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }
}
