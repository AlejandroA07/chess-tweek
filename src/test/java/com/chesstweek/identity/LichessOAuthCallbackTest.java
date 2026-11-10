package com.chesstweek.identity;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.chesstweek.composition.web.LichessAuthorizationController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;

class LichessOAuthCallbackTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-10T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void exchangesAValidCallbackOnceAndSetsOnlyASealedCookie(@TempDir Path temporaryDirectory)
            throws Exception {
        var tokenClient = new FakeLichessTokenClient();
        var keyPath = temporaryDirectory.resolve("runtime/lichess-cookie.key");
        var service = new LichessAuthorizationService(
                new SecureRandom(),
                CLOCK,
                tokenClient,
                new SessionCookieSealer(keyPath, new SecureRandom()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LichessAuthorizationController(service))
                .build();
        String state = beginAuthorization(mockMvc);

        var response = mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/#/dashboard"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn()
                .getResponse();

        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie)
                .startsWith("__Host-chess_tweek_session=v1.")
                .contains("Path=/")
                .contains("Max-Age=3600")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain(FakeLichessTokenClient.ACCESS_TOKEN);
        assertThat(tokenClient.exchangeCount).isEqualTo(1);
        assertThat(Files.getPosixFilePermissions(keyPath))
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void rejectsWrongStateWithoutConsumingTheValidAttempt(@TempDir Path temporaryDirectory)
            throws Exception {
        var tokenClient = new FakeLichessTokenClient();
        MockMvc mockMvc = callbackMockMvc(temporaryDirectory, CLOCK, tokenClient);
        String validState = beginAuthorization(mockMvc);
        String wrongState = (validState.startsWith("A") ? "B" : "A") + validState.substring(1);

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", wrongState))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?lichess=invalid#/dashboard"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", validState))
                .andExpect(status().isSeeOther());
        assertThat(tokenClient.exchangeCount).isEqualTo(1);
    }

    @Test
    void rejectsAReplayedCallback(@TempDir Path temporaryDirectory) throws Exception {
        var tokenClient = new FakeLichessTokenClient();
        MockMvc mockMvc = callbackMockMvc(temporaryDirectory, CLOCK, tokenClient);
        String state = beginAuthorization(mockMvc);

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther());
        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?lichess=invalid#/dashboard"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(tokenClient.exchangeCount).isEqualTo(1);
    }

    @Test
    void rejectsAnExpiredAuthorizationAttempt(@TempDir Path temporaryDirectory) throws Exception {
        var clock = new MutableClock(Instant.parse("2026-11-10T09:00:00Z"));
        var tokenClient = new FakeLichessTokenClient();
        MockMvc mockMvc = callbackMockMvc(temporaryDirectory, clock, tokenClient);
        String state = beginAuthorization(mockMvc);
        clock.advance(Duration.ofMinutes(6));

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?lichess=invalid#/dashboard"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(tokenClient.exchangeCount).isZero();
    }

    @Test
    void setsNoCookieWhenLichessIsUnavailable(@TempDir Path temporaryDirectory) throws Exception {
        LichessTokenClient unavailableClient = (code, verifier) -> {
            throw new LichessTokenExchangeException(false, new IOException("unavailable"));
        };
        MockMvc mockMvc = callbackMockMvc(temporaryDirectory, CLOCK, unavailableClient);
        String state = beginAuthorization(mockMvc);

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?lichess=unavailable#/dashboard"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void handlesAUserCancelledAuthorizationWithoutCallingLichess(@TempDir Path temporaryDirectory)
            throws Exception {
        var tokenClient = new FakeLichessTokenClient();
        MockMvc mockMvc = callbackMockMvc(temporaryDirectory, CLOCK, tokenClient);
        String state = beginAuthorization(mockMvc);

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("error", "access_denied")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?lichess=cancelled#/dashboard"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(tokenClient.exchangeCount).isZero();
    }

    @Test
    void sendsTheRequiredFormToTheFixedLichessTokenEndpoint(@TempDir Path temporaryDirectory)
            throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo("https://lichess.org/api/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "authorization_code",
                        "code", "valid_code_123",
                        "redirect_uri", "http://127.0.0.1:8080/api/lichess/callback",
                        "client_id", "chess-tweek-macos-local")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code_verifier=")))
                .andRespond(withSuccess(
                        "{\"token_type\":\"Bearer\",\"access_token\":\"liu_mock_token_123\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        MockMvc mockMvc = callbackMockMvc(
                temporaryDirectory,
                CLOCK,
                new HttpLichessTokenClient(restClientBuilder.build()));
        String state = beginAuthorization(mockMvc);

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().exists("Set-Cookie"));
        upstream.verify();
    }

    @Test
    void rejectsAnOversizedAuthorizationCodeBeforeCallingLichess(@TempDir Path temporaryDirectory)
            throws Exception {
        var tokenClient = new FakeLichessTokenClient();
        MockMvc mockMvc = callbackMockMvc(temporaryDirectory, CLOCK, tokenClient);
        String state = beginAuthorization(mockMvc);

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "a".repeat(513))
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?lichess=invalid#/dashboard"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(tokenClient.exchangeCount).isZero();
    }

    @Test
    void treatsARejectedCodeAsAnInvalidCallback(@TempDir Path temporaryDirectory) throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo("https://lichess.org/api/token"))
                .andRespond(withBadRequest());
        MockMvc mockMvc = callbackMockMvc(
                temporaryDirectory,
                CLOCK,
                new HttpLichessTokenClient(restClientBuilder.build()));
        String state = beginAuthorization(mockMvc);

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "expired_code_123")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?lichess=invalid#/dashboard"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().string("Cache-Control", "no-store"));

        upstream.verify();
    }

    @Test
    void refusesASymbolicLinkSessionKeyWithoutChangingItsTarget(@TempDir Path temporaryDirectory)
            throws Exception {
        Path externalKey = Files.writeString(temporaryDirectory.resolve("external.key"), "unchanged");
        Path runtimeDirectory = Files.createDirectory(temporaryDirectory.resolve("runtime"));
        Files.setPosixFilePermissions(
                runtimeDirectory,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Files.createSymbolicLink(runtimeDirectory.resolve("lichess-cookie.key"), externalKey);
        var tokenClient = new FakeLichessTokenClient();
        MockMvc mockMvc = callbackMockMvc(temporaryDirectory, CLOCK, tokenClient);
        String state = beginAuthorization(mockMvc);

        mockMvc.perform(get("/api/lichess/callback")
                        .queryParam("code", "valid_code_123")
                        .queryParam("state", state))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/?lichess=unavailable#/dashboard"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(Files.readString(externalKey)).isEqualTo("unchanged");
    }

    private static MockMvc callbackMockMvc(
            Path temporaryDirectory, Clock clock, LichessTokenClient tokenClient) {
        var service = new LichessAuthorizationService(
                new SecureRandom(),
                clock,
                tokenClient,
                new SessionCookieSealer(
                        temporaryDirectory.resolve("runtime/lichess-cookie.key"),
                        new SecureRandom()));
        return MockMvcBuilders
                .standaloneSetup(new LichessAuthorizationController(service))
                .build();
    }

    private static String beginAuthorization(MockMvc mockMvc) throws Exception {
        String location = mockMvc.perform(post("/api/lichess/authorize")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        return UriComponentsBuilder.fromUri(URI.create(location))
                .build()
                .getQueryParams()
                .getFirst("state");
    }

    private static final class FakeLichessTokenClient implements LichessTokenClient {

        private static final String ACCESS_TOKEN = "liu_test_token_123";
        private int exchangeCount;

        @Override
        public LichessToken exchange(String code, String verifier) {
            exchangeCount++;
            return new LichessToken("Bearer", ACCESS_TOKEN, 3_600);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
