package com.chesstweek.identity;

final class LichessRevocationException extends RuntimeException {

    LichessRevocationException(Throwable cause) {
        super("Lichess token revocation failed", cause);
    }
}
