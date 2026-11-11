package com.chesstweek.identity;

import java.util.Objects;
import java.util.function.Function;

public final class LichessSessionAuthorization {

    public enum Type {
        CONNECTED,
        DISCONNECTED,
        RATE_LIMITED,
        UPSTREAM_UNAVAILABLE
    }

    private final Type type;
    private final String accountId;
    private final String username;
    private final String accessToken;
    private final boolean clearCookie;
    private final Long retryAfterSeconds;

    private LichessSessionAuthorization(
            Type type,
            String accountId,
            String username,
            String accessToken,
            boolean clearCookie,
            Long retryAfterSeconds) {
        this.type = type;
        this.accountId = accountId;
        this.username = username;
        this.accessToken = accessToken;
        this.clearCookie = clearCookie;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static LichessSessionAuthorization connected(
            String accountId, String username, String accessToken) {
        return new LichessSessionAuthorization(
                Type.CONNECTED,
                Objects.requireNonNull(accountId),
                Objects.requireNonNull(username),
                Objects.requireNonNull(accessToken),
                false,
                null);
    }

    public static LichessSessionAuthorization disconnected(boolean clearCookie) {
        return new LichessSessionAuthorization(
                Type.DISCONNECTED, null, null, null, clearCookie, null);
    }

    public static LichessSessionAuthorization rateLimited(Long retryAfterSeconds) {
        return new LichessSessionAuthorization(
                Type.RATE_LIMITED, null, null, null, false, retryAfterSeconds);
    }

    public static LichessSessionAuthorization unavailable() {
        return new LichessSessionAuthorization(
                Type.UPSTREAM_UNAVAILABLE, null, null, null, false, null);
    }

    public Type type() {
        return type;
    }

    public String accountId() {
        return accountId;
    }

    public String username() {
        return username;
    }

    public boolean clearCookie() {
        return clearCookie;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public <T> T useAccessToken(Function<String, T> operation) {
        if (type != Type.CONNECTED) {
            throw new IllegalStateException("Lichess session is not connected");
        }
        return operation.apply(accessToken);
    }
}
