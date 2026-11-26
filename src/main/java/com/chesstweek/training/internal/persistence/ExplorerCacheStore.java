package com.chesstweek.training.internal.persistence;

import java.time.Instant;
import java.util.Optional;

/** The opening explorer cache: validated answers as JSON, keyed by a request digest. */
public interface ExplorerCacheStore {

    Optional<Entry> find(String cacheKey);

    /** Stores or replaces an entry, then keeps at most {@code maxEntries}, dropping the oldest. */
    void put(String cacheKey, String database, String responseJson, Instant fetchedAt, int maxEntries);

    record Entry(String responseJson, Instant fetchedAt) {}
}
