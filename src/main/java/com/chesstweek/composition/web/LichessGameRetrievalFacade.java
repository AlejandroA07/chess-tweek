package com.chesstweek.composition.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chesstweek.gamereview.GameRetrieval;
import com.chesstweek.gamereview.GameRetrievalResult;
import com.chesstweek.identity.LichessSessionAuthorizer;

@Component
final class LichessGameRetrievalFacade {

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final GameRetrieval gameRetrieval;
    private final Clock clock;
    private final AtomicBoolean retrievalRunning;
    private final AtomicReference<Instant> retryAllowedAt;

    @Autowired
    LichessGameRetrievalFacade(
            LichessSessionAuthorizer sessionAuthorizer,
            GameRetrieval gameRetrieval) {
        this(sessionAuthorizer, gameRetrieval, Clock.systemUTC());
    }

    LichessGameRetrievalFacade(
            LichessSessionAuthorizer sessionAuthorizer,
            GameRetrieval gameRetrieval,
            Clock clock) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.gameRetrieval = gameRetrieval;
        this.clock = clock;
        this.retrievalRunning = new AtomicBoolean();
        this.retryAllowedAt = new AtomicReference<>(Instant.EPOCH);
    }

    Outcome retrieve(String sealedSession, int count, Instant before) {
        Long retryAfterSeconds = remainingRetryDelay();
        if (retryAfterSeconds != null) {
            return Outcome.failed(Status.RATE_LIMITED, retryAfterSeconds);
        }
        if (!retrievalRunning.compareAndSet(false, true)) {
            return Outcome.failed(Status.BUSY, null);
        }

        try {
            retryAfterSeconds = remainingRetryDelay();
            if (retryAfterSeconds != null) {
                return Outcome.failed(Status.RATE_LIMITED, retryAfterSeconds);
            }
            var authorization = sessionAuthorizer.authorize(sealedSession);
            return switch (authorization.type()) {
                case DISCONNECTED -> Outcome.notConnected(authorization.clearCookie());
                case RATE_LIMITED -> rateLimited(authorization.retryAfterSeconds());
                case UPSTREAM_UNAVAILABLE -> Outcome.failed(Status.UPSTREAM_UNAVAILABLE, null);
                case CONNECTED -> authorization.useAccessToken(accessToken -> mapResult(
                        gameRetrieval.retrieve(
                                accessToken, authorization.username(), count, before)));
            };
        } finally {
            retrievalRunning.set(false);
        }
    }

    private Outcome mapResult(GameRetrievalResult result) {
        return switch (result.status()) {
            case SUCCESS -> Outcome.success(result.received());
            case UNAUTHORIZED -> Outcome.notConnected(true);
            case RATE_LIMITED -> rateLimited(result.retryAfterSeconds());
            case INVALID_RESPONSE -> Outcome.failed(Status.INVALID_RESPONSE, null);
            case UPSTREAM_UNAVAILABLE -> Outcome.failed(Status.UPSTREAM_UNAVAILABLE, null);
            case BUSY -> Outcome.failed(Status.BUSY, null);
        };
    }

    private Outcome rateLimited(Long retryAfterSeconds) {
        long delay = retryAfterSeconds == null ? 60 : retryAfterSeconds;
        Instant allowedAt;
        try {
            allowedAt = clock.instant().plusSeconds(delay);
        } catch (ArithmeticException | java.time.DateTimeException exception) {
            allowedAt = Instant.MAX;
        }
        retryAllowedAt.accumulateAndGet(
                allowedAt,
                (existing, candidate) -> existing.isAfter(candidate) ? existing : candidate);
        return Outcome.failed(Status.RATE_LIMITED, delay);
    }

    private Long remainingRetryDelay() {
        Duration remaining = Duration.between(clock.instant(), retryAllowedAt.get());
        if (remaining.isNegative() || remaining.isZero()) {
            return null;
        }
        long seconds = remaining.getSeconds();
        return remaining.getNano() == 0 ? seconds : Math.addExact(seconds, 1);
    }

    enum Status {
        SUCCESS,
        NOT_CONNECTED,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UPSTREAM_UNAVAILABLE,
        BUSY
    }

    record Outcome(Status status, int received, Long retryAfterSeconds, boolean clearCookie) {

        private static Outcome success(int received) {
            return new Outcome(Status.SUCCESS, received, null, false);
        }

        private static Outcome notConnected(boolean clearCookie) {
            return new Outcome(Status.NOT_CONNECTED, 0, null, clearCookie);
        }

        private static Outcome failed(Status status, Long retryAfterSeconds) {
            return new Outcome(status, 0, retryAfterSeconds, false);
        }
    }
}
