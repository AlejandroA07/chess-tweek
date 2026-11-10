package com.chesstweek.identity;

final class LichessAccountException extends RuntimeException {

    private final boolean unauthorized;

    LichessAccountException(boolean unauthorized, Throwable cause) {
        super("Lichess account request failed", cause);
        this.unauthorized = unauthorized;
    }

    boolean unauthorized() {
        return unauthorized;
    }
}
