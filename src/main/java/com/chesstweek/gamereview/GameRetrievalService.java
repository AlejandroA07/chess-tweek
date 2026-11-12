package com.chesstweek.gamereview;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.chesstweek.gamereview.internal.persistence.GameCatalogStore;

@Service
public final class GameRetrievalService implements GameRetrieval {

    private final LichessGameClient client;
    private final GamePayloadParser parser;
    private final GameCatalogStore catalog;
    private final Clock clock;
    private final AtomicBoolean retrievalRunning;

    @Autowired
    GameRetrievalService(
            LichessGameClient client,
            GamePayloadParser parser,
            GameCatalogStore catalog) {
        this(client, parser, catalog, Clock.systemUTC());
    }

    GameRetrievalService(
            LichessGameClient client,
            GamePayloadParser parser,
            GameCatalogStore catalog,
            Clock clock) {
        this.client = client;
        this.parser = parser;
        this.catalog = catalog;
        this.clock = clock;
        this.retrievalRunning = new AtomicBoolean();
    }

    @Override
    public GameRetrievalResult retrieve(
            String accessToken,
            String accountId,
            String username,
            int count,
            Instant before) {
        if (!retrievalRunning.compareAndSet(false, true)) {
            return GameRetrievalResult.failed(GameRetrievalResult.Status.BUSY, null);
        }
        try {
            GameExportBatch batch = client.fetch(accessToken, username, count, before);
            var accepted = new ArrayList<CatalogGame>(batch.games().size());
            int rejected = batch.rejected();
            for (ExportedGame exportedGame : batch.games()) {
                try {
                    accepted.add(parser.parse(accountId, exportedGame));
                } catch (GamePayloadParser.GamePayloadException exception) {
                    rejected += 1;
                }
            }
            Instant retrievedAt = clock.instant();
            GameCatalogWriteResult writeResult = catalog.save(accountId, accepted, retrievedAt);
            return GameRetrievalResult.success(writeResult, rejected);
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
        } catch (DataAccessException exception) {
            return GameRetrievalResult.failed(
                    GameRetrievalResult.Status.STORAGE_UNAVAILABLE, null);
        } finally {
            retrievalRunning.set(false);
        }
    }
}
