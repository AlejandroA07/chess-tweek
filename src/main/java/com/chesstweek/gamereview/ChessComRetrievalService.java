package com.chesstweek.gamereview;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.chesstweek.gamereview.internal.persistence.GameCatalogStore;

/**
 * Reads archive months newest first, one request at a time, and keeps the newest games until it
 * has the requested count. At most {@link #MAX_MONTH_REQUESTS} months are read per retrieval; an
 * older batch is reached with the before date.
 */
@Service
public final class ChessComRetrievalService implements ChessComRetrieval {

    static final int MAX_MONTH_REQUESTS = 24;

    private final ChessComPlayers players;
    private final ChessComGameClient client;
    private final ChessComGameParser parser;
    private final GameCatalogStore catalog;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    ChessComRetrievalService(
            ChessComPlayers players,
            ChessComGameClient client,
            ChessComGameParser parser,
            GameCatalogStore catalog) {
        this(players, client, parser, catalog, Clock.systemUTC());
    }

    ChessComRetrievalService(
            ChessComPlayers players,
            ChessComGameClient client,
            ChessComGameParser parser,
            GameCatalogStore catalog,
            Clock clock) {
        this.players = players;
        this.client = client;
        this.parser = parser;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public ChessComRetrievalResult retrieve(String accountId, int count, Instant before) {
        if (count < 1 || count > MAX_GAMES) {
            throw new IllegalArgumentException("Invalid chess.com game count");
        }
        if (!running.compareAndSet(false, true)) {
            return ChessComRetrievalResult.failed(ChessComRetrievalResult.Status.BUSY, null);
        }
        try {
            String username = players.username(accountId).orElse(null);
            if (username == null) {
                return ChessComRetrievalResult.failed(
                        ChessComRetrievalResult.Status.USERNAME_MISSING, null);
            }
            var accepted = new ArrayList<CatalogGame>(count);
            int rejected = 0;
            int requests = 0;
            for (YearMonth month : client.archiveMonths(username)) {
                if (accepted.size() >= count || requests >= MAX_MONTH_REQUESTS) {
                    break;
                }
                if (before != null && month.isAfter(YearMonth.from(before.atOffset(ZoneOffset.UTC)))) {
                    continue;
                }
                List<JsonNode> games = new ArrayList<>(client.monthGames(username, month));
                requests += 1;
                games.sort(Comparator.comparingLong(ChessComRetrievalService::endTime).reversed());
                for (JsonNode game : games) {
                    if (accepted.size() >= count) {
                        break;
                    }
                    long endTime = endTime(game);
                    if (before != null && endTime >= before.getEpochSecond()) {
                        continue;
                    }
                    try {
                        accepted.add(parser.parse(username, game));
                    } catch (ChessComGameParser.InvalidGameException exception) {
                        rejected += 1;
                    }
                }
            }
            GameCatalogWriteResult written = catalog.save(accountId, accepted, clock.instant());
            return ChessComRetrievalResult.success(written, rejected);
        } catch (ChessComClientException exception) {
            return switch (exception.reason()) {
                case PLAYER_NOT_FOUND -> ChessComRetrievalResult.failed(
                        ChessComRetrievalResult.Status.PLAYER_NOT_FOUND, null);
                case RATE_LIMITED -> ChessComRetrievalResult.failed(
                        ChessComRetrievalResult.Status.RATE_LIMITED,
                        exception.retryAfterSeconds() == null ? 60L : exception.retryAfterSeconds());
                case INVALID_RESPONSE -> ChessComRetrievalResult.failed(
                        ChessComRetrievalResult.Status.INVALID_RESPONSE, null);
                case UNAVAILABLE -> ChessComRetrievalResult.failed(
                        ChessComRetrievalResult.Status.UPSTREAM_UNAVAILABLE, null);
            };
        } catch (DataAccessException exception) {
            return ChessComRetrievalResult.failed(
                    ChessComRetrievalResult.Status.STORAGE_UNAVAILABLE, null);
        } finally {
            running.set(false);
        }
    }

    /** A game without a readable end time sorts last and is rejected by the parser. */
    private static long endTime(JsonNode game) {
        JsonNode value = game == null ? null : game.get("end_time");
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                ? value.longValue()
                : Long.MIN_VALUE;
    }
}
