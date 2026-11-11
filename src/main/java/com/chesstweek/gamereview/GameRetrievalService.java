package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

@Service
public final class GameRetrievalService implements GameRetrieval {

    private final LichessGameClient client;
    private final AtomicBoolean retrievalRunning;

    public GameRetrievalService() {
        this(new HttpLichessGameClient());
    }

    GameRetrievalService(LichessGameClient client) {
        this.client = client;
        this.retrievalRunning = new AtomicBoolean();
    }

    @Override
    public GameRetrievalResult retrieve(
            String accessToken, String username, int count, Instant before) {
        if (!retrievalRunning.compareAndSet(false, true)) {
            return GameRetrievalResult.failed(GameRetrievalResult.Status.BUSY, null);
        }
        try {
            GameExportBatch batch = client.fetch(accessToken, username, count, before);
            return GameRetrievalResult.success(batch.games().size());
        } catch (LichessGameClientException exception) {
            return switch (exception.reason()) {
                case UNAUTHORIZED -> GameRetrievalResult.failed(
                        GameRetrievalResult.Status.UNAUTHORIZED, null);
                case RATE_LIMITED -> GameRetrievalResult.failed(
                        GameRetrievalResult.Status.RATE_LIMITED,
                        exception.retryAfterSeconds() == null
                                ? 60L
                                : exception.retryAfterSeconds());
                case INVALID_RESPONSE -> GameRetrievalResult.failed(
                        GameRetrievalResult.Status.INVALID_RESPONSE, null);
                case UNAVAILABLE -> GameRetrievalResult.failed(
                        GameRetrievalResult.Status.UPSTREAM_UNAVAILABLE, null);
            };
        } finally {
            retrievalRunning.set(false);
        }
    }
}
