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

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public final class LichessAuthorizationService {

    private static final URI AUTHORIZATION_ENDPOINT = URI.create("https://lichess.org/oauth");
    private static final String CLIENT_ID = "chess-tweek-macos-local";
    private static final String REDIRECT_URI = "http://127.0.0.1:8080/api/lichess/callback";
    private static final String APPROVED_SCOPE = "puzzle:read";
    private static final Duration ATTEMPT_LIFETIME = Duration.ofMinutes(5);

    private final SecureRandom secureRandom;
    private final Clock clock;
    private final AtomicReference<PendingAuthorization> pendingAuthorization = new AtomicReference<>();

    public LichessAuthorizationService() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    LichessAuthorizationService(SecureRandom secureRandom, Clock clock) {
        this.secureRandom = secureRandom;
        this.clock = clock;
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
}
