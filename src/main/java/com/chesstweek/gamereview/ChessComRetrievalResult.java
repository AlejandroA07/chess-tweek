package com.chesstweek.gamereview;

public record ChessComRetrievalResult(
        Status status,
        int received,
        int rejected,
        int created,
        int updated,
        int unchanged,
        Long retryAfterSeconds) {

    public ChessComRetrievalResult {
        if (received < 0 || rejected < 0 || created < 0 || updated < 0 || unchanged < 0
                || created + updated + unchanged != received) {
            throw new IllegalArgumentException("Invalid chess.com retrieval counts");
        }
    }

    public enum Status {
        SUCCESS,
        USERNAME_MISSING,
        PLAYER_NOT_FOUND,
        RATE_LIMITED,
        INVALID_RESPONSE,
        UPSTREAM_UNAVAILABLE,
        STORAGE_UNAVAILABLE,
        BUSY
    }

    static ChessComRetrievalResult success(GameCatalogWriteResult written, int rejected) {
        return new ChessComRetrievalResult(
                Status.SUCCESS, written.total(), rejected, written.created(), written.updated(),
                written.unchanged(), null);
    }

    static ChessComRetrievalResult failed(Status status, Long retryAfterSeconds) {
        return new ChessComRetrievalResult(status, 0, 0, 0, 0, 0, retryAfterSeconds);
    }
}
