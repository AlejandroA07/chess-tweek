package com.chesstweek.gamereview;

import java.util.Optional;

public interface EngineBenchmark {

    /** The most recently stored profile, if a benchmark has ever completed. */
    Optional<EngineProfile> currentProfile();

    /** Runs the fixed benchmark on a ready Stockfish and stores a new profile when it succeeds. */
    BenchmarkResult run();

    /**
     * {@code engine} is the handshake status. {@code profile} is the new stored profile, or null
     * when the engine was unavailable or the benchmark did not complete.
     */
    record BenchmarkResult(EngineStatus engine, EngineProfile profile) {}
}
