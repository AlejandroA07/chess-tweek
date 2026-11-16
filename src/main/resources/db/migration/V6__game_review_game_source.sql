-- Games come from Lichess or chess.com. Games and analysis versions are keyed by source and game
-- ID. SQLite cannot change a primary key in place, so the three catalog tables are rebuilt: new
-- tables are filled from the old ones, the old ones are dropped child first (dropping a version
-- would otherwise cascade to its moves), and the new ones take the old names, which also updates
-- their foreign key references. Every game and analysis stored before this migration is a Lichess
-- game and keeps its values, version IDs, and moves.
--
-- Lichess IDs are 8 to 32 letters and digits; chess.com IDs are decimal numbers.

CREATE TABLE game_review_game_v6 (
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    source TEXT NOT NULL CHECK (source IN ('lichess', 'chesscom')),
    game_id TEXT NOT NULL,
    played_at_ms INTEGER NOT NULL CHECK (played_at_ms >= 0),
    last_move_at_ms INTEGER NOT NULL CHECK (last_move_at_ms >= played_at_ms),
    status TEXT NOT NULL CHECK (status IN (
        'mate', 'resign', 'stalemate', 'timeout', 'draw', 'outoftime',
        'cheat', 'unknownFinish', 'insufficientMaterialClaim', 'variantEnd'
    )),
    owner_result TEXT NOT NULL CHECK (owner_result IN ('win', 'draw', 'loss')),
    owner_color TEXT NOT NULL CHECK (owner_color IN ('white', 'black')),
    owner_rating INTEGER CHECK (owner_rating BETWEEN 0 AND 5000),
    opponent_kind TEXT NOT NULL CHECK (opponent_kind IN ('user', 'ai', 'anonymous')),
    opponent_user_id TEXT CHECK (
        opponent_user_id IS NULL OR length(opponent_user_id) BETWEEN 1 AND 32
    ),
    opponent_name TEXT NOT NULL CHECK (length(opponent_name) BETWEEN 1 AND 64),
    opponent_rating INTEGER CHECK (opponent_rating BETWEEN 0 AND 5000),
    opponent_title TEXT CHECK (opponent_title IN (
        'GM', 'WGM', 'IM', 'WIM', 'FM', 'WFM', 'NM', 'WNM', 'CM', 'WCM', 'LM', 'BOT'
    )),
    opponent_ai_level INTEGER CHECK (opponent_ai_level BETWEEN 1 AND 8),
    speed TEXT NOT NULL CHECK (speed IN (
        'ultraBullet', 'bullet', 'blitz', 'rapid', 'classical', 'correspondence'
    )),
    rated INTEGER NOT NULL CHECK (rated IN (0, 1)),
    variant TEXT NOT NULL CHECK (variant IN (
        'standard', 'chess960', 'crazyhouse', 'antichess', 'atomic', 'horde',
        'kingOfTheHill', 'racingKings', 'threeCheck', 'fromPosition'
    )),
    clock_initial_seconds INTEGER CHECK (clock_initial_seconds >= 0),
    clock_increment_seconds INTEGER CHECK (clock_increment_seconds >= 0),
    days_per_turn INTEGER CHECK (days_per_turn BETWEEN 1 AND 30),
    opening_eco TEXT CHECK (opening_eco IS NULL OR length(opening_eco) BETWEEN 3 AND 3),
    opening_name TEXT CHECK (opening_name IS NULL OR length(opening_name) BETWEEN 1 AND 160),
    opening_ply INTEGER CHECK (opening_ply >= 0),
    middlegame_ply INTEGER CHECK (middlegame_ply >= 0),
    endgame_ply INTEGER CHECK (endgame_ply >= 0),
    moves_san TEXT NOT NULL CHECK (length(moves_san) BETWEEN 1 AND 131072),
    pgn TEXT CHECK (pgn IS NULL OR length(pgn) BETWEEN 1 AND 524288),
    clocks_json TEXT NOT NULL CHECK (length(clocks_json) BETWEEN 2 AND 131072),
    analysis_eligible INTEGER NOT NULL CHECK (analysis_eligible IN (0, 1)),
    retrieved_at_ms INTEGER NOT NULL CHECK (retrieved_at_ms >= 0),
    owner_rating_change INTEGER
        CHECK (owner_rating_change IS NULL OR owner_rating_change BETWEEN -1000 AND 1000),
    PRIMARY KEY (account_id, source, game_id),
    CHECK ((source = 'lichess' AND length(game_id) BETWEEN 8 AND 32)
        OR (source = 'chesscom' AND length(game_id) BETWEEN 1 AND 20
            AND game_id NOT GLOB '*[^0-9]*')),
    CHECK (played_at_ms <= last_move_at_ms),
    CHECK ((variant = 'standard' AND analysis_eligible = 1)
        OR (variant <> 'standard' AND analysis_eligible = 0)),
    CHECK ((opponent_kind = 'ai' AND opponent_ai_level IS NOT NULL)
        OR (opponent_kind <> 'ai' AND opponent_ai_level IS NULL)),
    CHECK ((opponent_kind = 'user' AND opponent_user_id IS NOT NULL)
        OR (opponent_kind <> 'user' AND opponent_user_id IS NULL))
) WITHOUT ROWID;

INSERT INTO game_review_game_v6 (
    account_id, source, game_id, played_at_ms, last_move_at_ms, status, owner_result,
    owner_color, owner_rating, opponent_kind, opponent_user_id, opponent_name, opponent_rating,
    opponent_title, opponent_ai_level, speed, rated, variant, clock_initial_seconds,
    clock_increment_seconds, days_per_turn, opening_eco, opening_name, opening_ply,
    middlegame_ply, endgame_ply, moves_san, pgn, clocks_json, analysis_eligible,
    retrieved_at_ms, owner_rating_change
)
SELECT
    account_id, 'lichess', lichess_game_id, played_at_ms, last_move_at_ms, status, owner_result,
    owner_color, owner_rating, opponent_kind, opponent_user_id, opponent_name, opponent_rating,
    opponent_title, opponent_ai_level, speed, rated, variant, clock_initial_seconds,
    clock_increment_seconds, days_per_turn, opening_eco, opening_name, opening_ply,
    middlegame_ply, endgame_ply, moves_san, pgn, clocks_json, analysis_eligible,
    retrieved_at_ms, owner_rating_change
FROM game_review_game;

CREATE TABLE game_review_analysis_version_v6 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    source TEXT NOT NULL CHECK (source IN ('lichess', 'chesscom')),
    game_id TEXT NOT NULL,
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
    CHECK ((source = 'lichess' AND length(game_id) BETWEEN 8 AND 32)
        OR (source = 'chesscom' AND length(game_id) BETWEEN 1 AND 20
            AND game_id NOT GLOB '*[^0-9]*')),
    CHECK (inaccuracies + mistakes + blunders <= move_count),
    CHECK ((side = 'owner' AND requested_perspective IN ('my_moves', 'both'))
        OR (side = 'opponent' AND requested_perspective IN ('opponent_moves', 'both'))),
    FOREIGN KEY (account_id, source, game_id)
        REFERENCES game_review_game_v6 (account_id, source, game_id) ON DELETE RESTRICT,
    FOREIGN KEY (profile_id)
        REFERENCES game_review_engine_profile (id) ON DELETE RESTRICT
);

INSERT INTO game_review_analysis_version_v6 (
    id, account_id, source, game_id, side, requested_perspective, analyzed_color, profile_id,
    rules_version, phase_source, middlegame_start, endgame_start, move_count, deep_move_count,
    inaccuracies, mistakes, blunders, total_loss, started_at_ms, completed_at_ms
)
SELECT
    id, account_id, 'lichess', lichess_game_id, side, requested_perspective, analyzed_color,
    profile_id, rules_version, phase_source, middlegame_start, endgame_start, move_count,
    deep_move_count, inaccuracies, mistakes, blunders, total_loss, started_at_ms, completed_at_ms
FROM game_review_analysis_version;

CREATE TABLE game_review_analysis_move_v6 (
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
        REFERENCES game_review_analysis_version_v6 (id) ON DELETE CASCADE
) WITHOUT ROWID;

INSERT INTO game_review_analysis_move_v6 (
    version_id, ply, san, uci, fen_before, phase, pass, label, mate_event, played_best,
    best_move, expected_before, expected_after, loss, after_terminal, before_lines_json,
    after_lines_json
)
SELECT
    version_id, ply, san, uci, fen_before, phase, pass, label, mate_event, played_best,
    best_move, expected_before, expected_after, loss, after_terminal, before_lines_json,
    after_lines_json
FROM game_review_analysis_move;

DROP TABLE game_review_analysis_move;
DROP TABLE game_review_analysis_version;
DROP TABLE game_review_game;

ALTER TABLE game_review_game_v6 RENAME TO game_review_game;
ALTER TABLE game_review_analysis_version_v6 RENAME TO game_review_analysis_version;
ALTER TABLE game_review_analysis_move_v6 RENAME TO game_review_analysis_move;

CREATE INDEX game_review_game_account_date
    ON game_review_game (account_id, played_at_ms DESC, source, game_id);

CREATE INDEX game_review_analysis_version_current
    ON game_review_analysis_version (account_id, source, game_id, side, profile_id, id DESC);
CREATE INDEX game_review_analysis_version_profile
    ON game_review_analysis_version (profile_id);

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
