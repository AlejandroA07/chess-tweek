package com.chesstweek.gamereview;

public record GameRetrievalResult(Status status, int received, Long retryAfterSeconds) {

    public enum Status {
        SUCCESS,
        UNAUTHORIZED,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UPSTREAM_UNAVAILABLE,
        BUSY
    }

    public static GameRetrievalResult success(int received) {
        return new GameRetrievalResult(Status.SUCCESS, received, null);
    }

    public static GameRetrievalResult failed(Status status, Long retryAfterSeconds) {
        return new GameRetrievalResult(status, 0, retryAfterSeconds);
    }
}
