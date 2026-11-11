package com.chesstweek.identity;

final class LichessAccountException extends RuntimeException {

    enum Reason {
        UNAUTHORIZED,
        RATE_LIMITED,
        UNAVAILABLE
    }

    private final Reason reason;
    private final Long retryAfterSeconds;

    LichessAccountException(boolean unauthorized, Throwable cause) {
        this(unauthorized ? Reason.UNAUTHORIZED : Reason.UNAVAILABLE, null, cause);
    }

    LichessAccountException(Reason reason, Long retryAfterSeconds, Throwable cause) {
        super("Lichess account request failed", cause);
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    boolean unauthorized() {
        return reason == Reason.UNAUTHORIZED;
    }

    boolean rateLimited() {
        return reason == Reason.RATE_LIMITED;
    }

    Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
