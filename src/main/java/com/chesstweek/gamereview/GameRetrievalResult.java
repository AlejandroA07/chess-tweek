package com.chesstweek.gamereview;

public record GameRetrievalResult(
        Status status, int received, int rejected, Long retryAfterSeconds) {

    public enum Status {
        SUCCESS,
        UNAUTHORIZED,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UPSTREAM_UNAVAILABLE,
        STORAGE_UNAVAILABLE,
        BUSY
    }

    public static GameRetrievalResult success(int received, int rejected) {
        return new GameRetrievalResult(Status.SUCCESS, received, rejected, null);
    }

    public static GameRetrievalResult failed(Status status, Long retryAfterSeconds) {
        return new GameRetrievalResult(status, 0, 0, retryAfterSeconds);
    }
}
