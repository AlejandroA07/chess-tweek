-- Repetition Practice Sessions and the Mastered view for normal Puzzles
-- (docs/planning/07-performance-training-lists.md, Stage 7 Part 3c).

-- Training List queries (dashboard counts, session snapshots, Mastered grouping) seek by account
-- and list.
CREATE INDEX training_puzzle_performance_list
    ON training_puzzle_performance (account_id, list, puzzle_id);

-- SQLite cannot change a CHECK constraint, so the attempt table is rebuilt to allow the new entry
-- points: a repetition Practice Session, optional Mastered practice, and (for Part 3d) a guided
-- method. No other table references attempts.
CREATE TABLE training_puzzle_attempt_v13 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    puzzle_id INTEGER NOT NULL REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    entry TEXT NOT NULL CHECK (entry IN (
        'random', 'category', 'repetition', 'mastered_practice', 'method'
    )),
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
    CHECK ((status = 'completed') = (list_after IS NOT NULL AND elapsed_ms IS NOT NULL)),
    -- Mastered practice starts and ends in Mastered: it never changes list state.
    CHECK (entry <> 'mastered_practice' OR (
        list_before = 'mastered' AND (list_after IS NULL OR list_after = 'mastered')
    ))
);

INSERT INTO training_puzzle_attempt_v13 (
    id, account_id, puzzle_id, entry, status, list_before, next_ply, wrong_moves, hint_used,
    revealed, fast_limit_ms, started_at_ms, finished_at_ms, elapsed_ms, list_after
)
SELECT id, account_id, puzzle_id, entry, status, list_before, next_ply, wrong_moves, hint_used,
       revealed, fast_limit_ms, started_at_ms, finished_at_ms, elapsed_ms, list_after
FROM training_puzzle_attempt;

DROP TABLE training_puzzle_attempt;

ALTER TABLE training_puzzle_attempt_v13 RENAME TO training_puzzle_attempt;

CREATE INDEX training_puzzle_attempt_account
    ON training_puzzle_attempt (account_id, status, id);

CREATE INDEX training_puzzle_attempt_puzzle
    ON training_puzzle_attempt (puzzle_id);

-- A Practice Session: one Training List (or one Mastered category) in a freshly randomized
-- order. Opening a list replaces the account's previous session, so its order is never reused.
CREATE TABLE training_puzzle_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    list TEXT NOT NULL CHECK (list IN (
        'hard_to_solve', 'wrong_moves', 'worth_repeating', 'retiring', 'mastered'
    )),
    category_id INTEGER REFERENCES training_puzzle_category(id) ON DELETE RESTRICT,
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    -- Only Mastered practice is chosen by category; repetition covers the whole list.
    CHECK ((list = 'mastered') = (category_id IS NOT NULL))
);

CREATE INDEX training_puzzle_session_account
    ON training_puzzle_session (account_id, id);

-- The session's shuffled order. Each Puzzle is presented at most once per session.
CREATE TABLE training_puzzle_session_item (
    session_id INTEGER NOT NULL REFERENCES training_puzzle_session(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1),
    puzzle_id INTEGER NOT NULL REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    presented_at_ms INTEGER CHECK (presented_at_ms IS NULL OR presented_at_ms >= 0),
    PRIMARY KEY (session_id, position),
    UNIQUE (session_id, puzzle_id)
) WITHOUT ROWID;

-- Corpus retention and the puzzle foreign key look up session items by Puzzle ID.
CREATE INDEX training_puzzle_session_item_puzzle
    ON training_puzzle_session_item (puzzle_id);
