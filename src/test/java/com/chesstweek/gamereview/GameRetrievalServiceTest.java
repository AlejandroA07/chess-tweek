package com.chesstweek.gamereview;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.chesstweek.gamereview.internal.persistence.GameCatalogStore;

import static org.assertj.core.api.Assertions.assertThat;

class GameRetrievalServiceTest {

    @Test
    void mapsEveryUpstreamFailureWithoutLeakingItsCause() {
        for (var mapping : List.of(
                new FailureMapping(
                        LichessGameClientException.Reason.UNAUTHORIZED,
                        GameRetrievalResult.Status.UNAUTHORIZED,
                        null),
                new FailureMapping(
                        LichessGameClientException.Reason.RATE_LIMITED,
                        GameRetrievalResult.Status.RATE_LIMITED,
                        60L),
                new FailureMapping(
                        LichessGameClientException.Reason.INVALID_RESPONSE,
                        GameRetrievalResult.Status.INVALID_RESPONSE,
                        null),
                new FailureMapping(
                        LichessGameClientException.Reason.UNAVAILABLE,
                        GameRetrievalResult.Status.UPSTREAM_UNAVAILABLE,
                        null))) {
            LichessGameClient client = (accessToken, username, count, before) -> {
                throw new LichessGameClientException(mapping.reason(), null, null);
            };

            GameRetrievalResult result = service(client)
                    .retrieve(
                            "liu_private_token_123",
                            "ownerid",
                            "ChessOwner",
                            1,
                            null);

            assertThat(result.status()).isEqualTo(mapping.status());
            assertThat(result.retryAfterSeconds()).isEqualTo(mapping.retryAfterSeconds());
        }
    }

    @Test
    void includesTransportAndPayloadRejectionsInTheSafeSummary() {
        LichessGameClient client = (accessToken, username, count, before) ->
                new GameExportBatch(
                        List.of(new ExportedGame("game0001", "mate", "{}")),
                        2);

        GameRetrievalResult result = service(client).retrieve(
                "liu_private_token_123", "ownerid", "ChessOwner", 3, null);

        assertThat(result.status()).isEqualTo(GameRetrievalResult.Status.SUCCESS);
        assertThat(result.received()).isZero();
        assertThat(result.rejected()).isEqualTo(3);
    }

    @Test
    void allowsOnlyOneLichessRetrievalAtATime() throws Exception {
        var enteredClient = new CountDownLatch(1);
        var releaseClient = new CountDownLatch(1);
        LichessGameClient client = (accessToken, username, count, before) -> {
            enteredClient.countDown();
            try {
                if (!releaseClient.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test did not release the fake Lichess response");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return new GameExportBatch(List.of(
                    new ExportedGame("game0001", "mate", "{}")));
        };
        var service = service(client);
        var executor = Executors.newSingleThreadExecutor();

        try {
            var first = executor.submit(() -> service.retrieve(
                    "liu_private_token_123", "ownerid", "ChessOwner", 1, null));
            assertThat(enteredClient.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(service.retrieve(
                            "liu_private_token_123",
                            "ownerid",
                            "ChessOwner",
                            1,
                            null).status())
                    .isEqualTo(GameRetrievalResult.Status.BUSY);

            releaseClient.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).status())
                    .isEqualTo(GameRetrievalResult.Status.SUCCESS);
        } finally {
            releaseClient.countDown();
            executor.shutdownNow();
        }
    }

    private static GameRetrievalService service(LichessGameClient client) {
        GameCatalogStore catalog = (accountId, games, retrievedAt) ->
                new GameCatalogWriteResult(games.size(), 0, 0);
        return new GameRetrievalService(
                client, new GamePayloadParser(), catalog, Clock.systemUTC());
    }

    private record FailureMapping(
            LichessGameClientException.Reason reason,
            GameRetrievalResult.Status status,
            Long retryAfterSeconds) {}
}
