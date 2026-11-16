package com.chesstweek.gamereview;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpChessComGameClientTest {

    private static final String ARCHIVES = "https://api.chess.com/pub/player/ownername/games/archives";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
    private final HttpChessComGameClient client = new HttpChessComGameClient(
            builder.build(), Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void listsArchiveMonthsNewestFirstWithAGenericClientNameAndNoCredentials() {
        upstream.expect(requestTo(ARCHIVES))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(header(HttpHeaders.USER_AGENT, HttpChessComGameClient.USER_AGENT))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
                .andExpect(headerDoesNotExist(HttpHeaders.FROM))
                .andRespond(withSuccess("""
                        {"archives":[
                          "https://api.chess.com/pub/player/ownername/games/2026/09",
                          "https://api.chess.com/pub/player/ownername/games/2025/12",
                          "https://api.chess.com/pub/player/ownername/games/2026/11"]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.archiveMonths("OwnerName")).containsExactly(
                YearMonth.of(2026, 11), YearMonth.of(2026, 9), YearMonth.of(2025, 12));
        upstream.verify();
    }

    @Test
    void readsOneMonthOfGamesFromAUrlItBuildsItself() {
        upstream.expect(requestTo("https://api.chess.com/pub/player/ownername/games/2026/03"))
                .andRespond(withSuccess("{\"games\":[{\"url\":\"a\"},{\"url\":\"b\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.monthGames("OwnerName", YearMonth.of(2026, 3)))
                .extracting(game -> game.get("url").stringValue())
                .containsExactly("a", "b");
        upstream.verify();
    }

    @Test
    void reportsAnUnknownPlayer() {
        upstream.expect(requestTo(ARCHIVES)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertReason(() -> client.archiveMonths("ownername"),
                ChessComClientException.Reason.PLAYER_NOT_FOUND);
    }

    @Test
    void reportsRateLimitsWithTheRequestedWait() {
        upstream.expect(requestTo(ARCHIVES)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "120"));

        assertThatThrownBy(() -> client.archiveMonths("ownername"))
                .isInstanceOfSatisfying(ChessComClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(ChessComClientException.Reason.RATE_LIMITED);
                    assertThat(exception.retryAfterSeconds()).isEqualTo(120L);
                });
    }

    @Test
    void treatsAMissingMonthOrServerErrorAsUnavailable() {
        upstream.expect(requestTo("https://api.chess.com/pub/player/ownername/games/2026/03"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertReason(() -> client.monthGames("ownername", YearMonth.of(2026, 3)),
                ChessComClientException.Reason.UNAVAILABLE);

        upstream.reset();
        upstream.expect(requestTo(ARCHIVES)).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "https://evil.example/"));
        assertReason(() -> client.archiveMonths("ownername"), ChessComClientException.Reason.UNAVAILABLE);

        upstream.reset();
        upstream.expect(requestTo(ARCHIVES)).andRespond(withException(new IOException("offline")));
        assertReason(() -> client.archiveMonths("ownername"), ChessComClientException.Reason.UNAVAILABLE);
    }

    @Test
    void rejectsMalformedResponses() {
        for (String body : new String[] {
                "not json",
                "[]",
                "{\"archives\":\"none\"}",
                "{\"archives\":[\"https://evil.example/pub/player/x/games/2026/01\"]}",
                "{\"archives\":[\"https://api.chess.com/pub/player/x/games/2026/13\"]}",
                "{\"archives\":[42]}"}) {
            upstream.reset();
            upstream.expect(requestTo(ARCHIVES)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertReason(() -> client.archiveMonths("ownername"),
                    ChessComClientException.Reason.INVALID_RESPONSE);
        }

        upstream.reset();
        upstream.expect(requestTo(ARCHIVES)).andRespond(withSuccess("{\"archives\":[]}", MediaType.TEXT_HTML));
        assertReason(() -> client.archiveMonths("ownername"), ChessComClientException.Reason.INVALID_RESPONSE);

        upstream.reset();
        upstream.expect(requestTo("https://api.chess.com/pub/player/ownername/games/2026/03"))
                .andRespond(withSuccess("{\"games\":{}}", MediaType.APPLICATION_JSON));
        assertReason(() -> client.monthGames("ownername", YearMonth.of(2026, 3)),
                ChessComClientException.Reason.INVALID_RESPONSE);
    }

    @Test
    void refusesAnInvalidUsernameWithoutAnyRequest() {
        assertThatThrownBy(() -> client.archiveMonths("../admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.monthGames("owner name", YearMonth.of(2026, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        upstream.verify();
    }

    private static void assertReason(Runnable call, ChessComClientException.Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ChessComClientException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
