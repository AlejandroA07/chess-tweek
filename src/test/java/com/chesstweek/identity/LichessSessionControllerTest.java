package com.chesstweek.identity;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.chesstweek.composition.web.LichessSessionController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LichessSessionControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void returnsOnlyConnectedAccountMetadataForAValidSession(@TempDir Path temporaryDirectory)
            throws Exception {
        var sealer = new SessionCookieSealer(
                temporaryDirectory.resolve("runtime/lichess-cookie.key"), new SecureRandom());
        String cookieValue = sealer.seal("liu_private_token_123", NOW.plusSeconds(3_600).getEpochSecond());
        LichessAccountClient accountClient = token -> new LichessAccount(
                "bobby",
                "Bobby",
                new LichessRatings(1755, 1623, 1824, 1756, 1648));
        var service = new LichessSessionService(
                CLOCK,
                accountClient,
                token -> {},
                new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(get("/api/lichess/session")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.account.id").value("bobby"))
                .andExpect(jsonPath("$.account.username").value("Bobby"))
                .andExpect(jsonPath("$.account.ratings.bullet").value(1755))
                .andExpect(jsonPath("$.account.ratings.blitz").value(1623))
                .andExpect(jsonPath("$.account.ratings.rapid").value(1824))
                .andExpect(jsonPath("$.account.ratings.classical").value(1756))
                .andExpect(jsonPath("$.account.ratings.correspondence").value(1648))
                .andExpect(content().string(not(containsString("liu_private_token_123"))));
    }

    @Test
    void returnsDisconnectedWithoutCallingLichessWhenNoCookieExists(@TempDir Path temporaryDirectory)
            throws Exception {
        var accountCalls = new AtomicInteger();
        var service = sessionService(temporaryDirectory, token -> {
            accountCalls.incrementAndGet();
            throw new AssertionError("Missing sessions must not call Lichess");
        });
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(get("/api/lichess/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(header().doesNotExist("Set-Cookie"));

        org.assertj.core.api.Assertions.assertThat(accountCalls).hasValue(0);
    }

    @Test
    void clearsATamperedCookieWithoutCallingLichess(@TempDir Path temporaryDirectory)
            throws Exception {
        var sealer = new SessionCookieSealer(
                temporaryDirectory.resolve("runtime/lichess-cookie.key"), new SecureRandom());
        String cookieValue = sealer.seal("liu_private_token_123", NOW.plusSeconds(3_600).getEpochSecond());
        String tampered = cookieValue.substring(0, cookieValue.length() - 1)
                + (cookieValue.endsWith("A") ? "B" : "A");
        var accountCalls = new AtomicInteger();
        var service = sessionService(temporaryDirectory, token -> {
            accountCalls.incrementAndGet();
            throw new AssertionError("Tampered sessions must not call Lichess");
        });
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(get("/api/lichess/session")
                        .cookie(new Cookie("__Host-chess_tweek_session", tampered)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        org.assertj.core.api.Assertions.assertThat(accountCalls).hasValue(0);
    }

    @Test
    void clearsAnExpiredCookieWithoutCallingLichess(@TempDir Path temporaryDirectory)
            throws Exception {
        var sealer = new SessionCookieSealer(
                temporaryDirectory.resolve("runtime/lichess-cookie.key"), new SecureRandom());
        String cookieValue = sealer.seal("liu_private_token_123", NOW.minusSeconds(1).getEpochSecond());
        var accountCalls = new AtomicInteger();
        var service = sessionService(temporaryDirectory, token -> {
            accountCalls.incrementAndGet();
            throw new AssertionError("Expired sessions must not call Lichess");
        });
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(get("/api/lichess/session")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        org.assertj.core.api.Assertions.assertThat(accountCalls).hasValue(0);
    }

    @Test
    void clearsARevokedSession(@TempDir Path temporaryDirectory) throws Exception {
        String cookieValue = sealedSession(temporaryDirectory);
        var service = sessionService(temporaryDirectory, token -> {
            throw new LichessAccountException(true, null);
        });
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(get("/api/lichess/session")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void keepsAValidCookieWhenLichessIsTemporarilyUnavailable(@TempDir Path temporaryDirectory)
            throws Exception {
        String cookieValue = sealedSession(temporaryDirectory);
        var service = sessionService(temporaryDirectory, token -> {
            throw new LichessAccountException(false, new java.io.IOException("unavailable"));
        });
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(get("/api/lichess/session")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("lichess_unavailable"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void revokesAValidTokenAndAlwaysClearsTheCookieOnLogout(@TempDir Path temporaryDirectory)
            throws Exception {
        var sealer = new SessionCookieSealer(
                temporaryDirectory.resolve("runtime/lichess-cookie.key"), new SecureRandom());
        String cookieValue = sealer.seal("liu_private_token_123", NOW.plusSeconds(3_600).getEpochSecond());
        var revoker = new RecordingTokenRevoker();
        var service = new LichessSessionService(
                CLOCK,
                token -> {
                    throw new AssertionError("Account lookup is not part of logout");
                },
                revoker,
                sealer);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(post("/api/lichess/logout")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "same-origin")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(
                        "Set-Cookie",
                        org.hamcrest.Matchers.allOf(
                                containsString("__Host-chess_tweek_session="),
                                containsString("Max-Age=0"),
                                containsString("Path=/"),
                                containsString("Secure"),
                                containsString("HttpOnly"),
                                containsString("SameSite=Strict"))));

        org.assertj.core.api.Assertions.assertThat(revoker.revokedToken)
                .isEqualTo("liu_private_token_123");
    }

    @Test
    void rejectsCrossSiteLogoutWithoutClearingOrRevoking(@TempDir Path temporaryDirectory)
            throws Exception {
        String cookieValue = sealedSession(temporaryDirectory);
        var revoker = new RecordingTokenRevoker();
        var service = new LichessSessionService(
                CLOCK,
                token -> {
                    throw new AssertionError("Account lookup is not part of logout");
                },
                revoker,
                new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(post("/api/lichess/logout")
                        .header("Origin", "https://attacker.example")
                        .header("Sec-Fetch-Site", "cross-site")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"));

        org.assertj.core.api.Assertions.assertThat(revoker.revokedToken).isNull();
    }

    @Test
    void rejectsLogoutWithoutAnOrigin(@TempDir Path temporaryDirectory) throws Exception {
        var revoker = new RecordingTokenRevoker();
        var service = new LichessSessionService(
                CLOCK,
                token -> {
                    throw new AssertionError("Account lookup is not part of logout");
                },
                revoker,
                new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(post("/api/lichess/logout"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"));

        org.assertj.core.api.Assertions.assertThat(revoker.revokedToken).isNull();
    }

    @Test
    void clearsTheLocalCookieWhenUpstreamRevocationFails(@TempDir Path temporaryDirectory)
            throws Exception {
        String cookieValue = sealedSession(temporaryDirectory);
        var service = new LichessSessionService(
                CLOCK,
                token -> {
                    throw new AssertionError("Account lookup is not part of logout");
                },
                token -> {
                    throw new LichessRevocationException(new java.io.IOException("unavailable"));
                },
                new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(post("/api/lichess/logout")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "same-origin")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void requestsOnlyTheCurrentAccountWithTheSealedToken(@TempDir Path temporaryDirectory)
            throws Exception {
        String cookieValue = sealedSession(temporaryDirectory);
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo("https://lichess.org/api/account"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer liu_private_token_123"))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "bobby",
                          "username": "Bobby",
                          "perfs": {
                            "bullet": { "rating": 1755 },
                            "blitz": { "rating": 1623 },
                            "rapid": { "rating": 1824 },
                            "classical": { "rating": 1756 },
                            "correspondence": { "rating": 1648 },
                            "puzzle": { "rating": 2100 }
                          },
                          "profile": { "bio": "not returned by Chess Tweek" }
                        }
                        """,
                        MediaType.APPLICATION_JSON));
        var service = new LichessSessionService(
                CLOCK,
                new HttpLichessAccountClient(restClientBuilder.build()),
                token -> {},
                new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(get("/api/lichess/session")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.account.username").value("Bobby"))
                .andExpect(jsonPath("$.account.profile").doesNotExist())
                .andExpect(jsonPath("$.account.ratings.puzzle").doesNotExist());

        upstream.verify();
    }

    @Test
    void sendsLogoutOnlyToTheFixedRevocationEndpoint(@TempDir Path temporaryDirectory)
            throws Exception {
        String cookieValue = sealedSession(temporaryDirectory);
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo("https://lichess.org/api/token"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer liu_private_token_123"))
                .andRespond(withNoContent());
        var service = new LichessSessionService(
                CLOCK,
                token -> {
                    throw new AssertionError("Account lookup is not part of logout");
                },
                new HttpLichessTokenRevoker(restClientBuilder.build()),
                new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessSessionController(service))
                .build();

        mockMvc.perform(post("/api/lichess/logout")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "same-origin")
                        .cookie(new Cookie("__Host-chess_tweek_session", cookieValue)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        upstream.verify();
    }

    private static LichessSessionService sessionService(
            Path temporaryDirectory, LichessAccountClient accountClient) {
        return new LichessSessionService(
                CLOCK,
                accountClient,
                token -> {},
                new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom()));
    }

    private static String sealedSession(Path temporaryDirectory) {
        return new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom())
                .seal("liu_private_token_123", NOW.plusSeconds(3_600).getEpochSecond());
    }

    private static final class RecordingTokenRevoker implements LichessTokenRevoker {

        private String revokedToken;

        @Override
        public void revoke(String accessToken) {
            revokedToken = accessToken;
        }
    }
}
