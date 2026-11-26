package com.chesstweek.training.internal.explorer;

/** Why an explorer request failed. Messages never contain the token or upstream body. */
public final class ExplorerClientException extends RuntimeException {

    public enum Reason {
        UNAUTHORIZED,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UNAVAILABLE
    }

    private final Reason reason;
    private final Long retryAfterSeconds;

    public ExplorerClientException(Reason reason, Long retryAfterSeconds, Throwable cause) {
        super("Lichess explorer request failed: " + reason, cause);
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Reason reason() {
        return reason;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
