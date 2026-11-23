-- Own Puzzle training state (docs/planning/07-performance-training-lists.md, Stage 8 Part 8b).
-- Own Puzzles apply the same performance rules as normal Puzzles but keep completely separate
-- attempts, Training List state, and Practice Sessions. Every row is tied to the owning account
-- through the Own Puzzle's (account_id, id), so no account can hold state on another's puzzle.

CREATE UNIQUE INDEX training_own_puzzle_account ON training_own_puzzle (account_id, id);

-- A discarded Own Puzzle is out of New Own Puzzles and every list until it is restored.
CREATE TABLE training_own_puzzle_discard (
    account_id TEXT NOT NULL,
    puzzle_id INTEGER NOT NULL,
    discarded_at_ms INTEGER NOT NULL CHECK (discarded_at_ms >= 0),
    PRIMARY KEY (account_id, puzzle_id),
    FOREIGN KEY (account_id, puzzle_id)
        REFERENCES training_own_puzzle (account_id, id) ON DELETE CASCADE
) WITHOUT ROWID;

-- One row per classified Own Puzzle, as training_puzzle_performance does for normal Puzzles.
CREATE TABLE training_own_puzzle_performance (
    account_id TEXT NOT NULL,
    puzzle_id INTEGER NOT NULL,
    list TEXT NOT NULL CHECK (list IN (
        'hard_to_solve', 'wrong_moves', 'worth_repeating', 'retiring', 'mastered'
    )),
    successes INTEGER NOT NULL CHECK (successes BETWEEN 0 AND 9),
    strikes INTEGER NOT NULL CHECK (strikes BETWEEN 0 AND 1),
    classified_at_ms INTEGER NOT NULL CHECK (classified_at_ms >= 0),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= classified_at_ms),
    PRIMARY KEY (account_id, puzzle_id),
    FOREIGN KEY (account_id, puzzle_id)
        REFERENCES training_own_puzzle (account_id, id) ON DELETE RESTRICT
) WITHOUT ROWID;

CREATE INDEX training_own_puzzle_performance_list
    ON training_own_puzzle_performance (account_id, list, puzzle_id);

-- Discard is allowed only before classification, and a discarded puzzle is never classified.
CREATE TRIGGER training_own_puzzle_discarded_never_classified
BEFORE INSERT ON training_own_puzzle_performance
WHEN EXISTS (
    SELECT 1 FROM training_own_puzzle_discard d
    WHERE d.account_id = NEW.account_id AND d.puzzle_id = NEW.puzzle_id)
BEGIN
    SELECT RAISE(ABORT, 'Own Puzzle is discarded');
END;

CREATE TRIGGER training_own_puzzle_classified_never_discarded
BEFORE INSERT ON training_own_puzzle_discard
WHEN EXISTS (
    SELECT 1 FROM training_own_puzzle_performance p
    WHERE p.account_id = NEW.account_id AND p.puzzle_id = NEW.puzzle_id)
BEGIN
    SELECT RAISE(ABORT, 'Own Puzzle is classified');
END;

-- Every Own Puzzle attempt, including unfinished ones. A first attempt starts from New Own
-- Puzzles; later attempts come from a repetition or Mastered-practice session.
CREATE TABLE training_own_puzzle_attempt (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL,
    puzzle_id INTEGER NOT NULL,
    entry TEXT NOT NULL CHECK (entry IN ('new', 'repetition', 'mastered_practice')),
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
    )),
    FOREIGN KEY (account_id, puzzle_id)
        REFERENCES training_own_puzzle (account_id, id) ON DELETE RESTRICT
);

CREATE INDEX training_own_puzzle_attempt_account
    ON training_own_puzzle_attempt (account_id, status, id);

CREATE INDEX training_own_puzzle_attempt_puzzle
    ON training_own_puzzle_attempt (account_id, puzzle_id);

-- A Practice Session over one Training List, or Mastered practice over one group: an opening
-- family, a game phase, or a tactic label, stored as "kind:key".
CREATE TABLE training_own_puzzle_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    list TEXT NOT NULL CHECK (list IN (
        'hard_to_solve', 'wrong_moves', 'worth_repeating', 'retiring', 'mastered'
    )),
    group_key TEXT CHECK (group_key IS NULL OR (
        length(group_key) BETWEEN 7 AND 200
        AND (group_key GLOB 'opening:?*' OR group_key GLOB 'phase:?*'
             OR group_key GLOB 'tactic:?*')
    )),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    -- Only Mastered practice is chosen by group; repetition covers the whole list.
    CHECK ((list = 'mastered') = (group_key IS NOT NULL))
);

CREATE INDEX training_own_puzzle_session_account
    ON training_own_puzzle_session (account_id, id);

CREATE TABLE training_own_puzzle_session_item (
    session_id INTEGER NOT NULL REFERENCES training_own_puzzle_session(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1),
    puzzle_id INTEGER NOT NULL REFERENCES training_own_puzzle(id) ON DELETE RESTRICT,
    presented_at_ms INTEGER CHECK (presented_at_ms IS NULL OR presented_at_ms >= 0),
    PRIMARY KEY (session_id, position),
    UNIQUE (session_id, puzzle_id)
) WITHOUT ROWID;

CREATE INDEX training_own_puzzle_session_item_puzzle
    ON training_own_puzzle_session_item (puzzle_id);
