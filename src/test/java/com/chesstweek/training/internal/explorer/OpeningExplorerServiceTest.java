package com.chesstweek.training.internal.explorer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningExplorer;
import com.chesstweek.training.OpeningExplorer.Answer;
import com.chesstweek.training.internal.persistence.ExplorerCacheStore;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningExplorerServiceTest {

    private static final ExplorerPosition START = ExplorerPosition.parse(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").orElseThrow();
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    @Test
    void servesAFreshCachedAnswerWithoutAskingLichess() {
        var client = new FakeClient();
        var cache = new MemoryCache();
        var clock = new MutableClock(NOW);
        var service = new OpeningExplorerService(client, cache, clock);

        Answer first = service.masters("token", START);
        clock.advance(Duration.ofDays(29));
        Answer second = service.masters("other-token", START);

        assertThat(first.status()).isEqualTo(Answer.Status.SUCCESS);
        assertThat(second.statistics().results()).isEqualTo(first.statistics().results());
        assertThat(second.statistics().fetchedAt()).isEqualTo(NOW);
        assertThat(second.statistics().stale()).isFalse();
        assertThat(client.calls).containsExactly("masters token");
    }

    @Test
    void refreshesAnExpiredAnswer() {
        var client = new FakeClient();
        var clock = new MutableClock(NOW);
        var service = new OpeningExplorerService(client, new MemoryCache(), clock);

        service.lichess("token", START, Set.of(), Set.of());
        clock.advance(Duration.ofDays(7));
        Answer refreshed = service.lichess("token", START, Set.of(), Set.of());

        assertThat(client.calls).hasSize(2);
        assertThat(refreshed.statistics().fetchedAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void cachesEachDatabaseAndFilterSeparately() {
        var client = new FakeClient();
        var service = new OpeningExplorerService(client, new MemoryCache(), new MutableClock(NOW));

        service.masters("token", START);
        service.lichess("token", START, Set.of(), Set.of());
        service.lichess("token", START, Set.of(OpeningExplorer.Speed.RAPID), Set.of());
        service.lichess(
                "token",
                START,
                Set.of(OpeningExplorer.Speed.RAPID),
                Set.of(OpeningExplorer.RatingGroup.R1600));
        service.lichess(
                "token",
                START,
                Set.of(OpeningExplorer.Speed.RAPID),
                Set.of(OpeningExplorer.RatingGroup.R1600));

        assertThat(client.calls).hasSize(4);
    }

    @Test
    void showsAnExpiredAnswerAsStaleWhenLichessCannotBeReached() {
        var client = new FakeClient();
        var clock = new MutableClock(NOW);
        var service = new OpeningExplorerService(client, new MemoryCache(), clock);
        service.masters("token", START);
        clock.advance(Duration.ofDays(31));
        client.failure = ExplorerClientException.Reason.UNAVAILABLE;

        Answer answer = service.masters("token", START);

        assertThat(answer.status()).isEqualTo(Answer.Status.SUCCESS);
        assertThat(answer.statistics().stale()).isTrue();
        assertThat(answer.statistics().fetchedAt()).isEqualTo(NOW);
    }

    @Test
    void reportsAFailureWhenNothingIsCached() {
        var client = new FakeClient();
        client.failure = ExplorerClientException.Reason.INVALID_RESPONSE;
        var service = new OpeningExplorerService(client, new MemoryCache(), new MutableClock(NOW));

        assertThat(service.masters("token", START).status()).isEqualTo(Answer.Status.INVALID_RESPONSE);
        client.failure = ExplorerClientException.Reason.UNAVAILABLE;
        assertThat(service.masters("token", START).status()).isEqualTo(Answer.Status.UNAVAILABLE);
    }

    @Test
    void sendsNothingUntilTheRateLimitWaitIsOver() {
        var client = new FakeClient();
        var clock = new MutableClock(NOW);
        var service = new OpeningExplorerService(client, new MemoryCache(), clock);
        client.failure = ExplorerClientException.Reason.RATE_LIMITED;
        client.retryAfter = 90L;

        Answer limited = service.masters("token", START);
        client.failure = null;
        clock.advance(Duration.ofSeconds(30));
        Answer waiting = service.lichess("token", START, Set.of(), Set.of());
        clock.advance(Duration.ofSeconds(60));
        Answer resumed = service.masters("token", START);

        assertThat(limited.status()).isEqualTo(Answer.Status.RATE_LIMITED);
        assertThat(limited.retryAfterSeconds()).isEqualTo(90);
        assertThat(waiting.status()).isEqualTo(Answer.Status.RATE_LIMITED);
        assertThat(waiting.retryAfterSeconds()).isEqualTo(60);
        assertThat(resumed.status()).isEqualTo(Answer.Status.SUCCESS);
        assertThat(client.calls).hasSize(2);
    }

    @Test
    void waitsAtLeastAMinuteAfterARateLimitWithoutRetryAfter() {
        var client = new FakeClient();
        var clock = new MutableClock(NOW);
        var service = new OpeningExplorerService(client, new MemoryCache(), clock);
        client.failure = ExplorerClientException.Reason.RATE_LIMITED;
        client.retryAfter = 5L;

        assertThat(service.masters("token", START).retryAfterSeconds()).isEqualTo(60);
        clock.advance(Duration.ofSeconds(10));
        client.failure = null;
        assertThat(service.masters("token", START).status()).isEqualTo(Answer.Status.RATE_LIMITED);
        assertThat(client.calls).hasSize(1);
    }

    @Test
    void neverCachesARefusal() {
        var client = new FakeClient();
        client.failure = ExplorerClientException.Reason.UNAUTHORIZED;
        var cache = new MemoryCache();
        var service = new OpeningExplorerService(client, cache, new MutableClock(NOW));

        assertThat(service.masters("token", START).status()).isEqualTo(Answer.Status.UNAUTHORIZED);
        assertThat(cache.entries).isEmpty();
    }

    @Test
    void treatsAnUnreadableCacheEntryAsMissingAndStoresNoToken() {
        var client = new FakeClient();
        var cache = new MemoryCache();
        var service = new OpeningExplorerService(client, cache, new MutableClock(NOW));
        service.masters("liu_secret_token", START);
        cache.entries.replaceAll((key, entry) -> new ExplorerCacheStore.Entry("{broken", entry.fetchedAt()));

        Answer answer = service.masters("liu_secret_token", START);

        assertThat(answer.status()).isEqualTo(Answer.Status.SUCCESS);
        assertThat(client.calls).hasSize(2);
        assertThat(cache.entries.values()).allSatisfy(entry ->
                assertThat(entry.responseJson()).doesNotContain("liu_secret_token"));
    }

    @Test
    void givesUpWaitingBehindASlowUpstreamRequest() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var slow = new FakeClient() {
            @Override
            public ExplorerAnswer masters(String accessToken, ExplorerPosition position) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return super.masters(accessToken, position);
            }
        };
        var service = new OpeningExplorerService(
                slow, new MemoryCache(), new MutableClock(NOW), Duration.ofMillis(50));
        var first = new Thread(() -> service.masters("token", START));
        first.start();
        entered.await();

        Answer waiting = service.lichess("token", START, Set.of(), Set.of());

        release.countDown();
        first.join();
        assertThat(waiting.status()).isEqualTo(Answer.Status.UNAVAILABLE);
        assertThat(slow.calls).containsExactly("masters token");
    }

    private static class FakeClient implements LichessExplorerClient {

        final List<String> calls = new ArrayList<>();
        private ExplorerClientException.Reason failure;
        private Long retryAfter;

        @Override
        public ExplorerAnswer masters(String accessToken, ExplorerPosition position) {
            calls.add("masters " + accessToken);
            return answer();
        }

        @Override
        public ExplorerAnswer lichess(
                String accessToken,
                ExplorerPosition position,
                Set<OpeningExplorer.Speed> speeds,
                Set<OpeningExplorer.RatingGroup> ratings) {
            calls.add("lichess " + speeds + " " + ratings);
            return answer();
        }

        private ExplorerAnswer answer() {
            if (failure != null) {
                throw new ExplorerClientException(failure, retryAfter, null);
            }
            return new ExplorerAnswer(
                    new OpeningExplorer.Opening("A00", "Start"),
                    new OpeningExplorer.Results(10, 5, 8),
                    List.of(new OpeningExplorer.NextMove(
                            "e2e4",
                            "e4",
                            2400,
                            new OpeningExplorer.Results(6, 2, 4),
                            new OpeningExplorer.Opening("B00", "King's Pawn"))),
                    List.of(new OpeningExplorer.TopGame(
                            "abcdefgh",
                            "e2e4",
                            new OpeningExplorer.Player("White Player", 2700, "GM"),
                            new OpeningExplorer.Player("Black Player", 2650, null),
                            null,
                            2020)));
        }
    }

    private static final class MemoryCache implements ExplorerCacheStore {

        private final Map<String, Entry> entries = new HashMap<>();

        @Override
        public Optional<Entry> find(String cacheKey) {
            return Optional.ofNullable(entries.get(cacheKey));
        }

        @Override
        public void put(
                String cacheKey,
                String database,
                String responseJson,
                Instant fetchedAt,
                int maxEntries) {
            entries.put(cacheKey, new Entry(responseJson, fetchedAt));
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
