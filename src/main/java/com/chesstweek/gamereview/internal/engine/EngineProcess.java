package com.chesstweek.gamereview.internal.engine;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

/** A running engine process reduced to what the UCI connection needs. */
interface EngineProcess {

    OutputStream input();

    InputStream output();

    /** Waits up to {@code timeout} for a voluntary exit, then kills the process. */
    void stop(Duration timeout);
}
