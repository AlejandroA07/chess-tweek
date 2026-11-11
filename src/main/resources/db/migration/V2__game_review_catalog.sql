CREATE TABLE game_review_game (
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    lichess_game_id TEXT NOT NULL CHECK (length(lichess_game_id) BETWEEN 8 AND 32),
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
    PRIMARY KEY (account_id, lichess_game_id),
    CHECK (played_at_ms <= last_move_at_ms),
    CHECK ((variant = 'standard' AND analysis_eligible = 1)
        OR (variant <> 'standard' AND analysis_eligible = 0)),
    CHECK ((opponent_kind = 'ai' AND opponent_ai_level IS NOT NULL)
        OR (opponent_kind <> 'ai' AND opponent_ai_level IS NULL)),
    CHECK ((opponent_kind = 'user' AND opponent_user_id IS NOT NULL)
        OR (opponent_kind <> 'user' AND opponent_user_id IS NULL))
) WITHOUT ROWID;

CREATE INDEX game_review_game_account_date
    ON game_review_game (account_id, played_at_ms DESC, lichess_game_id);
