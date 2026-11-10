package com.chesstweek.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public final class LichessAuthorizationService {

    private static final URI AUTHORIZATION_ENDPOINT = URI.create("https://lichess.org/oauth");
    private static final String CLIENT_ID = "chess-tweek-macos-local";
    private static final String REDIRECT_URI = "http://127.0.0.1:8080/api/lichess/callback";
    private static final String APPROVED_SCOPE = "puzzle:read";
    private static final Duration ATTEMPT_LIFETIME = Duration.ofMinutes(5);
    private static final Pattern STATE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,512}");

    private final SecureRandom secureRandom;
    private final Clock clock;
    private final LichessTokenClient tokenClient;
    private final SessionCookieSealer sessionCookieSealer;
    private final AtomicReference<PendingAuthorization> pendingAuthorization = new AtomicReference<>();

    public LichessAuthorizationService() {
        this(
                new SecureRandom(),
                Clock.systemUTC(),
                new HttpLichessTokenClient(),
                new SessionCookieSealer(java.nio.file.Path.of("var/lichess-cookie.key"), new SecureRandom()));
    }

    LichessAuthorizationService(
            SecureRandom secureRandom,
            Clock clock,
            LichessTokenClient tokenClient,
            SessionCookieSealer sessionCookieSealer) {
        this.secureRandom = secureRandom;
        this.clock = clock;
        this.tokenClient = tokenClient;
        this.sessionCookieSealer = sessionCookieSealer;
    }

    public URI beginAuthorization() {
        String state = randomBase64Url(32);
        String verifier = randomBase64Url(64);
        String challenge = sha256Base64Url(verifier);
        Instant expiresAt = clock.instant().plus(ATTEMPT_LIFETIME);

        pendingAuthorization.set(new PendingAuthorization(state, verifier, expiresAt));

        return UriComponentsBuilder.fromUri(AUTHORIZATION_ENDPOINT)
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("scope", APPROVED_SCOPE)
                .queryParam("state", state)
                .build()
                .toUri();
    }

    public Completion completeAuthorization(String code, String state) {
        PendingAuthorization pending = consumePendingAuthorization(state);
        if (pending == null || code == null || !CODE_PATTERN.matcher(code).matches()) {
            return Completion.invalid();
        }

        try {
            LichessToken token = tokenClient.exchange(code, pending.verifier());
            if (!token.isValid()) {
                return Completion.unavailable();
            }
            long expiresAt = clock.instant().plusSeconds(token.expiresInSeconds()).getEpochSecond();
            String sealedSession = sessionCookieSealer.seal(token.accessToken(), expiresAt);
            return Completion.success(sealedSession, token.expiresInSeconds());
        } catch (LichessTokenExchangeException exception) {
            return exception.authorizationRejected() ? Completion.invalid() : Completion.unavailable();
        } catch (SessionSealingException exception) {
            return Completion.unavailable();
        }
    }

    public boolean cancelAuthorization(String state) {
        return consumePendingAuthorization(state) != null;
    }

    private PendingAuthorization consumePendingAuthorization(String state) {
        if (state == null || !STATE_PATTERN.matcher(state).matches()) {
            return null;
        }

        while (true) {
            PendingAuthorization pending = pendingAuthorization.get();
            if (pending == null) {
                return null;
            }
            if (!pending.expiresAt().isAfter(clock.instant())) {
                pendingAuthorization.compareAndSet(pending, null);
                return null;
            }
            if (!MessageDigest.isEqual(
                    pending.state().getBytes(StandardCharsets.US_ASCII),
                    state.getBytes(StandardCharsets.US_ASCII))) {
                return null;
            }
            if (pendingAuthorization.compareAndSet(pending, null)) {
                return pending;
            }
        }
    }

    private String randomBase64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record PendingAuthorization(String state, String verifier, Instant expiresAt) {}

    public enum CompletionStatus {
        SUCCESS,
        INVALID_CALLBACK,
        UPSTREAM_UNAVAILABLE
    }

    public record Completion(CompletionStatus status, String sealedSession, long maxAgeSeconds) {

        private static Completion success(String sealedSession, long maxAgeSeconds) {
            return new Completion(CompletionStatus.SUCCESS, sealedSession, maxAgeSeconds);
        }

        private static Completion invalid() {
            return new Completion(CompletionStatus.INVALID_CALLBACK, null, 0);
        }

        private static Completion unavailable() {
            return new Completion(CompletionStatus.UPSTREAM_UNAVAILABLE, null, 0);
        }
    }
}
