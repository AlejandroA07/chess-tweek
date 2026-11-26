package com.chesstweek.training.internal.explorer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningExplorer;
import com.chesstweek.training.internal.persistence.ExplorerCacheStore;

/**
 * Serves explorer answers from the cache while they are fresh and otherwise asks Lichess, one
 * request at a time. After Lichess limits the rate, no request is sent until the wait is over;
 * meanwhile, and whenever Lichess cannot be reached, an expired cached answer is shown as stale.
 */
@Service
public class OpeningExplorerService implements OpeningExplorer {

    /** Masters games change slowly; the Lichess database grows every month. */
    static final Duration MASTERS_FRESH = Duration.ofDays(30);
    static final Duration LICHESS_FRESH = Duration.ofDays(7);
    static final int MAX_CACHE_ENTRIES = 20_000;
    private static final long DEFAULT_RETRY_SECONDS = 60;

    private final LichessExplorerClient client;
    private final ExplorerCacheStore cache;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ReentrantLock upstream = new ReentrantLock(true);
    private final AtomicReference<Instant> retryAllowedAt = new AtomicReference<>(Instant.EPOCH);

    @Autowired
    public OpeningExplorerService(LichessExplorerClient client, ExplorerCacheStore cache) {
        this(client, cache, Clock.systemUTC());
    }

    OpeningExplorerService(LichessExplorerClient client, ExplorerCacheStore cache, Clock clock) {
        this.client = client;
        this.cache = cache;
        this.clock = clock;
        this.objectMapper = JsonMapper.builder().build();
    }

    @Override
    public Answer masters(String accessToken, ExplorerPosition position) {
        Objects.requireNonNull(accessToken);
        Objects.requireNonNull(position);
        return answer(
                "masters",
                digest("masters|" + position.key()),
                MASTERS_FRESH,
                () -> client.masters(accessToken, position));
    }

    @Override
    public Answer lichess(
            String accessToken,
            ExplorerPosition position,
            Set<Speed> speeds,
            Set<RatingGroup> ratings) {
        Objects.requireNonNull(accessToken);
        Objects.requireNonNull(position);
        Set<Speed> checkedSpeeds = Set.copyOf(speeds);
        Set<RatingGroup> checkedRatings = Set.copyOf(ratings);
        String request = "lichess|" + position.key()
                + "|" + checkedSpeeds.stream().sorted().map(Speed::value).collect(Collectors.joining(","))
                + "|" + checkedRatings.stream()
                        .sorted(Comparator.comparingInt(RatingGroup::lowest))
                        .map(group -> Integer.toString(group.lowest()))
                        .collect(Collectors.joining(","));
        return answer(
                "lichess",
                digest(request),
                LICHESS_FRESH,
                () -> client.lichess(accessToken, position, checkedSpeeds, checkedRatings));
    }

    private Answer answer(
            String database, String cacheKey, Duration freshFor, Supplier<ExplorerAnswer> request) {
        Optional<Statistics> cached = cached(cacheKey);
        if (cached.isPresent() && isFresh(cached.get(), freshFor)) {
            return Answer.success(cached.get());
        }
        Long waiting = remainingRetryDelay();
        if (waiting != null) {
            return staleOr(cached, Answer.failed(Answer.Status.RATE_LIMITED, waiting));
        }
        upstream.lock();
        try {
            // Another request may have refreshed the entry or hit the rate limit meanwhile.
            cached = cached(cacheKey);
            if (cached.isPresent() && isFresh(cached.get(), freshFor)) {
                return Answer.success(cached.get());
            }
            waiting = remainingRetryDelay();
            if (waiting != null) {
                return staleOr(cached, Answer.failed(Answer.Status.RATE_LIMITED, waiting));
            }
            ExplorerAnswer fresh;
            try {
                fresh = request.get();
            } catch (ExplorerClientException exception) {
                return switch (exception.reason()) {
                    case UNAUTHORIZED -> Answer.failed(Answer.Status.UNAUTHORIZED, null);
                    case RATE_LIMITED -> staleOr(
                            cached,
                            Answer.failed(
                                    Answer.Status.RATE_LIMITED,
                                    waitAfterRateLimit(exception.retryAfterSeconds())));
                    case INVALID_RESPONSE -> staleOr(
                            cached, Answer.failed(Answer.Status.INVALID_RESPONSE, null));
                    case UNAVAILABLE -> staleOr(cached, Answer.failed(Answer.Status.UNAVAILABLE, null));
                };
            }
            Instant fetchedAt = clock.instant();
            store(cacheKey, database, fresh, fetchedAt);
            return Answer.success(fresh.fetchedAt(fetchedAt));
        } finally {
            upstream.unlock();
        }
    }

    private Optional<Statistics> cached(String cacheKey) {
        Optional<ExplorerCacheStore.Entry> entry;
        try {
            entry = cache.find(cacheKey);
        } catch (DataAccessException exception) {
            return Optional.empty();
        }
        return entry.flatMap(found -> {
            try {
                return Optional.of(objectMapper
                        .readValue(found.responseJson(), ExplorerAnswer.class)
                        .fetchedAt(found.fetchedAt()));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        });
    }

    private void store(String cacheKey, String database, ExplorerAnswer answer, Instant fetchedAt) {
        try {
            cache.put(
                    cacheKey,
                    database,
                    objectMapper.writeValueAsString(answer),
                    fetchedAt,
                    MAX_CACHE_ENTRIES);
        } catch (DataAccessException | IllegalArgumentException exception) {
            // The answer is still shown; it is fetched again next time.
        }
    }

    private boolean isFresh(Statistics statistics, Duration freshFor) {
        Instant now = clock.instant();
        return !statistics.fetchedAt().isAfter(now)
                && Duration.between(statistics.fetchedAt(), now).compareTo(freshFor) < 0;
    }

    private static Answer staleOr(Optional<Statistics> cached, Answer failure) {
        return cached.map(statistics -> Answer.success(statistics.asStale())).orElse(failure);
    }

    private long waitAfterRateLimit(Long retryAfterSeconds) {
        long delay = retryAfterSeconds == null
                ? DEFAULT_RETRY_SECONDS
                : Math.max(retryAfterSeconds, DEFAULT_RETRY_SECONDS);
        Instant allowedAt;
        try {
            allowedAt = clock.instant().plusSeconds(delay);
        } catch (ArithmeticException | DateTimeException exception) {
            allowedAt = Instant.MAX;
        }
        retryAllowedAt.accumulateAndGet(
                allowedAt, (existing, candidate) -> existing.isAfter(candidate) ? existing : candidate);
        return delay;
    }

    private Long remainingRetryDelay() {
        Duration remaining = Duration.between(clock.instant(), retryAllowedAt.get());
        if (remaining.isNegative() || remaining.isZero()) {
            return null;
        }
        long seconds = remaining.getSeconds();
        return remaining.getNano() == 0 ? seconds : Math.addExact(seconds, 1);
    }

    private static String digest(String request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(request.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
