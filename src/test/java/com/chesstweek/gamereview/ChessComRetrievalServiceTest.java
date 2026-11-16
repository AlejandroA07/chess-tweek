package com.chesstweek.gamereview;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import tools.jackson.databind.JsonNode;

import com.chesstweek.gamereview.internal.persistence.GameCatalogStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChessComRetrievalServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    private final Players players = new Players("OwnerName");
    private final Client client = new Client();
    private final Catalog catalog = new Catalog();
    private final ChessComRetrievalService service = new ChessComRetrievalService(
            players, client, new ChessComGameParser(), catalog, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void keepsTheNewestGamesAcrossMonthsAndStopsOnceItHasEnough() {
        client.months.put(NOVEMBER, List.of(game(101, "2026-11-02T10:00:00Z"), game(102, "2026-11-05T10:00:00Z")));
        client.months.put(OCTOBER, List.of(game(91, "2026-10-20T10:00:00Z"), game(92, "2026-10-28T10:00:00Z")));
        client.months.put(MARCH, List.of(game(31, "2026-03-01T10:00:00Z")));

        ChessComRetrievalResult result = service.retrieve("owner-id", 3, null);

        assertThat(result.status()).isEqualTo(ChessComRetrievalResult.Status.SUCCESS);
        assertThat(result.received()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(3);
        assertThat(catalog.saved).extracting(game -> game.ref().id())
                .containsExactly("102", "101", "92");
        assertThat(client.requests).containsExactly(
                "archives OwnerName", "month OwnerName 2026-11", "month OwnerName 2026-10");
        assertThat(catalog.retrievedAt).isEqualTo(NOW);
    }

    @Test
    void takesOnlyGamesThatEndedBeforeTheDateAndSkipsLaterMonths() {
        client.months.put(NOVEMBER, List.of(game(101, "2026-11-02T10:00:00Z")));
        client.months.put(OCTOBER, List.of(game(91, "2026-10-20T10:00:00Z"), game(92, "2026-10-28T10:00:00Z")));

        ChessComRetrievalResult result = service.retrieve(
                "owner-id", 5, Instant.parse("2026-10-25T00:00:00Z"));

        assertThat(result.received()).isEqualTo(1);
        assertThat(catalog.saved).extracting(game -> game.ref().id()).containsExactly("91");
        assertThat(client.requests).containsExactly("archives OwnerName", "month OwnerName 2026-10");
    }

    @Test
    void doesNotReadTheMonthThatStartsOnTheBeforeDate() {
        client.months.put(NOVEMBER, List.of(game(101, "2026-11-02T10:00:00Z")));
        client.months.put(OCTOBER, List.of(game(91, "2026-10-31T23:59:00Z")));

        ChessComRetrievalResult result = service.retrieve(
                "owner-id", 5, Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(catalog.saved).extracting(game -> game.ref().id()).containsExactly("91");
        assertThat(client.requests).containsExactly("archives OwnerName", "month OwnerName 2026-10");
        assertThat(result.received()).isEqualTo(1);
    }

    @Test
    void countsGamesItCannotStoreAsRejectedAndKeepsTheRest() {
        JsonNode variant = ChessComGameParserTest.game(Map.of(
                "url", "https://www.chess.com/game/live/103",
                "rules", "crazyhouse",
                "end_time", Instant.parse("2026-11-06T10:00:00Z").getEpochSecond()));
        client.months.put(NOVEMBER, List.of(variant, game(101, "2026-11-02T10:00:00Z")));

        ChessComRetrievalResult result = service.retrieve("owner-id", 5, null);

        assertThat(result.received()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(1);
    }

    @Test
    void readsAtMostTwoYearsOfMonthsPerRetrieval() {
        for (int month = 0; month < 30; month++) {
            client.months.put(NOVEMBER.minusMonths(month), List.of());
        }

        ChessComRetrievalResult result = service.retrieve("owner-id", 50, null);

        assertThat(result.received()).isZero();
        assertThat(client.requests).hasSize(1 + ChessComRetrievalService.MAX_MONTH_REQUESTS);
    }

    @Test
    void asksForAUsernameBeforeContactingChessCom() {
        players.username = null;

        assertThat(service.retrieve("owner-id", 5, null).status())
                .isEqualTo(ChessComRetrievalResult.Status.USERNAME_MISSING);
        assertThat(client.requests).isEmpty();
    }

    @Test
    void reportsChessComFailuresWithoutStoringAnything() {
        assertFailure(ChessComClientException.Reason.PLAYER_NOT_FOUND, null,
                ChessComRetrievalResult.Status.PLAYER_NOT_FOUND, null);
        assertFailure(ChessComClientException.Reason.RATE_LIMITED, 30L,
                ChessComRetrievalResult.Status.RATE_LIMITED, 30L);
        assertFailure(ChessComClientException.Reason.RATE_LIMITED, null,
                ChessComRetrievalResult.Status.RATE_LIMITED, 60L);
        assertFailure(ChessComClientException.Reason.INVALID_RESPONSE, null,
                ChessComRetrievalResult.Status.INVALID_RESPONSE, null);
        assertFailure(ChessComClientException.Reason.UNAVAILABLE, null,
                ChessComRetrievalResult.Status.UPSTREAM_UNAVAILABLE, null);
        assertThat(catalog.saved).isEmpty();
    }

    @Test
    void reportsStorageFailuresAndRejectsAnInvalidCount() {
        client.months.put(NOVEMBER, List.of(game(101, "2026-11-02T10:00:00Z")));
        catalog.fail = true;

        assertThat(service.retrieve("owner-id", 5, null).status())
                .isEqualTo(ChessComRetrievalResult.Status.STORAGE_UNAVAILABLE);
        assertThatThrownBy(() -> service.retrieve("owner-id", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.retrieve("owner-id", 51, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertFailure(
            ChessComClientException.Reason reason,
            Long retryAfter,
            ChessComRetrievalResult.Status status,
            Long expectedRetryAfter) {
        client.failure = new ChessComClientException(reason, retryAfter, null);
        ChessComRetrievalResult result = service.retrieve("owner-id", 5, null);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.retryAfterSeconds()).isEqualTo(expectedRetryAfter);
        assertThat(result.received()).isZero();
    }

    static JsonNode game(long id, String endedAt) {
        return ChessComGameParserTest.game(Map.of(
                "url", "https://www.chess.com/game/live/" + id,
                "end_time", Instant.parse(endedAt).getEpochSecond()));
    }

    private static final class Players implements ChessComPlayers {

        private String username;

        Players(String username) {
            this.username = username;
        }

        @Override
        public Optional<String> username(String accountId) {
            return Optional.ofNullable(username);
        }

        @Override
        public void save(String accountId, String username) {
            this.username = username;
        }

        @Override
        public void remove(String accountId) {
            username = null;
        }
    }

    private static final class Client implements ChessComGameClient {

        private final Map<YearMonth, List<JsonNode>> months = new LinkedHashMap<>();
        private final List<String> requests = new ArrayList<>();
        private ChessComClientException failure;

        @Override
        public List<YearMonth> archiveMonths(String username) {
            requests.add("archives " + username);
            if (failure != null) {
                throw failure;
            }
            return months.keySet().stream().sorted(java.util.Comparator.reverseOrder()).toList();
        }

        @Override
        public List<JsonNode> monthGames(String username, YearMonth month) {
            requests.add("month " + username + " " + month);
            return months.get(month);
        }
    }

    private static final class Catalog implements GameCatalogStore {

        private final List<CatalogGame> saved = new ArrayList<>();
        private final Map<GameRef, CatalogGame> stored = new HashMap<>();
        private Instant retrievedAt;
        private boolean fail;

        @Override
        public GameCatalogWriteResult save(String accountId, List<CatalogGame> games, Instant at) {
            if (fail) {
                throw new DataAccessResourceFailureException("offline");
            }
            retrievedAt = at;
            int created = 0;
            for (CatalogGame game : games) {
                if (stored.put(game.ref(), game) == null) {
                    created += 1;
                }
            }
            saved.addAll(games);
            return new GameCatalogWriteResult(created, games.size() - created, 0);
        }
    }
}
