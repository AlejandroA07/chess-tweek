package com.chesstweek.gamereview;

public interface EngineHealth {

    /** Locates the binary without starting it, reporting the last health result for it. */
    EngineStatus status();

    /** Starts the binary, completes a bounded UCI handshake, and always stops the process. */
    EngineStatus check();
}
