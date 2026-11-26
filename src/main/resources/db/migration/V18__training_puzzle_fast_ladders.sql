-- Stage 9 Part 2: fast ladders (owner request, 2026-11-26).
--
-- A fast ladder is one random run through a pattern: one Puzzle from each 200-point rating step
-- up to 2199, then one Puzzle of the pattern that also carries each other pattern Theme, rated
-- 2000-2199 when possible, in random order. Every start draws a new run and replaces the
-- account's previous one, so a run is never resumed or reused.
CREATE TABLE training_puzzle_fast_ladder (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    category_id INTEGER NOT NULL REFERENCES training_puzzle_category(id) ON DELETE RESTRICT,
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0)
);

CREATE INDEX training_puzzle_fast_ladder_account
    ON training_puzzle_fast_ladder (account_id, id);

-- The run in order. A rating step names its range; a combination step names the other pattern.
CREATE TABLE training_puzzle_fast_ladder_item (
    fast_ladder_id INTEGER NOT NULL
        REFERENCES training_puzzle_fast_ladder(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1),
    puzzle_id INTEGER NOT NULL REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    rating_low INTEGER CHECK (rating_low IS NULL OR rating_low BETWEEN 0 AND 4100),
    rating_high INTEGER CHECK (rating_high IS NULL OR rating_high BETWEEN 100 AND 4100),
    combined_category_id INTEGER
        REFERENCES training_puzzle_category(id) ON DELETE RESTRICT,
    presented_at_ms INTEGER CHECK (presented_at_ms IS NULL OR presented_at_ms >= 0),
    CHECK ((combined_category_id IS NULL) = (rating_low IS NOT NULL AND rating_high IS NOT NULL)),
    PRIMARY KEY (fast_ladder_id, position),
    UNIQUE (fast_ladder_id, puzzle_id)
) WITHOUT ROWID;

-- Corpus retention and the puzzle foreign key look up run items by Puzzle ID.
CREATE INDEX training_puzzle_fast_ladder_item_puzzle
    ON training_puzzle_fast_ladder_item (puzzle_id);
