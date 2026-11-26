package com.chesstweek.training.internal.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcExplorerCacheStore implements ExplorerCacheStore {

    private static final Pattern CACHE_KEY = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;

    public JdbcExplorerCacheStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Entry> find(String cacheKey) {
        checkKey(cacheKey);
        return jdbc.query(
                        "SELECT response_json, fetched_at_ms FROM training_explorer_cache WHERE cache_key = ?",
                        (row, index) -> new Entry(
                                row.getString("response_json"),
                                Instant.ofEpochMilli(row.getLong("fetched_at_ms"))),
                        cacheKey)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public void put(
            String cacheKey, String database, String responseJson, Instant fetchedAt, int maxEntries) {
        checkKey(cacheKey);
        if (maxEntries < 1) {
            throw new IllegalArgumentException("The cache must keep at least one entry");
        }
        jdbc.update("""
                INSERT INTO training_explorer_cache (cache_key, database_name, response_json, fetched_at_ms)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (cache_key) DO UPDATE SET
                    database_name = excluded.database_name,
                    response_json = excluded.response_json,
                    fetched_at_ms = excluded.fetched_at_ms
                """,
                cacheKey, database, responseJson, fetchedAt.toEpochMilli());
        jdbc.update("""
                DELETE FROM training_explorer_cache
                WHERE cache_key IN (
                    SELECT cache_key FROM training_explorer_cache
                    ORDER BY fetched_at_ms DESC, cache_key DESC
                    LIMIT -1 OFFSET ?)
                """,
                maxEntries);
    }

    private static void checkKey(String cacheKey) {
        if (cacheKey == null || !CACHE_KEY.matcher(cacheKey).matches()) {
            throw new IllegalArgumentException("Invalid explorer cache key");
        }
    }
}
