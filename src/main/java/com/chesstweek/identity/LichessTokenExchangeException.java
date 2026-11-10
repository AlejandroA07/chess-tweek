package com.chesstweek.identity;

final class LichessTokenExchangeException extends RuntimeException {

    private final boolean authorizationRejected;

    LichessTokenExchangeException(boolean authorizationRejected, Throwable cause) {
        super("Lichess token exchange failed", cause);
        this.authorizationRejected = authorizationRejected;
    }

    boolean authorizationRejected() {
        return authorizationRejected;
    }
}
