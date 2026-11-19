-- Stage 7 Part 2: the complete Lichess Puzzle corpus and controlled, indexed access.
--
-- A full-corpus Admin import is an auditable snapshot of its own. One row records the source,
-- download, verification, resume cursor, and outcome of each manual import. At most one import is
-- unfinished (active or paused) at a time.
CREATE TABLE training_puzzle_corpus_import (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL CHECK (provider = 'lichess-puzzle-database'),
    source_url TEXT NOT NULL CHECK (length(source_url) BETWEEN 10 AND 240),
    source_etag TEXT NOT NULL CHECK (length(source_etag) BETWEEN 1 AND 128),
    source_last_modified_ms INTEGER NOT NULL CHECK (source_last_modified_ms >= 0),
    snapshot_date TEXT NOT NULL CHECK (
        length(snapshot_date) = 10
        AND snapshot_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
    ),
    source_bytes INTEGER NOT NULL CHECK (source_bytes BETWEEN 1 AND 10737418240),
    downloaded_bytes INTEGER NOT NULL DEFAULT 0 CHECK (
        downloaded_bytes BETWEEN 0 AND source_bytes
    ),
    download_checksum TEXT CHECK (download_checksum IS NULL OR length(download_checksum) = 64),
    importer_version TEXT NOT NULL CHECK (length(importer_version) BETWEEN 1 AND 64),
    state TEXT NOT NULL CHECK (
        state IN ('downloading', 'importing', 'finalizing', 'paused', 'failed', 'verified')
    ),
    failure_code TEXT CHECK (failure_code IS NULL OR length(failure_code) BETWEEN 1 AND 64),
    processed_records INTEGER NOT NULL DEFAULT 0 CHECK (processed_records >= 0),
    records_complete INTEGER NOT NULL DEFAULT 0 CHECK (records_complete IN (0, 1)),
    inserted_puzzles INTEGER NOT NULL DEFAULT 0 CHECK (inserted_puzzles >= 0),
    updated_puzzles INTEGER NOT NULL DEFAULT 0 CHECK (updated_puzzles >= 0),
    unchanged_puzzles INTEGER NOT NULL DEFAULT 0 CHECK (unchanged_puzzles >= 0),
    rejected_records INTEGER NOT NULL DEFAULT 0 CHECK (rejected_records >= 0),
    conflicting_records INTEGER NOT NULL DEFAULT 0 CHECK (conflicting_records >= 0),
    removed_puzzles INTEGER NOT NULL DEFAULT 0 CHECK (removed_puzzles >= 0),
    retained_puzzles INTEGER NOT NULL DEFAULT 0 CHECK (retained_puzzles >= 0),
    record_count INTEGER CHECK (record_count IS NULL OR record_count >= 0),
    source_deleted INTEGER NOT NULL DEFAULT 0 CHECK (source_deleted IN (0, 1)),
    started_at_ms INTEGER NOT NULL CHECK (started_at_ms >= 0),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= 0),
    completed_at_ms INTEGER CHECK (completed_at_ms IS NULL OR completed_at_ms >= 0),
    CHECK ((state = 'verified') = (record_count IS NOT NULL AND completed_at_ms IS NOT NULL))
);

CREATE UNIQUE INDEX training_puzzle_corpus_import_unfinished
    ON training_puzzle_corpus_import(provider)
    WHERE state IN ('downloading', 'importing', 'finalizing', 'paused');

-- Bounded samples of rejected and conflicting source records. Totals live on the import row.
CREATE TABLE training_puzzle_corpus_issue (
    import_id INTEGER NOT NULL
        REFERENCES training_puzzle_corpus_import(id) ON DELETE CASCADE,
    record_number INTEGER NOT NULL CHECK (record_number >= 1),
    kind TEXT NOT NULL CHECK (kind IN ('rejected', 'conflict')),
    external_id TEXT CHECK (external_id IS NULL OR length(external_id) BETWEEN 1 AND 32),
    reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 64),
    PRIMARY KEY (import_id, record_number)
) WITHOUT ROWID;

-- Compact integer identity for every classification so category indexes stay small.
CREATE TABLE training_puzzle_category (
    id INTEGER PRIMARY KEY,
    kind TEXT NOT NULL,
    classification_key TEXT NOT NULL,
    UNIQUE (kind, classification_key),
    FOREIGN KEY (kind, classification_key)
        REFERENCES training_puzzle_classification(kind, classification_key) ON DELETE RESTRICT
);

INSERT INTO training_puzzle_category (kind, classification_key)
SELECT kind, classification_key
FROM training_puzzle_classification
ORDER BY kind, classification_key;

-- The current observation of every canonical Puzzle. Rows imported by a full-corpus import name
-- that import; built-in and bounded-import rows keep a NULL import and their pinned observation.
-- Random selection seeks the first Puzzle ID at or after a uniform point in the ID range, so the
-- primary key is the sampling index and no separate sampling column is stored.
CREATE TABLE training_puzzle_current (
    puzzle_id INTEGER PRIMARY KEY REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    corpus_import_id INTEGER
        REFERENCES training_puzzle_corpus_import(id) ON DELETE RESTRICT,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 100 AND 4000),
    rating_deviation INTEGER NOT NULL CHECK (rating_deviation BETWEEN 0 AND 500),
    popularity INTEGER NOT NULL CHECK (popularity BETWEEN -100 AND 100),
    play_count INTEGER NOT NULL CHECK (play_count >= 0),
    source_themes TEXT NOT NULL CHECK (length(source_themes) BETWEEN 1 AND 1000),
    source_opening_tags TEXT NOT NULL CHECK (length(source_opening_tags) <= 1000),
    daily_date TEXT CHECK (
        daily_date IS NULL OR (
            length(daily_date) = 10
            AND daily_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        )
    )
);

CREATE INDEX training_puzzle_current_import
    ON training_puzzle_current(corpus_import_id, puzzle_id);

-- Category membership of the current observation, ordered by Puzzle ID so one primary-key seek
-- selects the Puzzle at a uniform point in the category's ID range. Rows are maintained together
-- with training_puzzle_current; a foreign key would need a second puzzle_id index over tens of
-- millions of rows only to support deletes that the store already performs explicitly.
CREATE TABLE training_puzzle_category_member (
    category_id INTEGER NOT NULL,
    puzzle_id INTEGER NOT NULL,
    PRIMARY KEY (category_id, puzzle_id)
) WITHOUT ROWID;

-- Backfill existing built-in and bounded-import Puzzles from their latest observation.
INSERT INTO training_puzzle_current (
    puzzle_id, corpus_import_id, rating, rating_deviation, popularity, play_count,
    source_themes, source_opening_tags, daily_date
)
SELECT o.puzzle_id, NULL, o.rating, o.rating_deviation,
       o.popularity, o.play_count, o.source_themes, o.source_opening_tags, o.daily_date
FROM training_puzzle_observation o
JOIN training_puzzle_snapshot s ON s.id = o.snapshot_id
WHERE s.snapshot_date = (
    SELECT max(latest.snapshot_date)
    FROM training_puzzle_observation newer
    JOIN training_puzzle_snapshot latest ON latest.id = newer.snapshot_id
    WHERE newer.puzzle_id = o.puzzle_id
);

INSERT OR IGNORE INTO training_puzzle_category_member (category_id, puzzle_id)
SELECT category.id, current.puzzle_id
FROM training_puzzle_current current
JOIN training_puzzle_observation o ON o.puzzle_id = current.puzzle_id
JOIN training_puzzle_snapshot s ON s.id = o.snapshot_id
JOIN training_puzzle_tag tag ON tag.puzzle_id = o.puzzle_id AND tag.snapshot_id = o.snapshot_id
JOIN training_puzzle_category category
  ON category.kind = tag.kind AND category.classification_key = tag.classification_key
WHERE s.snapshot_date = (
    SELECT max(latest.snapshot_date)
    FROM training_puzzle_observation newer
    JOIN training_puzzle_snapshot latest ON latest.id = newer.snapshot_id
    WHERE newer.puzzle_id = o.puzzle_id
);
