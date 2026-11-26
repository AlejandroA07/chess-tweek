-- Stage 10 Part 1: the Lichess opening explorer cache.
--
-- Masters and Lichess explorer answers are public statistics, not account data, so one cache
-- serves every account. The key is a digest of the canonical request (database, position key,
-- and filters). Only the validated, reduced answer is stored, never the raw upstream body. An
-- expired entry is refreshed on the next request and is still shown when Lichess cannot be
-- reached. The cache keeps at most a bounded number of entries, dropping the oldest first.
CREATE TABLE training_explorer_cache (
    cache_key TEXT NOT NULL PRIMARY KEY CHECK (length(cache_key) = 64),
    database_name TEXT NOT NULL CHECK (database_name IN ('masters', 'lichess')),
    response_json TEXT NOT NULL CHECK (length(response_json) BETWEEN 2 AND 262144),
    fetched_at_ms INTEGER NOT NULL CHECK (fetched_at_ms >= 0)
) WITHOUT ROWID;

CREATE INDEX training_explorer_cache_age
    ON training_explorer_cache (fetched_at_ms, cache_key);
