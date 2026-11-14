-- One immutable analysis of one side of one game. Owner and opponent findings are always
-- separate versions; reanalysis inserts a new version and never changes an old one. A game's
-- current version for a side and profile is the newest one (see the index below).
CREATE TABLE game_review_analysis_version (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    lichess_game_id TEXT NOT NULL CHECK (length(lichess_game_id) BETWEEN 8 AND 32),
    side TEXT NOT NULL CHECK (side IN ('owner', 'opponent')),
    requested_perspective TEXT NOT NULL
        CHECK (requested_perspective IN ('my_moves', 'opponent_moves', 'both')),
    analyzed_color TEXT NOT NULL CHECK (analyzed_color IN ('white', 'black')),
    profile_id INTEGER NOT NULL,
    rules_version INTEGER NOT NULL CHECK (rules_version BETWEEN 1 AND 1000),
    phase_source TEXT NOT NULL CHECK (phase_source IN ('supplied', 'inferred')),
    middlegame_start INTEGER NOT NULL CHECK (middlegame_start >= 0),
    endgame_start INTEGER CHECK (endgame_start IS NULL OR endgame_start >= middlegame_start),
    move_count INTEGER NOT NULL CHECK (move_count BETWEEN 0 AND 600),
    deep_move_count INTEGER NOT NULL CHECK (deep_move_count BETWEEN 0 AND move_count),
    inaccuracies INTEGER NOT NULL CHECK (inaccuracies BETWEEN 0 AND move_count),
    mistakes INTEGER NOT NULL CHECK (mistakes BETWEEN 0 AND move_count),
    blunders INTEGER NOT NULL CHECK (blunders BETWEEN 0 AND move_count),
    total_loss INTEGER NOT NULL CHECK (total_loss BETWEEN 0 AND 1200000),
    started_at_ms INTEGER NOT NULL CHECK (started_at_ms >= 0),
    completed_at_ms INTEGER NOT NULL CHECK (completed_at_ms >= started_at_ms),
    CHECK (inaccuracies + mistakes + blunders <= move_count),
    CHECK ((side = 'owner' AND requested_perspective IN ('my_moves', 'both'))
        OR (side = 'opponent' AND requested_perspective IN ('opponent_moves', 'both'))),
    FOREIGN KEY (account_id, lichess_game_id)
        REFERENCES game_review_game (account_id, lichess_game_id) ON DELETE RESTRICT,
    FOREIGN KEY (profile_id)
        REFERENCES game_review_engine_profile (id) ON DELETE RESTRICT
);

CREATE INDEX game_review_analysis_version_current
    ON game_review_analysis_version (account_id, lichess_game_id, side, profile_id, id DESC);

CREATE INDEX game_review_analysis_version_profile
    ON game_review_analysis_version (profile_id);

-- Every judged move of a version. Expected scores and loss are half-permille (0 to 2000);
-- lines are the engine's best lines as JSON, best first, from the side to move.
CREATE TABLE game_review_analysis_move (
    version_id INTEGER NOT NULL,
    ply INTEGER NOT NULL CHECK (ply BETWEEN 1 AND 1200),
    san TEXT NOT NULL CHECK (length(san) BETWEEN 2 AND 8),
    uci TEXT NOT NULL CHECK (length(uci) BETWEEN 4 AND 5),
    fen_before TEXT NOT NULL CHECK (length(fen_before) BETWEEN 20 AND 100),
    phase TEXT NOT NULL CHECK (phase IN ('opening', 'middlegame', 'endgame')),
    pass TEXT NOT NULL CHECK (pass IN ('quick', 'deep')),
    label TEXT NOT NULL CHECK (label IN ('none', 'inaccuracy', 'mistake', 'blunder')),
    mate_event TEXT NOT NULL CHECK (mate_event IN ('none', 'missed_mate', 'allowed_mate')),
    played_best INTEGER NOT NULL CHECK (played_best IN (0, 1)),
    best_move TEXT NOT NULL CHECK (length(best_move) BETWEEN 4 AND 5),
    expected_before INTEGER NOT NULL CHECK (expected_before BETWEEN 0 AND 2000),
    expected_after INTEGER NOT NULL CHECK (expected_after BETWEEN 0 AND 2000),
    loss INTEGER NOT NULL CHECK (loss BETWEEN 0 AND 2000),
    after_terminal TEXT NOT NULL CHECK (after_terminal IN ('none', 'checkmate', 'stalemate')),
    before_lines_json TEXT NOT NULL CHECK (length(before_lines_json) BETWEEN 2 AND 16384),
    after_lines_json TEXT CHECK (after_lines_json IS NULL OR length(after_lines_json) BETWEEN 2 AND 16384),
    PRIMARY KEY (version_id, ply),
    CHECK ((after_terminal = 'none' AND after_lines_json IS NOT NULL)
        OR (after_terminal <> 'none' AND after_lines_json IS NULL)),
    CHECK (played_best = 0 OR (loss = 0 AND label = 'none' AND mate_event = 'none')),
    CHECK (mate_event = 'none' OR label = 'blunder'),
    FOREIGN KEY (version_id)
        REFERENCES game_review_analysis_version (id) ON DELETE CASCADE
) WITHOUT ROWID;

-- Stored evidence never changes in place.
CREATE TRIGGER game_review_analysis_version_immutable
BEFORE UPDATE ON game_review_analysis_version
BEGIN
    SELECT RAISE(ABORT, 'analysis versions are immutable');
END;

CREATE TRIGGER game_review_analysis_move_immutable
BEFORE UPDATE ON game_review_analysis_move
BEGIN
    SELECT RAISE(ABORT, 'analysis moves are immutable');
END;
