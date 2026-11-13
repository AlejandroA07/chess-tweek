package com.chesstweek.gamereview;

import java.util.Objects;

/**
 * Local Stockfish availability. {@code path} is the detected binary location when one exists;
 * {@code engineName} is the UCI identity reported by a successful health check.
 */
public record EngineStatus(State state, String path, String engineName) {

    public enum State {
        /** A binary was found but has not been tested since the application started. */
        NOT_CHECKED,
        /** The binary answered the UCI handshake as Stockfish. */
        READY,
        /** No binary exists at the configured or standard Homebrew location. */
        NOT_INSTALLED,
        /** A file exists but is not an executable regular file in an accepted location. */
        NOT_EXECUTABLE,
        /** The binary answered UCI but did not identify itself as Stockfish. */
        UNSUPPORTED_ENGINE,
        /** The binary did not complete the UCI handshake within the time limit. */
        NOT_RESPONDING,
        /** Another engine operation is already running. */
        BUSY
    }

    public EngineStatus {
        Objects.requireNonNull(state);
    }
}
