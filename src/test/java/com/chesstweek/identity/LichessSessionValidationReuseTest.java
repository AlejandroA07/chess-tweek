package com.chesstweek.identity;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LichessSessionValidationReuseTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final AtomicInteger accountCalls = new AtomicInteger();
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();

    @Test
    void reusesASuccessfulAccountCheckForSixtySecondsThenChecksAgain(@TempDir Path directory) {
        var fixture = fixture(directory);

        for (int poll = 0; poll < 60; poll++) {
            assertThat(fixture.service().authorize(fixture.cookie()).type())
                    .isEqualTo(LichessSessionAuthorization.Type.CONNECTED);
            clock.advance(Duration.ofMillis(900));
        }
        assertThat(accountCalls).hasValue(1);

        clock.advance(Duration.ofSeconds(10));
        fixture.service().authorize(fixture.cookie());
        assertThat(accountCalls).hasValue(2);
    }

    @Test
    void aRevokedTokenIsNoticedOnTheNextCheckAndNeverRemembered(@TempDir Path directory) {
        var fixture = fixture(directory);
        fixture.service().authorize(fixture.cookie());

        clock.advance(LichessSessionService.VALIDATION_REUSE);
        nextFailure.set(new LichessAccountException(true, null));
        LichessSessionAuthorization revoked = fixture.service().authorize(fixture.cookie());

        assertThat(revoked.type()).isEqualTo(LichessSessionAuthorization.Type.DISCONNECTED);
        assertThat(revoked.clearCookie()).isTrue();
        assertThat(fixture.service().authorize(fixture.cookie()).type())
                .isEqualTo(LichessSessionAuthorization.Type.CONNECTED);
        assertThat(accountCalls).hasValue(3);
    }

    @Test
    void failedChecksAreNotRemembered(@TempDir Path directory) {
        var fixture = fixture(directory);
        nextFailure.set(new LichessAccountException(
                LichessAccountException.Reason.RATE_LIMITED, 60L, null));

        assertThat(fixture.service().authorize(fixture.cookie()).type())
                .isEqualTo(LichessSessionAuthorization.Type.RATE_LIMITED);
        assertThat(fixture.service().authorize(fixture.cookie()).type())
                .isEqualTo(LichessSessionAuthorization.Type.CONNECTED);
        assertThat(accountCalls).hasValue(2);
    }

    @Test
    void loggingOutForgetsTheRememberedCheck(@TempDir Path directory) {
        var fixture = fixture(directory);
        fixture.service().authorize(fixture.cookie());

        fixture.service().logout(fixture.cookie());
        fixture.service().authorize(fixture.cookie());

        assertThat(accountCalls).hasValue(2);
    }

    private Fixture fixture(Path directory) {
        Path key = directory.resolve("runtime/lichess-cookie.key");
        String cookie = new SessionCookieSealer(key, new SecureRandom())
                .seal("liu_private_token_123", NOW.plusSeconds(86_400).getEpochSecond());
        LichessAccountClient accountClient = token -> {
            accountCalls.incrementAndGet();
            RuntimeException failure = nextFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return new LichessAccount("bobby", "Bobby", new LichessRatings(1755, 1623, 1824, 1756, 1648));
        };
        var service = new LichessSessionService(
                clock, accountClient, token -> {}, new SessionCookieSealer(key, new SecureRandom()));
        return new Fixture(service, cookie);
    }

    private record Fixture(LichessSessionService service, String cookie) {}

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
