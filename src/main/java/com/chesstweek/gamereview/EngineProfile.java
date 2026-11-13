package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable analysis profile measured on this machine: the engine identity, its search
 * settings, the benchmark that was run, and the fixed node budgets derived from it. Every future
 * analysis version references the profile it used. {@code id} is zero until stored.
 */
public record EngineProfile(
        long id,
        String engineName,
        int threads,
        int hashMb,
        int multiPv,
        int benchmarkPositions,
        long benchmarkNodesPerPosition,
        long nodesPerSecond,
        int quickTargetMs,
        int deepTargetMs,
        long quickNodes,
        long deepNodes,
        Instant createdAt) {

    public EngineProfile {
        Objects.requireNonNull(engineName);
        Objects.requireNonNull(createdAt);
        if (deepNodes < quickNodes) {
            throw new IllegalArgumentException("Deep budget must not be smaller than quick budget");
        }
    }

    public EngineProfile withId(long storedId) {
        return new EngineProfile(
                storedId, engineName, threads, hashMb, multiPv, benchmarkPositions,
                benchmarkNodesPerPosition, nodesPerSecond, quickTargetMs, deepTargetMs,
                quickNodes, deepNodes, createdAt);
    }
}
