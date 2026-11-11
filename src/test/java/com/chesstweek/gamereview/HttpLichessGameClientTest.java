package com.chesstweek.gamereview;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class HttpLichessGameClientTest {

    @Test
    void fetchesOnlyTheRequestedFinishedGamesAsNdjson() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer liu_private_token_123"))
                .andExpect(header("Accept", "application/x-ndjson"))
                .andExpect(queryParam("max", "2"))
                .andExpect(queryParam("until", "1793491200000"))
                .andExpect(queryParam("moves", "true"))
                .andExpect(queryParam("pgnInJson", "true"))
                .andExpect(queryParam("clocks", "true"))
                .andExpect(queryParam("opening", "true"))
                .andExpect(queryParam("division", "true"))
                .andExpect(queryParam("ongoing", "false"))
                .andExpect(queryParam("finished", "true"))
                .andRespond(withSuccess(
                        "{\"id\":\"game0001\",\"status\":\"mate\"}\n"
                                + "{\"id\":\"game0002\",\"status\":\"draw\"}\n",
                        MediaType.parseMediaType("application/x-ndjson")));

        var client = new HttpLichessGameClient(restClientBuilder.build());

        var batch = client.fetch(
                "liu_private_token_123",
                "ChessOwner",
                2,
                Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(batch.games()).extracting(ExportedGame::id)
                .containsExactly("game0001", "game0002");
        upstream.verify();
    }

    @Test
    void rejectsAnInvalidGameWithoutDiscardingValidGames() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withSuccess(
                        "{\"id\":\"game0001\",\"status\":\"mate\"}\n"
                                + "{\"id\":\"game0002\",\"status\":\"started\"}\n",
                        MediaType.parseMediaType("application/x-ndjson")));
        var client = new HttpLichessGameClient(restClientBuilder.build());

        GameExportBatch batch = client.fetch(
                "liu_private_token_123", "ChessOwner", 2, null);

        assertThat(batch.games()).extracting(ExportedGame::id).containsExactly("game0001");
        assertThat(batch.rejected()).isEqualTo(1);
        upstream.verify();
    }

    @Test
    void rejectsMalformedGameJsonPerGameAndUnexpectedContentTypesGlobally() {
        RestClient.Builder malformedBuilder = RestClient.builder();
        MockRestServiceServer malformed = MockRestServiceServer.bindTo(malformedBuilder).build();
        malformed.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withSuccess(
                        "{not-json}\n",
                        MediaType.parseMediaType("application/x-ndjson")));

        GameExportBatch malformedBatch = new HttpLichessGameClient(malformedBuilder.build()).fetch(
                "liu_private_token_123", "ChessOwner", 1, null);

        assertThat(malformedBatch.games()).isEmpty();
        assertThat(malformedBatch.rejected()).isEqualTo(1);
        malformed.verify();

        RestClient.Builder wrongTypeBuilder = RestClient.builder();
        MockRestServiceServer wrongType = MockRestServiceServer.bindTo(wrongTypeBuilder).build();
        wrongType.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withSuccess(
                        "{\"id\":\"game0001\",\"status\":\"mate\"}\n",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new HttpLichessGameClient(wrongTypeBuilder.build()).fetch(
                        "liu_private_token_123", "ChessOwner", 1, null))
                .isInstanceOfSatisfying(
                        LichessGameClientException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(LichessGameClientException.Reason.INVALID_RESPONSE));
        wrongType.verify();
    }

    @Test
    void rejectsMalformedUtf8AndBareCarriageReturns() {
        byte[] prefix = "{\"id\":\"game0001\",\"status\":\"mate\",\"note\":\""
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] malformedUtf8 = ByteBuffer.allocate(prefix.length + 1 + suffix.length)
                .put(prefix)
                .put((byte) 0xC3)
                .put(suffix)
                .array();
        RestClient.Builder utf8Builder = RestClient.builder();
        MockRestServiceServer utf8 = MockRestServiceServer.bindTo(utf8Builder).build();
        utf8.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withSuccess(
                        malformedUtf8,
                        MediaType.parseMediaType("application/x-ndjson")));

        GameExportBatch utf8Batch = new HttpLichessGameClient(utf8Builder.build()).fetch(
                "liu_private_token_123", "ChessOwner", 1, null);

        assertThat(utf8Batch.games()).isEmpty();
        assertThat(utf8Batch.rejected()).isEqualTo(1);
        utf8.verify();

        RestClient.Builder carriageReturnBuilder = RestClient.builder();
        MockRestServiceServer carriageReturn =
                MockRestServiceServer.bindTo(carriageReturnBuilder).build();
        carriageReturn.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withSuccess(
                        "{\"id\":\"game0001\",\"status\":\"mate\",\"note\":\"bad\rgood\"}\n",
                        MediaType.parseMediaType("application/x-ndjson")));

        assertThatThrownBy(() -> new HttpLichessGameClient(carriageReturnBuilder.build()).fetch(
                        "liu_private_token_123", "ChessOwner", 1, null))
                .isInstanceOfSatisfying(
                        LichessGameClientException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(LichessGameClientException.Reason.INVALID_RESPONSE));
        carriageReturn.verify();
    }

    @Test
    void mapsNetworkFailureToARecoverableUnavailableResult() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withException(new IOException("connection lost")));

        assertThatThrownBy(() -> new HttpLichessGameClient(restClientBuilder.build()).fetch(
                        "liu_private_token_123", "ChessOwner", 1, null))
                .isInstanceOfSatisfying(
                        LichessGameClientException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(LichessGameClientException.Reason.UNAVAILABLE));
        upstream.verify();
    }

    @Test
    void rejectsAnOversizedGamePerGameWithoutBufferingTheWholeResponse() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withSuccess(
                        "{\"id\":\"game0001\",\"status\":\"mate\",\"pgn\":\""
                                + "x".repeat(512 * 1024)
                                + "\"}\n",
                        MediaType.parseMediaType("application/x-ndjson")));
        var client = new HttpLichessGameClient(restClientBuilder.build());

        GameExportBatch batch = client.fetch(
                "liu_private_token_123", "ChessOwner", 1, null);

        assertThat(batch.games()).isEmpty();
        assertThat(batch.rejected()).isEqualTo(1);
        upstream.verify();
    }

    @Test
    void distinguishesRejectedCredentialsFromRateLimiting() {
        RestClient.Builder unauthorizedBuilder = RestClient.builder();
        MockRestServiceServer unauthorized = MockRestServiceServer.bindTo(unauthorizedBuilder).build();
        unauthorized.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> new HttpLichessGameClient(unauthorizedBuilder.build()).fetch(
                        "liu_private_token_123", "ChessOwner", 1, null))
                .isInstanceOfSatisfying(
                        LichessGameClientException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(LichessGameClientException.Reason.UNAUTHORIZED));
        unauthorized.verify();

        RestClient.Builder limitedBuilder = RestClient.builder();
        MockRestServiceServer limited = MockRestServiceServer.bindTo(limitedBuilder).build();
        limited.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "73"));

        assertThatThrownBy(() -> new HttpLichessGameClient(limitedBuilder.build()).fetch(
                        "liu_private_token_123", "ChessOwner", 1, null))
                .isInstanceOfSatisfying(
                        LichessGameClientException.class,
                        exception -> {
                            assertThat(exception.reason())
                                    .isEqualTo(LichessGameClientException.Reason.RATE_LIMITED);
                            assertThat(exception.retryAfterSeconds()).isEqualTo(73L);
                        });
        limited.verify();
    }

    @Test
    void acceptsAnHttpDateRetryDelay() {
        Instant now = Instant.parse("2026-11-11T00:00:00Z");
        String retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(now.plusSeconds(73), ZoneOffset.UTC));
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(restClientBuilder).build();
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://lichess.org/api/games/user/ChessOwner?")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", retryAt));

        assertThatThrownBy(() -> new HttpLichessGameClient(
                                restClientBuilder.build(),
                                Clock.fixed(now, ZoneOffset.UTC))
                        .fetch("liu_private_token_123", "ChessOwner", 1, null))
                .isInstanceOfSatisfying(
                        LichessGameClientException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(73L));
        upstream.verify();
    }
}
