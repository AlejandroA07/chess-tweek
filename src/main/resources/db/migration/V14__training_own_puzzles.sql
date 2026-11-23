-- Own Puzzles (docs/planning/07-performance-training-lists.md, Stage 8 Part 8a): one puzzle per
-- owner inaccuracy, mistake, and blunder of a completed My Moves analysis. They are a separate
-- game-derived kind, never rows of the Lichess Puzzle Library, so they keep their own position and
-- solution. The source game, ply, and analysis version are copied as scoped values; there is no
-- foreign key into the game-review module's tables.
CREATE TABLE training_own_puzzle (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    game_source TEXT NOT NULL CHECK (game_source IN ('lichess', 'chesscom')),
    game_id TEXT NOT NULL CHECK (length(game_id) BETWEEN 1 AND 32),
    -- The owner's labelled move; the puzzle starts at the position it was played from.
    source_ply INTEGER NOT NULL CHECK (source_ply BETWEEN 1 AND 1200),
    analysis_version_id INTEGER NOT NULL CHECK (analysis_version_id >= 1),
    position_fen TEXT NOT NULL CHECK (length(position_fen) BETWEEN 20 AND 100),
    -- The opponent's move into the position; null when the owner played the first move.
    previous_move_uci TEXT
        CHECK (previous_move_uci IS NULL OR length(previous_move_uci) BETWEEN 4 AND 5),
    -- The engine's top line, space separated: one owner move, or owner, reply, owner.
    solution_uci TEXT NOT NULL CHECK (length(solution_uci) BETWEEN 4 AND 17
        AND length(solution_uci) - length(replace(solution_uci, ' ', '')) IN (0, 2)),
    label TEXT NOT NULL CHECK (label IN ('inaccuracy', 'mistake', 'blunder')),
    phase TEXT NOT NULL CHECK (phase IN ('opening', 'middlegame', 'endgame')),
    opening_eco TEXT CHECK (opening_eco IS NULL OR length(opening_eco) = 3),
    opening_name TEXT CHECK (opening_name IS NULL OR length(opening_name) BETWEEN 1 AND 160),
    game_played_at_ms INTEGER NOT NULL CHECK (game_played_at_ms >= 0),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    -- Creation is idempotent: one Own Puzzle per account and source game position.
    UNIQUE (account_id, game_source, game_id, source_ply)
);

-- New Own Puzzles are presented newest source game first.
CREATE INDEX training_own_puzzle_newest
    ON training_own_puzzle (account_id, game_played_at_ms DESC, id);

CREATE INDEX training_own_puzzle_phase
    ON training_own_puzzle (account_id, phase);

-- The proven tactic labels of the owner's move, for grouping.
CREATE TABLE training_own_puzzle_tactic (
    own_puzzle_id INTEGER NOT NULL REFERENCES training_own_puzzle(id) ON DELETE CASCADE,
    theme TEXT NOT NULL CHECK (length(theme) BETWEEN 1 AND 40 AND theme NOT GLOB '*[^a-z_]*'),
    kind TEXT NOT NULL CHECK (kind IN ('allowed', 'missed')),
    PRIMARY KEY (own_puzzle_id, theme, kind)
) WITHOUT ROWID;
