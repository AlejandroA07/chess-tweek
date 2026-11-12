package com.chesstweek.gamereview;

public record GameRetrievalResult(
        Status status,
        int received,
        int rejected,
        int created,
        int updated,
        int unchanged,
        Long retryAfterSeconds) {

    public GameRetrievalResult {
        if (received < 0 || rejected < 0 || created < 0 || updated < 0 || unchanged < 0
                || created + updated + unchanged != received) {
            throw new IllegalArgumentException("Invalid game retrieval counts");
        }
    }

    public enum Status {
        SUCCESS,
        UNAUTHORIZED,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UPSTREAM_UNAVAILABLE,
        STORAGE_UNAVAILABLE,
        BUSY
    }

    public static GameRetrievalResult success(GameCatalogWriteResult writeResult, int rejected) {
        return new GameRetrievalResult(
                Status.SUCCESS,
                writeResult.total(),
                rejected,
                writeResult.created(),
                writeResult.updated(),
                writeResult.unchanged(),
                null);
    }

    public static GameRetrievalResult failed(Status status, Long retryAfterSeconds) {
        return new GameRetrievalResult(status, 0, 0, 0, 0, 0, retryAfterSeconds);
    }
}
