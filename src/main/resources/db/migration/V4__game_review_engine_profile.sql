CREATE TABLE game_review_engine_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    engine_name TEXT NOT NULL CHECK (length(engine_name) BETWEEN 11 AND 58),
    threads INTEGER NOT NULL CHECK (threads BETWEEN 1 AND 64),
    hash_mb INTEGER NOT NULL CHECK (hash_mb BETWEEN 16 AND 4096),
    multi_pv INTEGER NOT NULL CHECK (multi_pv BETWEEN 1 AND 10),
    benchmark_positions INTEGER NOT NULL CHECK (benchmark_positions BETWEEN 1 AND 64),
    benchmark_nodes_per_position INTEGER NOT NULL
        CHECK (benchmark_nodes_per_position BETWEEN 1000 AND 100000000),
    nodes_per_second INTEGER NOT NULL CHECK (nodes_per_second BETWEEN 10000 AND 1000000000),
    quick_target_ms INTEGER NOT NULL CHECK (quick_target_ms BETWEEN 1 AND 600000),
    deep_target_ms INTEGER NOT NULL CHECK (deep_target_ms BETWEEN 1 AND 600000),
    quick_nodes INTEGER NOT NULL CHECK (quick_nodes BETWEEN 10000 AND 1000000000),
    deep_nodes INTEGER NOT NULL CHECK (deep_nodes BETWEEN 10000 AND 1000000000),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    CHECK (deep_nodes >= quick_nodes)
);
