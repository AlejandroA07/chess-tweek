package com.chesstweek.identity;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public final class LichessSessionService implements LichessSessionAuthorizer {

    public static final String COOKIE_NAME = "__Host-chess_tweek_session";

    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Clock clock;
    private final LichessAccountClient accountClient;
    private final LichessTokenRevoker tokenRevoker;
    private final SessionCookieSealer sessionCookieSealer;

    public LichessSessionService() {
        this(
                Clock.systemUTC(),
                new HttpLichessAccountClient(),
                new HttpLichessTokenRevoker(),
                new SessionCookieSealer(Path.of("var/lichess-cookie.key"), new SecureRandom()));
    }

    LichessSessionService(
            Clock clock,
            LichessAccountClient accountClient,
            LichessTokenRevoker tokenRevoker,
            SessionCookieSealer sessionCookieSealer) {
        this.clock = clock;
        this.accountClient = accountClient;
        this.tokenRevoker = tokenRevoker;
        this.sessionCookieSealer = sessionCookieSealer;
    }

    public Status status(String sealedSession) {
        SessionValidation validation = validateSession(sealedSession);
        return switch (validation.type()) {
            case DISCONNECTED -> Status.disconnected(validation.clearCookie());
            case RATE_LIMITED -> Status.unavailable();
            case UPSTREAM_UNAVAILABLE -> Status.unavailable();
            case CONNECTED -> {
                LichessAccount account = validation.account();
                yield Status.connected(new Account(
                        account.id(),
                        account.username(),
                        new Ratings(
                                account.ratings().bullet(),
                                account.ratings().blitz(),
                                account.ratings().rapid(),
                                account.ratings().classical(),
                                account.ratings().correspondence())));
            }
        };
    }

    @Override
    public LichessSessionAuthorization authorize(String sealedSession) {
        SessionValidation validation = validateSession(sealedSession);
        return switch (validation.type()) {
            case DISCONNECTED ->
                LichessSessionAuthorization.disconnected(validation.clearCookie());
            case RATE_LIMITED ->
                LichessSessionAuthorization.rateLimited(validation.retryAfterSeconds());
            case UPSTREAM_UNAVAILABLE -> LichessSessionAuthorization.unavailable();
            case CONNECTED -> LichessSessionAuthorization.connected(
                    validation.account().id(),
                    validation.account().username(),
                    validation.session().accessToken());
        };
    }

    private SessionValidation validateSession(String sealedSession) {
        if (sealedSession == null) {
            return SessionValidation.disconnected(false);
        }
        var opened = sessionCookieSealer.open(sealedSession, clock.instant());
        if (opened.isEmpty()) {
            return SessionValidation.disconnected(true);
        }

        try {
            var session = opened.orElseThrow();
            LichessAccount account = accountClient.currentAccount(session.accessToken());
            if (!isValid(account)) {
                return SessionValidation.unavailable();
            }
            return SessionValidation.connected(session, account);
        } catch (LichessAccountException exception) {
            if (exception.unauthorized()) {
                return SessionValidation.disconnected(true);
            }
            return exception.rateLimited()
                    ? SessionValidation.rateLimited(exception.retryAfterSeconds())
                    : SessionValidation.unavailable();
        }
    }

    public void logout(String sealedSession) {
        var opened = sessionCookieSealer.open(sealedSession, clock.instant());
        if (opened.isEmpty()) {
            return;
        }
        try {
            tokenRevoker.revoke(opened.orElseThrow().accessToken());
        } catch (LichessRevocationException exception) {
            // Local logout must succeed even when upstream revocation is unavailable.
        }
    }

    private static boolean isValid(LichessAccount account) {
        return account != null
                && account.id() != null
                && ACCOUNT_ID_PATTERN.matcher(account.id()).matches()
                && account.username() != null
                && USERNAME_PATTERN.matcher(account.username()).matches()
                && account.ratings() != null
                && validRating(account.ratings().bullet())
                && validRating(account.ratings().blitz())
                && validRating(account.ratings().rapid())
                && validRating(account.ratings().classical())
                && validRating(account.ratings().correspondence());
    }

    private static boolean validRating(Integer rating) {
        return rating == null || (rating >= 0 && rating <= 5_000);
    }

    private enum SessionValidationType {
        CONNECTED,
        DISCONNECTED,
        RATE_LIMITED,
        UPSTREAM_UNAVAILABLE
    }

    private record SessionValidation(
            SessionValidationType type,
            SessionCookieSealer.OpenedSession session,
            LichessAccount account,
            boolean clearCookie,
            Long retryAfterSeconds) {

        private static SessionValidation connected(
                SessionCookieSealer.OpenedSession session, LichessAccount account) {
            return new SessionValidation(
                    SessionValidationType.CONNECTED, session, account, false, null);
        }

        private static SessionValidation disconnected(boolean clearCookie) {
            return new SessionValidation(
                    SessionValidationType.DISCONNECTED, null, null, clearCookie, null);
        }

        private static SessionValidation rateLimited(Long retryAfterSeconds) {
            return new SessionValidation(
                    SessionValidationType.RATE_LIMITED,
                    null,
                    null,
                    false,
                    retryAfterSeconds);
        }

        private static SessionValidation unavailable() {
            return new SessionValidation(
                    SessionValidationType.UPSTREAM_UNAVAILABLE, null, null, false, null);
        }
    }

    public enum StatusType {
        CONNECTED,
        DISCONNECTED,
        UPSTREAM_UNAVAILABLE
    }

    public record Status(StatusType type, Account account, boolean clearCookie) {

        private static Status connected(Account account) {
            return new Status(StatusType.CONNECTED, account, false);
        }

        private static Status disconnected(boolean clearCookie) {
            return new Status(StatusType.DISCONNECTED, null, clearCookie);
        }

        private static Status unavailable() {
            return new Status(StatusType.UPSTREAM_UNAVAILABLE, null, false);
        }
    }

    public record Account(String id, String username, Ratings ratings) {}

    public record Ratings(
            Integer bullet,
            Integer blitz,
            Integer rapid,
            Integer classical,
            Integer correspondence) {}
}
