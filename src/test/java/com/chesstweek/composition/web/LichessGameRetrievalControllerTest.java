package com.chesstweek.composition.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.gamereview.GameRetrieval;
import com.chesstweek.gamereview.GameRetrievalResult;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LichessGameRetrievalControllerTest {

    @Test
    void retrievesTheRequestedFinishedGamesForTheConnectedOwner() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.connected(
                        "owner-id", "ChessOwner", "liu_private_token_123");
        var requestSeen = new AtomicReference<RecordedRetrieval>();
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) -> {
            requestSeen.set(new RecordedRetrieval(
                    accessToken, accountId, username, count, before));
            return GameRetrievalResult.success(2, 0);
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LichessGameRetrievalController(
                        new LichessGameRetrievalFacade(authorizer, retrieval)))
                .build();

        mockMvc.perform(post("/api/lichess/games/retrievals")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "same-origin")
                        .cookie(new Cookie("__Host-chess_tweek_session", "sealed-session"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2,\"before\":\"2026-11-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.before").value("2026-11-01"))
                .andExpect(content().string(not(containsString("liu_private_token_123"))))
                .andExpect(content().string(not(containsString("game"))));

        assertThat(requestSeen.get()).isEqualTo(new RecordedRetrieval(
                "liu_private_token_123",
                "owner-id",
                "ChessOwner",
                2,
                Instant.parse("2026-11-01T00:00:00Z")));
    }

    @Test
    void deniesRetrievalWithoutAConnectedOwnerSession() throws Exception {
        var retrievalCalls = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.disconnected(false);
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) -> {
            retrievalCalls.incrementAndGet();
            throw new AssertionError("Logged-out requests must not reach Lichess");
        };
        MockMvc mockMvc = mockMvc(authorizer, retrieval);

        mockMvc.perform(validRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(retrievalCalls).hasValue(0);
    }

    @Test
    void rejectsForeignOriginsBeforeOpeningTheSession() throws Exception {
        var authorizationCalls = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealedSession -> {
            authorizationCalls.incrementAndGet();
            throw new AssertionError("Foreign origins must not open the session");
        };
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) -> {
            throw new AssertionError("Foreign origins must not reach Lichess");
        };
        MockMvc mockMvc = mockMvc(authorizer, retrieval);

        mockMvc.perform(post("/api/lichess/games/retrievals")
                        .header("Origin", "https://attacker.example")
                        .header("Sec-Fetch-Site", "cross-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));

        assertThat(authorizationCalls).hasValue(0);
    }

    @Test
    void rejectsOutOfRangeCountsAndInvalidDatesBeforeOpeningTheSession() throws Exception {
        var authorizationCalls = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealedSession -> {
            authorizationCalls.incrementAndGet();
            throw new AssertionError("Invalid input must not open the session");
        };
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) -> {
            throw new AssertionError("Invalid input must not reach Lichess");
        };
        MockMvc mockMvc = mockMvc(authorizer, retrieval);

        for (String body : java.util.List.of(
                "{\"count\":0}",
                "{\"count\":51}",
                "{\"count\":2,\"before\":\"not-a-date\"}",
                "{\"count\":2,\"before\":\"2026-11-01T00:00:00Z\"}")) {
            mockMvc.perform(post("/api/lichess/games/retrievals")
                            .header("Origin", "http://127.0.0.1:8080")
                            .header("Sec-Fetch-Site", "same-origin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_retrieval_request"));
        }

        assertThat(authorizationCalls).hasValue(0);
    }

    @Test
    void clearsARejectedSessionAndDoesNotExposeTheCredential() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.connected(
                        "owner-id", "ChessOwner", "liu_private_token_123");
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) ->
                GameRetrievalResult.failed(GameRetrievalResult.Status.UNAUTHORIZED, null);
        MockMvc mockMvc = mockMvc(authorizer, retrieval);

        mockMvc.perform(validRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(jsonPath("$.error").value("not_connected"))
                .andExpect(content().string(not(containsString("liu_private_token_123"))));
    }

    @Test
    void exposesOnlyASafeRetryDelayWhenLichessRateLimitsRetrieval() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.connected(
                        "owner-id", "ChessOwner", "liu_private_token_123");
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) ->
                GameRetrievalResult.failed(GameRetrievalResult.Status.RATE_LIMITED, 73L);
        MockMvc mockMvc = mockMvc(authorizer, retrieval);

        mockMvc.perform(validRequest())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "73"))
                .andExpect(jsonPath("$.error").value("lichess_rate_limited"))
                .andExpect(content().string(not(containsString("liu_private_token_123"))));
    }

    @Test
    void exposesOnlySafeRecoverableUpstreamErrors() throws Exception {
        LichessSessionAuthorizer authorizer = sealedSession ->
                LichessSessionAuthorization.connected(
                        "owner-id", "ChessOwner", "liu_private_token_123");

        for (var failure : java.util.List.of(
                new ExpectedFailure(
                        GameRetrievalResult.Status.INVALID_RESPONSE,
                        502,
                        "invalid_lichess_response"),
                new ExpectedFailure(
                        GameRetrievalResult.Status.UPSTREAM_UNAVAILABLE,
                        503,
                        "lichess_unavailable"),
                new ExpectedFailure(
                        GameRetrievalResult.Status.STORAGE_UNAVAILABLE,
                        503,
                        "catalog_unavailable"))) {
            GameRetrieval retrieval = (accessToken, accountId, username, count, before) ->
                    GameRetrievalResult.failed(failure.status(), null);

            mockMvc(authorizer, retrieval)
                    .perform(validRequest())
                    .andExpect(status().is(failure.httpStatus()))
                    .andExpect(jsonPath("$.error").value(failure.error()))
                    .andExpect(content().string(not(containsString("liu_private_token_123"))));
        }
    }

    @Test
    void rejectsAConcurrentRetrievalBeforeMakingAnotherLichessRequest() throws Exception {
        var authorizationEntered = new CountDownLatch(1);
        var releaseAuthorization = new CountDownLatch(1);
        var authorizationCalls = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealedSession -> {
            authorizationCalls.incrementAndGet();
            authorizationEntered.countDown();
            try {
                if (!releaseAuthorization.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test did not release session authorization");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return LichessSessionAuthorization.connected(
                    "owner-id", "ChessOwner", "liu_private_token_123");
        };
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) ->
                GameRetrievalResult.success(1, 0);
        var facade = new LichessGameRetrievalFacade(
                authorizer, retrieval, Clock.systemUTC());
        var executor = Executors.newSingleThreadExecutor();

        try {
            var first = executor.submit(() -> facade.retrieve("sealed-session", 1, null));
            assertThat(authorizationEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(facade.retrieve("sealed-session", 1, null).status())
                    .isEqualTo(LichessGameRetrievalFacade.Status.BUSY);
            assertThat(authorizationCalls).hasValue(1);

            releaseAuthorization.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).status())
                    .isEqualTo(LichessGameRetrievalFacade.Status.SUCCESS);
        } finally {
            releaseAuthorization.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesTheLichessRetryDelayBeforeReauthorizing() {
        var authorizationCalls = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealedSession -> {
            authorizationCalls.incrementAndGet();
            return LichessSessionAuthorization.connected(
                    "owner-id", "ChessOwner", "liu_private_token_123");
        };
        var retrievalCalls = new AtomicInteger();
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) -> {
            retrievalCalls.incrementAndGet();
            return GameRetrievalResult.failed(GameRetrievalResult.Status.RATE_LIMITED, 73L);
        };
        var facade = new LichessGameRetrievalFacade(
                authorizer,
                retrieval,
                Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC));

        assertThat(facade.retrieve("sealed-session", 1, null).retryAfterSeconds())
                .isEqualTo(73L);
        assertThat(facade.retrieve("sealed-session", 1, null).retryAfterSeconds())
                .isEqualTo(73L);
        assertThat(authorizationCalls).hasValue(1);
        assertThat(retrievalCalls).hasValue(1);
    }

    @Test
    void enforcesAnAccountCheckRetryDelayBeforeCallingGameExport() {
        var authorizationCalls = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealedSession -> {
            authorizationCalls.incrementAndGet();
            return LichessSessionAuthorization.rateLimited(73L);
        };
        var retrievalCalls = new AtomicInteger();
        GameRetrieval retrieval = (accessToken, accountId, username, count, before) -> {
            retrievalCalls.incrementAndGet();
            throw new AssertionError("A rate-limited account check must not export games");
        };
        var facade = new LichessGameRetrievalFacade(
                authorizer,
                retrieval,
                Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC));

        assertThat(facade.retrieve("sealed-session", 1, null).retryAfterSeconds())
                .isEqualTo(73L);
        assertThat(facade.retrieve("sealed-session", 1, null).retryAfterSeconds())
                .isEqualTo(73L);
        assertThat(authorizationCalls).hasValue(1);
        assertThat(retrievalCalls).hasValue(0);
    }

    private static MockMvc mockMvc(
            LichessSessionAuthorizer authorizer, GameRetrieval retrieval) {
        return MockMvcBuilders.standaloneSetup(new LichessGameRetrievalController(
                        new LichessGameRetrievalFacade(authorizer, retrieval)))
                .build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            validRequest() {
        return post("/api/lichess/games/retrievals")
                .header("Origin", "http://127.0.0.1:8080")
                .header("Sec-Fetch-Site", "same-origin")
                .cookie(new Cookie("__Host-chess_tweek_session", "sealed-session"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":2}");
    }

    private record RecordedRetrieval(
            String accessToken,
            String accountId,
            String username,
            int count,
            Instant before) {}

    private record ExpectedFailure(
            GameRetrievalResult.Status status, int httpStatus, String error) {}
}
