-- Account-scoped performance Training Lists for normal Puzzles
-- (docs/planning/07-performance-training-lists.md). Both tables reference the canonical Puzzle
-- by ID only; neither copies its position, solution, or provenance.

-- One row per account and classified Puzzle. An unclassified Puzzle has no row, so Random seeks
-- past classified IDs on this primary key.
CREATE TABLE training_puzzle_performance (
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    puzzle_id INTEGER NOT NULL REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    list TEXT NOT NULL CHECK (list IN (
        'hard_to_solve', 'wrong_moves', 'worth_repeating', 'retiring', 'mastered'
    )),
    successes INTEGER NOT NULL CHECK (successes BETWEEN 0 AND 9),
    strikes INTEGER NOT NULL CHECK (strikes BETWEEN 0 AND 1),
    classified_at_ms INTEGER NOT NULL CHECK (classified_at_ms >= 0),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= classified_at_ms),
    PRIMARY KEY (account_id, puzzle_id)
) WITHOUT ROWID;

-- Corpus retention looks up any account's state by Puzzle ID.
CREATE INDEX training_puzzle_performance_puzzle
    ON training_puzzle_performance (puzzle_id);

-- Every attempt, including unfinished ones. An active attempt carries the server-side solving
-- cursor; starting another attempt for the account abandons it.
CREATE TABLE training_puzzle_attempt (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    puzzle_id INTEGER NOT NULL REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    entry TEXT NOT NULL CHECK (entry IN ('random', 'category')),
    status TEXT NOT NULL CHECK (status IN ('active', 'completed', 'abandoned')),
    list_before TEXT CHECK (list_before IS NULL OR list_before IN (
        'hard_to_solve', 'wrong_moves', 'worth_repeating', 'retiring', 'mastered'
    )),
    next_ply INTEGER NOT NULL CHECK (next_ply BETWEEN 0 AND 200),
    wrong_moves INTEGER NOT NULL CHECK (wrong_moves BETWEEN 0 AND 10000),
    hint_used INTEGER NOT NULL CHECK (hint_used IN (0, 1)),
    revealed INTEGER NOT NULL CHECK (revealed IN (0, 1)),
    fast_limit_ms INTEGER NOT NULL CHECK (fast_limit_ms BETWEEN 6000 AND 1000000),
    started_at_ms INTEGER NOT NULL CHECK (started_at_ms >= 0),
    finished_at_ms INTEGER CHECK (finished_at_ms IS NULL OR finished_at_ms >= started_at_ms),
    elapsed_ms INTEGER CHECK (elapsed_ms IS NULL OR elapsed_ms >= 0),
    list_after TEXT CHECK (list_after IS NULL OR list_after IN (
        'hard_to_solve', 'wrong_moves', 'worth_repeating', 'retiring', 'mastered'
    )),
    CHECK ((status = 'completed') = (list_after IS NOT NULL AND elapsed_ms IS NOT NULL))
);

CREATE INDEX training_puzzle_attempt_account
    ON training_puzzle_attempt (account_id, status, id);

CREATE INDEX training_puzzle_attempt_puzzle
    ON training_puzzle_attempt (puzzle_id);
