package com.chesstweek.identity;

final class SessionSealingException extends RuntimeException {

    SessionSealingException(Throwable cause) {
        super("Session sealing failed", cause);
    }
}
