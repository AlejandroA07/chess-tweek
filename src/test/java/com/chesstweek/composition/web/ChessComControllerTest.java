package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.gamereview.ChessComPlayers;
import com.chesstweek.gamereview.ChessComRetrieval;
import com.chesstweek.gamereview.ChessComRetrievalResult;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChessComControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final String ORIGIN = "http://127.0.0.1:8080";

    private final FakePlayers players = new FakePlayers();
    private final FakeRetrieval retrieval = new FakeRetrieval();
    private final List<String> authorizations = new ArrayList<>();

    @Test
    void savesReadsAndRemovesTheUsernameOfTheConnectedAccount() throws Exception {
        MockMvc mockMvc = mockMvc(connected());

        mockMvc.perform(get("/api/chesscom/player").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.username").isEmpty());
        mockMvc.perform(write(put("/api/chesscom/player"), "{\"username\":\"Owner_Name-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("Owner_Name-1"));
        mockMvc.perform(get("/api/chesscom/player").cookie(SESSION))
                .andExpect(jsonPath("$.username").value("Owner_Name-1"));
        mockMvc.perform(delete("/api/chesscom/player").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNoContent());

        assertThat(players.events).containsExactly(
                "read owner-id", "save owner-id Owner_Name-1", "read owner-id", "remove owner-id");
    }

    @Test
    void rejectsInvalidUsernamesBeforeCheckingTheSession() throws Exception {
        MockMvc mockMvc = mockMvc(connected());

        for (String body : List.of(
                "{\"username\":\"ab\"}",
                "{\"username\":\"" + "a".repeat(26) + "\"}",
                "{\"username\":\"owner/../x\"}",
                "{\"username\":\"owner name\"}",
                "{\"username\":null}",
                "{}")) {
            mockMvc.perform(write(put("/api/chesscom/player"), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_chesscom_username"));
        }

        assertThat(authorizations).isEmpty();
        assertThat(players.events).isEmpty();
    }

    @Test
    void refusesChangesFromAnotherOriginAndRequestsWithoutASession() throws Exception {
        mockMvc(connected())
                .perform(put("/api/chesscom/player").cookie(SESSION)
                        .header("Origin", "https://example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"owner\"}"))
                .andExpect(status().isForbidden());
        mockMvc(connected())
                .perform(post("/api/chesscom/games/retrievals").cookie(SESSION)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":5}"))
                .andExpect(status().isForbidden());
        mockMvc(connected())
                .perform(delete("/api/chesscom/player").cookie(SESSION))
                .andExpect(status().isForbidden());

        MockMvc disconnected = mockMvc(sealed -> {
            authorizations.add(sealed);
            return LichessSessionAuthorization.disconnected(true);
        });
        disconnected.perform(get("/api/chesscom/player").cookie(SESSION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"));
        disconnected.perform(write(post("/api/chesscom/games/retrievals"), "{\"count\":5}"))
                .andExpect(status().isUnauthorized());
        mockMvc(sealed -> LichessSessionAuthorization.unavailable())
                .perform(get("/api/chesscom/player").cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("session_unavailable"));

        assertThat(players.events).isEmpty();
        assertThat(retrieval.requests).isEmpty();
    }

    @Test
    void retrievesTheRequestedCountBeforeTheGivenDate() throws Exception {
        retrieval.result = new ChessComRetrievalResult(
                ChessComRetrievalResult.Status.SUCCESS, 5, 1, 3, 1, 1, null);

        mockMvc(connected())
                .perform(write(post("/api/chesscom/games/retrievals"),
                        "{\"count\":5,\"before\":\"2026-11-01\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.requested").value(5))
                .andExpect(jsonPath("$.received").value(5))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unchanged").value(1))
                .andExpect(jsonPath("$.before").value("2026-11-01"));

        assertThat(retrieval.requests).containsExactly("owner-id 5 2026-11-01T00:00:00Z");
    }

    @Test
    void rejectsInvalidRetrievalRequestsBeforeCheckingTheSession() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        for (String body : List.of(
                "{}", "{\"count\":0}", "{\"count\":51}",
                "{\"count\":5,\"before\":\"2026-13-01\"}", "{\"count\":5,\"before\":\"2026-11-1\"}")) {
            mockMvc.perform(write(post("/api/chesscom/games/retrievals"), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_retrieval_request"));
        }

        assertThat(authorizations).isEmpty();
        assertThat(retrieval.requests).isEmpty();
    }

    @Test
    void reportsEveryRecoverableFailureWithASafeCode() throws Exception {
        expectFailure(ChessComRetrievalResult.Status.USERNAME_MISSING, 409, "chesscom_username_missing");
        expectFailure(ChessComRetrievalResult.Status.PLAYER_NOT_FOUND, 404, "chesscom_player_not_found");
        expectFailure(ChessComRetrievalResult.Status.INVALID_RESPONSE, 502, "invalid_chesscom_response");
        expectFailure(ChessComRetrievalResult.Status.UPSTREAM_UNAVAILABLE, 503, "chesscom_unavailable");
        expectFailure(ChessComRetrievalResult.Status.STORAGE_UNAVAILABLE, 503, "catalog_unavailable");
        expectFailure(ChessComRetrievalResult.Status.BUSY, 409, "retrieval_already_running");

        retrieval.result = ChessComRetrievalResultFactory.rateLimited(90L);
        mockMvc(connected())
                .perform(write(post("/api/chesscom/games/retrievals"), "{\"count\":5}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.error").value("chesscom_rate_limited"));
    }

    private void expectFailure(ChessComRetrievalResult.Status status, int code, String error)
            throws Exception {
        retrieval.result = new ChessComRetrievalResult(status, 0, 0, 0, 0, 0, null);
        mockMvc(connected())
                .perform(write(post("/api/chesscom/games/retrievals"), "{\"count\":5}"))
                .andExpect(status().is(code))
                .andExpect(jsonPath("$.error").value(error));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String body) {
        return builder.cookie(SESSION)
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockMvc mockMvc(LichessSessionAuthorizer authorizer) {
        return MockMvcBuilders
                .standaloneSetup(new ChessComController(authorizer, players, retrieval))
                .build();
    }

    private LichessSessionAuthorizer connected() {
        return sealed -> {
            authorizations.add(sealed);
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        };
    }

    private static final class ChessComRetrievalResultFactory {

        static ChessComRetrievalResult rateLimited(Long seconds) {
            return new ChessComRetrievalResult(
                    ChessComRetrievalResult.Status.RATE_LIMITED, 0, 0, 0, 0, 0, seconds);
        }
    }

    private static final class FakePlayers implements ChessComPlayers {

        private final List<String> events = new ArrayList<>();
        private String username;

        @Override
        public Optional<String> username(String accountId) {
            events.add("read " + accountId);
            return Optional.ofNullable(username);
        }

        @Override
        public void save(String accountId, String username) {
            events.add("save " + accountId + " " + username);
            this.username = username;
        }

        @Override
        public void remove(String accountId) {
            events.add("remove " + accountId);
            username = null;
        }
    }

    private static final class FakeRetrieval implements ChessComRetrieval {

        private final List<String> requests = new ArrayList<>();
        private ChessComRetrievalResult result;

        @Override
        public ChessComRetrievalResult retrieve(String accountId, int count, Instant before) {
            requests.add(accountId + " " + count + " " + before);
            return result;
        }
    }
}
