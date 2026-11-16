package com.chesstweek.gamereview;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.gamereview.internal.persistence.GameLookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChessComRetrievalPersistenceTest {

    @Test
    void storesTheUsernamePerAccountAndRetrievesWithoutDuplicates(@TempDir Path directory) {
        try (var application = start(directory.resolve("runtime/chess-tweek.db"))) {
            var players = application.getBean(ChessComPlayers.class);
            var retrieval = application.getBean(ChessComRetrieval.class);

            assertThat(players.username("owner-id")).isEmpty();
            players.save("owner-id", "OwnerName");
            players.save("owner-id", "ownername");
            players.save("other-id", "SomeoneElse");

            ChessComRetrievalResult first = retrieval.retrieve("owner-id", 5, null);
            ChessComRetrievalResult repeated = retrieval.retrieve("owner-id", 5, null);

            assertThat(players.username("owner-id")).contains("ownername");
            assertThat(players.username("other-id")).contains("SomeoneElse");
            assertThat(first.status()).isEqualTo(ChessComRetrievalResult.Status.SUCCESS);
            assertThat(first.created()).isEqualTo(2);
            assertThat(repeated.created()).isZero();
            assertThat(repeated.unchanged()).isEqualTo(2);
            assertThat(application.getBean(GameLookup.class)
                    .find("owner-id", new GameRef(GameRef.Source.CHESSCOM, "102")))
                    .get()
                    .satisfies(game -> {
                        assertThat(game.movesSan()).isEqualTo("e4 c6 d4 d5 e5 Bf5");
                        assertThat(game.clocks()).hasSize(6);
                        assertThat(game.analysisEligible()).isTrue();
                    });
            assertThat(application.getBean(GameCatalog.class)
                    .games("other-id", GameCatalogQuery.unfiltered(0, 50)).games()).isEmpty();

            players.remove("owner-id");
            assertThat(players.username("owner-id")).isEmpty();
            assertThat(retrieval.retrieve("owner-id", 5, null).status())
                    .isEqualTo(ChessComRetrievalResult.Status.USERNAME_MISSING);
        }
    }

    @Test
    void refusesAnInvalidUsernameInCodeAndInTheDatabase(@TempDir Path directory) {
        try (var application = start(directory.resolve("runtime/chess-tweek.db"))) {
            var players = application.getBean(ChessComPlayers.class);
            var jdbc = application.getBean(JdbcTemplate.class);

            assertThatThrownBy(() -> players.save("owner-id", "a b"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO game_review_chesscom_player VALUES ('owner-id', 'bad/name', 0)"))
                    .hasMessageContaining("CHECK constraint failed");
        }
    }

    private ConfigurableApplicationContext start(Path databasePath) {
        return new SpringApplicationBuilder(ChessTweekApplication.class, FakeChessComConfiguration.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + databasePath, "--logging.level.root=OFF");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeChessComConfiguration {

        @Bean
        @Primary
        ChessComGameClient fakeChessComGameClient() {
            return new ChessComGameClient() {
                @Override
                public List<YearMonth> archiveMonths(String username) {
                    return List.of(YearMonth.of(2026, 11));
                }

                @Override
                public List<JsonNode> monthGames(String username, YearMonth month) {
                    return List.of(
                            ChessComRetrievalServiceTest.game(101, "2026-11-02T10:00:00Z"),
                            ChessComRetrievalServiceTest.game(102, "2026-11-05T10:00:00Z"));
                }
            };
        }
    }
}
