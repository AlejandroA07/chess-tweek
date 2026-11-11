package com.chesstweek.gamereview;

final class LichessGameClientException extends RuntimeException {

    enum Reason {
        UNAUTHORIZED,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UNAVAILABLE
    }

    private final Reason reason;
    private final Long retryAfterSeconds;

    LichessGameClientException(Reason reason, Long retryAfterSeconds, Throwable cause) {
        super(cause);
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    Reason reason() {
        return reason;
    }

    Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
