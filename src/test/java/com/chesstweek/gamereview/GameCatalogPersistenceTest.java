package com.chesstweek.gamereview;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.chesstweek.ChessTweekApplication;

import static org.assertj.core.api.Assertions.assertThat;

class GameCatalogPersistenceTest {

    @Test
    void catalogsValidGamesWithoutDuplicatesAndSurvivesRestart(
            @TempDir Path temporaryDirectory) {
        Path databasePath = temporaryDirectory.resolve("runtime/chess-tweek.db");

        try (var application = start(databasePath)) {
            GameRetrieval retrieval = application.getBean(GameRetrieval.class);
            GameCatalog catalog = application.getBean(GameCatalog.class);

            GameRetrievalResult first = retrieval.retrieve(
                    "liu_private_token_123", "ownerid", "ChessOwner", 3, null);
            GameRetrievalResult repeated = retrieval.retrieve(
                    "liu_private_token_123", "ownerid", "ChessOwner", 3, null);

            assertThat(first.status()).isEqualTo(GameRetrievalResult.Status.SUCCESS);
            assertThat(first.received()).isEqualTo(2);
            assertThat(first.rejected()).isEqualTo(1);
            assertThat(repeated.received()).isEqualTo(2);
            assertThat(catalog.games("ownerid"))
                    .extracting(CatalogGame::id)
                    .containsExactly("game0003", "game0001");
            assertThat(catalog.games("another-account")).isEmpty();
        }

        try (var restarted = start(databasePath)) {
            List<CatalogGame> games = restarted.getBean(GameCatalog.class).games("ownerid");

            assertThat(games).hasSize(2);
            CatalogGame standard = games.stream()
                    .filter(game -> game.id().equals("game0001"))
                    .findFirst()
                    .orElseThrow();
            assertThat(standard.ownerResult()).isEqualTo(CatalogGame.Result.WIN);
            assertThat(standard.ownerColor()).isEqualTo(CatalogGame.Color.WHITE);
            assertThat(standard.opponent().name()).isEqualTo("Opponent");
            assertThat(standard.opening().eco()).isEqualTo("C20");
            assertThat(standard.analysisEligible()).isTrue();

            CatalogGame variant = games.stream()
                    .filter(game -> game.id().equals("game0003"))
                    .findFirst()
                    .orElseThrow();
            assertThat(variant.variant()).isEqualTo(CatalogGame.Variant.CRAZYHOUSE);
            assertThat(variant.analysisEligible()).isFalse();
        }
    }

    private ConfigurableApplicationContext start(Path databasePath) {
        return new SpringApplicationBuilder(
                        ChessTweekApplication.class, FakeLichessConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--chess-tweek.database.path=" + databasePath,
                        "--logging.level.root=OFF");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeLichessConfiguration {

        @Bean
        @Primary
        LichessGameClient fakeLichessGameClient() {
            return (accessToken, username, count, before) -> new GameExportBatch(List.of(
                    new ExportedGame("game0001", "mate", standardGame("game0001", VALID_MOVES)),
                    new ExportedGame("game0002", "mate", standardGame("game0002", "e4 e5 e4")),
                    new ExportedGame("game0003", "mate", variantGame())));
        }

        private static String standardGame(String id, String moves) {
            return """
                    {
                      "id":"%s",
                      "rated":true,
                      "variant":"standard",
                      "speed":"blitz",
                      "perf":"blitz",
                      "createdAt":1794355200000,
                      "lastMoveAt":1794355500000,
                      "status":"mate",
                      "winner":"white",
                      "players":{
                        "white":{"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800},
                        "black":{"user":{"id":"opponent","name":"Opponent"},"rating":1700}
                      },
                      "clock":{"initial":300,"increment":3,"totalTime":420},
                      "clocks":[3000,3000,2970,2990],
                      "opening":{"eco":"C20","name":"King's Pawn Game","ply":2},
                      "division":{"middle":4,"end":6},
                      "moves":"%s",
                      "pgn":"[Result \\\"1-0\\\"]\\n\\n%s 1-0"
                    }
                    """.formatted(id, moves, moves);
        }

        private static String variantGame() {
            return """
                    {
                      "id":"game0003",
                      "rated":false,
                      "variant":"crazyhouse",
                      "speed":"rapid",
                      "perf":"crazyhouse",
                      "createdAt":1794441600000,
                      "lastMoveAt":1794441900000,
                      "status":"mate",
                      "winner":"black",
                      "players":{
                        "white":{"user":{"id":"opponent2","name":"VariantPlayer"},"rating":1600},
                        "black":{"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800}
                      },
                      "clock":{"initial":600,"increment":5,"totalTime":900},
                      "moves":"e4 e5",
                      "pgn":"[Variant \\\"Crazyhouse\\\"]\\n\\n1. e4 e5 0-1"
                    }
                    """;
        }

        private static final String VALID_MOVES = "e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#";
    }
}
