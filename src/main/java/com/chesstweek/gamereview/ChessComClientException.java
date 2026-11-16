package com.chesstweek.gamereview;

final class ChessComClientException extends RuntimeException {

    enum Reason {
        PLAYER_NOT_FOUND,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UNAVAILABLE
    }

    private final Reason reason;
    private final Long retryAfterSeconds;

    ChessComClientException(Reason reason, Long retryAfterSeconds, Throwable cause) {
        super("chess.com request failed: " + reason, cause);
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
