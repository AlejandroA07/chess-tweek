package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameCatalog;
import com.chesstweek.gamereview.GameCatalogPage;
import com.chesstweek.gamereview.GameCatalogQuery;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameCatalogControllerTest {

    @Test
    void returnsOnlySafeCardDataForTheAuthorizedAccount() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.connected(
                        "owner-id", "ChessOwner", "liu_private_token_123");
        var accountSeen = new AtomicReference<String>();
        GameCatalog catalog = (accountId, query) -> {
            accountSeen.set(accountId);
            return new GameCatalogPage(List.of(game()), 1);
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GameCatalogController(authorizer, catalog))
                .build();

        mockMvc.perform(get("/api/games")
                        .cookie(new jakarta.servlet.http.Cookie(
                                "__Host-chess_tweek_session", "sealed-session")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.games[0].id").value("game0001"))
                .andExpect(jsonPath("$.games[0].playedAt").value("2026-11-11T00:00:00Z"))
                .andExpect(jsonPath("$.games[0].result").value("win"))
                .andExpect(jsonPath("$.games[0].ownerColor").value("white"))
                .andExpect(jsonPath("$.games[0].opponent.type").value("bot"))
                .andExpect(jsonPath("$.games[0].opponent.name").value("StudyBot"))
                .andExpect(jsonPath("$.games[0].speed").value("blitz"))
                .andExpect(jsonPath("$.games[0].timeControl.label").value("5+3"))
                .andExpect(jsonPath("$.games[0].opening.eco").value("C20"))
                .andExpect(jsonPath("$.games[0].analysis.status").value("not_analyzed"))
                .andExpect(content().string(not(containsString("e4 e5"))))
                .andExpect(content().string(not(containsString("private_token"))));

        assertThat(accountSeen).hasValue("owner-id");
    }

    @Test
    void returnsASafeRecoverableErrorWhenTheCatalogIsUnavailable() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.connected(
                        "owner-id", "ChessOwner", "liu_private_token_123");
        GameCatalog catalog = (accountId, query) -> {
            throw new DataAccessResourceFailureException("private database detail");
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GameCatalogController(authorizer, catalog))
                .build();

        mockMvc.perform(get("/api/games")
                        .cookie(new jakarta.servlet.http.Cookie(
                                "__Host-chess_tweek_session", "sealed-session")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("catalog_unavailable"))
                .andExpect(content().string(not(containsString("private database detail"))));
    }

    @Test
    void deniesDisconnectedRequestsBeforeReadingAccountData() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.disconnected(false);
        var catalogCalls = new AtomicInteger();
        GameCatalog catalog = (accountId, query) -> {
            catalogCalls.incrementAndGet();
            return new GameCatalogPage(List.of(game()), 1);
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GameCatalogController(authorizer, catalog))
                .build();

        mockMvc.perform(get("/api/games"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"))
                .andExpect(content().string(not(containsString("game0001"))));

        assertThat(catalogCalls).hasValue(0);
    }

    @Test
    void passesBoundedPaginationToTheAccountCatalog() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.connected(
                        "owner-id", "ChessOwner", "liu_private_token_123");
        var pageSeen = new AtomicReference<String>();
        GameCatalog catalog = (accountId, query) -> {
            pageSeen.set(accountId + ":" + query.offset() + ":" + query.limit());
            return new GameCatalogPage(List.of(game()), 120);
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GameCatalogController(authorizer, catalog))
                .build();

        mockMvc.perform(get("/api/games").queryParam("offset", "50").queryParam("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(120))
                .andExpect(jsonPath("$.offset").value(50))
                .andExpect(jsonPath("$.limit").value(25));

        assertThat(pageSeen).hasValue("owner-id:50:25");
    }

    @Test
    void passesValidatedFiltersToTheAccountCatalog() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.connected(
                        "owner-id", "ChessOwner", "liu_private_token_123");
        var querySeen = new AtomicReference<GameCatalogQuery>();
        GameCatalog catalog = (accountId, query) -> {
            querySeen.set(query);
            return new GameCatalogPage(List.of(game()), 1);
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GameCatalogController(authorizer, catalog))
                .build();

        mockMvc.perform(get("/api/games")
                        .queryParam("offset", "25")
                        .queryParam("limit", "10")
                        .queryParam("result", "win")
                        .queryParam("color", "white")
                        .queryParam("sort", "oldest"))
                .andExpect(status().isOk());

        assertThat(querySeen).hasValue(new GameCatalogQuery(
                25,
                10,
                CatalogGame.Result.WIN,
                CatalogGame.Color.WHITE,
                GameCatalogQuery.Sort.OLDEST));
    }

    @Test
    void rejectsInvalidPaginationBeforeOpeningTheSession() throws Exception {
        var authorizationCalls = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealedSession -> {
            authorizationCalls.incrementAndGet();
            throw new AssertionError("Invalid input must not open the session");
        };
        GameCatalog catalog = (accountId, query) -> {
            throw new AssertionError("Invalid input must not read the catalog");
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GameCatalogController(authorizer, catalog))
                .build();

        mockMvc.perform(get("/api/games").queryParam("offset", "-1").queryParam("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_catalog_page"));

        assertThat(authorizationCalls).hasValue(0);
    }

    @Test
    void rejectsUnknownFiltersBeforeOpeningTheSession() throws Exception {
        var authorizationCalls = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealedSession -> {
            authorizationCalls.incrementAndGet();
            throw new AssertionError("Invalid filters must not open the session");
        };
        GameCatalog catalog = (accountId, query) -> {
            throw new AssertionError("Invalid filters must not read the catalog");
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new GameCatalogController(authorizer, catalog))
                .build();

        mockMvc.perform(get("/api/games")
                        .queryParam("result", "winning")
                        .queryParam("color", "green")
                        .queryParam("sort", "random"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_catalog_filter"));

        assertThat(authorizationCalls).hasValue(0);
    }

    private static CatalogGame game() {
        return new CatalogGame(
                "game0001",
                Instant.parse("2026-11-11T00:00:00Z"),
                Instant.parse("2026-11-11T00:05:00Z"),
                CatalogGame.Status.MATE,
                CatalogGame.Result.WIN,
                CatalogGame.Color.WHITE,
                1800,
                new CatalogGame.Opponent(
                        CatalogGame.OpponentKind.USER,
                        "studybot",
                        "StudyBot",
                        1750,
                        "BOT",
                        null),
                CatalogGame.Speed.BLITZ,
                true,
                CatalogGame.Variant.STANDARD,
                new CatalogGame.TimeControl(300, 3, null),
                new CatalogGame.Opening("C20", "King's Pawn Game", 2),
                new CatalogGame.PhaseDivision(4, 6),
                "e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#",
                "[Result \"1-0\"]",
                List.of(3000, 3000),
                true);
    }
}
