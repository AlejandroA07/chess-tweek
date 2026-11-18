CREATE TABLE training_puzzle_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL CHECK (length(provider) BETWEEN 1 AND 64),
    snapshot_date TEXT NOT NULL CHECK (
        length(snapshot_date) = 10
        AND snapshot_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
    ),
    download_checksum TEXT CHECK (
        download_checksum IS NULL OR length(download_checksum) = 64
    ),
    importer_version TEXT NOT NULL CHECK (length(importer_version) BETWEEN 1 AND 64),
    imported_at_ms INTEGER NOT NULL CHECK (imported_at_ms >= 0),
    UNIQUE (provider, snapshot_date)
);

CREATE TABLE training_puzzle (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL CHECK (length(provider) BETWEEN 1 AND 64),
    external_id TEXT NOT NULL CHECK (length(external_id) BETWEEN 1 AND 32),
    source_fen TEXT NOT NULL CHECK (length(source_fen) BETWEEN 20 AND 120),
    source_moves_uci TEXT NOT NULL CHECK (length(source_moves_uci) BETWEEN 9 AND 600),
    setup_move_uci TEXT NOT NULL CHECK (length(setup_move_uci) BETWEEN 4 AND 5),
    position_fen TEXT NOT NULL CHECK (length(position_fen) BETWEEN 20 AND 120),
    solution_uci TEXT NOT NULL CHECK (length(solution_uci) BETWEEN 4 AND 550),
    game_url TEXT NOT NULL CHECK (length(game_url) BETWEEN 20 AND 240),
    license TEXT NOT NULL CHECK (license = 'CC0-1.0'),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    UNIQUE (provider, external_id)
);

CREATE TABLE training_puzzle_observation (
    puzzle_id INTEGER NOT NULL REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    snapshot_id INTEGER NOT NULL REFERENCES training_puzzle_snapshot(id) ON DELETE RESTRICT,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 400 AND 4000),
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
    ),
    PRIMARY KEY (puzzle_id, snapshot_id)
) WITHOUT ROWID;

CREATE INDEX training_puzzle_observation_rating
    ON training_puzzle_observation(snapshot_id, rating, popularity, play_count);

CREATE TABLE training_puzzle_classification (
    kind TEXT NOT NULL CHECK (kind IN ('theme', 'opening_family', 'opening_variation')),
    classification_key TEXT NOT NULL CHECK (length(classification_key) BETWEEN 1 AND 120),
    parent_key TEXT CHECK (parent_key IS NULL OR length(parent_key) BETWEEN 1 AND 120),
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 180),
    group_name TEXT NOT NULL CHECK (length(group_name) BETWEEN 1 AND 80),
    drill_oriented INTEGER NOT NULL CHECK (drill_oriented IN (0, 1)),
    PRIMARY KEY (kind, classification_key)
) WITHOUT ROWID;

CREATE TABLE training_puzzle_tag (
    puzzle_id INTEGER NOT NULL,
    snapshot_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    classification_key TEXT NOT NULL,
    PRIMARY KEY (puzzle_id, snapshot_id, kind, classification_key),
    FOREIGN KEY (puzzle_id, snapshot_id)
        REFERENCES training_puzzle_observation(puzzle_id, snapshot_id) ON DELETE CASCADE,
    FOREIGN KEY (kind, classification_key)
        REFERENCES training_puzzle_classification(kind, classification_key) ON DELETE RESTRICT
) WITHOUT ROWID;

CREATE INDEX training_puzzle_tag_lookup
    ON training_puzzle_tag(snapshot_id, kind, classification_key, puzzle_id);

CREATE TABLE training_puzzle_collection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id INTEGER NOT NULL REFERENCES training_puzzle_snapshot(id) ON DELETE RESTRICT,
    kind TEXT NOT NULL,
    classification_key TEXT NOT NULL,
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 180),
    target_count INTEGER NOT NULL CHECK (target_count BETWEEN 20 AND 100),
    actual_count INTEGER NOT NULL CHECK (actual_count BETWEEN 0 AND 100),
    available INTEGER NOT NULL CHECK (available IN (0, 1)),
    FOREIGN KEY (kind, classification_key)
        REFERENCES training_puzzle_classification(kind, classification_key) ON DELETE RESTRICT,
    UNIQUE (snapshot_id, kind, classification_key)
);

CREATE TABLE training_puzzle_collection_member (
    collection_id INTEGER NOT NULL REFERENCES training_puzzle_collection(id) ON DELETE CASCADE,
    puzzle_id INTEGER NOT NULL REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 100),
    selection_score INTEGER NOT NULL,
    PRIMARY KEY (collection_id, puzzle_id),
    UNIQUE (collection_id, ordinal)
) WITHOUT ROWID;

CREATE INDEX training_puzzle_collection_member_puzzle
    ON training_puzzle_collection_member(puzzle_id, collection_id);

CREATE TABLE training_method_exercise_puzzle (
    method_key TEXT NOT NULL CHECK (length(method_key) BETWEEN 1 AND 64),
    content_version INTEGER NOT NULL CHECK (content_version BETWEEN 1 AND 1000),
    exercise_key TEXT NOT NULL CHECK (length(exercise_key) BETWEEN 1 AND 64),
    puzzle_id INTEGER NOT NULL REFERENCES training_puzzle(id) ON DELETE RESTRICT,
    snapshot_id INTEGER NOT NULL REFERENCES training_puzzle_snapshot(id) ON DELETE RESTRICT,
    PRIMARY KEY (method_key, content_version, exercise_key),
    FOREIGN KEY (puzzle_id, snapshot_id)
        REFERENCES training_puzzle_observation(puzzle_id, snapshot_id) ON DELETE RESTRICT
) WITHOUT ROWID;

INSERT INTO training_puzzle_classification (
    kind, classification_key, parent_key, display_name, group_name, drill_oriented
) VALUES
    ('theme', 'mix', NULL, 'Healthy mix', 'Recommended', 0),
    ('theme', 'opening', NULL, 'Opening', 'Phases', 0),
    ('theme', 'middlegame', NULL, 'Middlegame', 'Phases', 0),
    ('theme', 'endgame', NULL, 'Endgame', 'Phases', 0),
    ('theme', 'rookEndgame', NULL, 'Rook endgame', 'Phases', 1),
    ('theme', 'bishopEndgame', NULL, 'Bishop endgame', 'Phases', 1),
    ('theme', 'pawnEndgame', NULL, 'Pawn endgame', 'Phases', 1),
    ('theme', 'knightEndgame', NULL, 'Knight endgame', 'Phases', 1),
    ('theme', 'queenEndgame', NULL, 'Queen endgame', 'Phases', 1),
    ('theme', 'queenRookEndgame', NULL, 'Queen and rook endgame', 'Phases', 1),
    ('theme', 'advancedPawn', NULL, 'Advanced pawn', 'Motifs', 1),
    ('theme', 'attackingF2F7', NULL, 'Attacking f2 or f7', 'Motifs', 1),
    ('theme', 'capturingDefender', NULL, 'Capture the defender', 'Motifs', 1),
    ('theme', 'discoveredAttack', NULL, 'Discovered attack', 'Motifs', 1),
    ('theme', 'doubleCheck', NULL, 'Double check', 'Motifs', 1),
    ('theme', 'exposedKing', NULL, 'Exposed king', 'Motifs', 1),
    ('theme', 'fork', NULL, 'Fork', 'Motifs', 1),
    ('theme', 'hangingPiece', NULL, 'Hanging piece', 'Motifs', 1),
    ('theme', 'kingsideAttack', NULL, 'Kingside attack', 'Motifs', 1),
    ('theme', 'pin', NULL, 'Pin', 'Motifs', 1),
    ('theme', 'queensideAttack', NULL, 'Queenside attack', 'Motifs', 1),
    ('theme', 'sacrifice', NULL, 'Sacrifice', 'Motifs', 1),
    ('theme', 'skewer', NULL, 'Skewer', 'Motifs', 1),
    ('theme', 'trappedPiece', NULL, 'Trapped piece', 'Motifs', 1),
    ('theme', 'attraction', NULL, 'Attraction', 'Advanced', 1),
    ('theme', 'clearance', NULL, 'Clearance', 'Advanced', 1),
    ('theme', 'collinearMove', NULL, 'Collinear move', 'Advanced', 1),
    ('theme', 'discoveredCheck', NULL, 'Discovered check', 'Advanced', 1),
    ('theme', 'defensiveMove', NULL, 'Defensive move', 'Advanced', 1),
    ('theme', 'deflection', NULL, 'Deflection', 'Advanced', 1),
    ('theme', 'interference', NULL, 'Interference', 'Advanced', 1),
    ('theme', 'intermezzo', NULL, 'Intermezzo', 'Advanced', 1),
    ('theme', 'quietMove', NULL, 'Quiet move', 'Advanced', 1),
    ('theme', 'xRayAttack', NULL, 'X-Ray attack', 'Advanced', 1),
    ('theme', 'zugzwang', NULL, 'Zugzwang', 'Advanced', 1),
    ('theme', 'mate', NULL, 'Checkmate', 'Mates', 1),
    ('theme', 'mateIn1', NULL, 'Mate in 1', 'Mates', 1),
    ('theme', 'mateIn2', NULL, 'Mate in 2', 'Mates', 1),
    ('theme', 'mateIn3', NULL, 'Mate in 3', 'Mates', 1),
    ('theme', 'mateIn4', NULL, 'Mate in 4', 'Mates', 1),
    ('theme', 'mateIn5', NULL, 'Mate in 5', 'Mates', 1),
    ('theme', 'anastasiaMate', NULL, 'Anastasia''s mate', 'Mate themes', 1),
    ('theme', 'arabianMate', NULL, 'Arabian mate', 'Mate themes', 1),
    ('theme', 'backRankMate', NULL, 'Back rank mate', 'Mate themes', 1),
    ('theme', 'balestraMate', NULL, 'Balestra mate', 'Mate themes', 1),
    ('theme', 'blindSwineMate', NULL, 'Blind Swine mate', 'Mate themes', 1),
    ('theme', 'bodenMate', NULL, 'Boden''s mate', 'Mate themes', 1),
    ('theme', 'cornerMate', NULL, 'Corner mate', 'Mate themes', 1),
    ('theme', 'doubleBishopMate', NULL, 'Double bishop mate', 'Mate themes', 1),
    ('theme', 'dovetailMate', NULL, 'Dovetail mate', 'Mate themes', 1),
    ('theme', 'epauletteMate', NULL, 'Epaulette mate', 'Mate themes', 1),
    ('theme', 'hookMate', NULL, 'Hook mate', 'Mate themes', 1),
    ('theme', 'killBoxMate', NULL, 'Kill box mate', 'Mate themes', 1),
    ('theme', 'pillsburysMate', NULL, 'Pillsbury''s mate', 'Mate themes', 1),
    ('theme', 'morphysMate', NULL, 'Morphy''s mate', 'Mate themes', 1),
    ('theme', 'operaMate', NULL, 'Opera mate', 'Mate themes', 1),
    ('theme', 'swallowstailMate', NULL, 'Swallow tail mate', 'Mate themes', 1),
    ('theme', 'triangleMate', NULL, 'Triangle mate', 'Mate themes', 1),
    ('theme', 'vukovicMate', NULL, 'Vukovic mate', 'Mate themes', 1),
    ('theme', 'smotheredMate', NULL, 'Smothered mate', 'Mate themes', 1),
    ('theme', 'castling', NULL, 'Castling', 'Special moves', 1),
    ('theme', 'enPassant', NULL, 'En passant', 'Special moves', 1),
    ('theme', 'promotion', NULL, 'Promotion', 'Special moves', 1),
    ('theme', 'underPromotion', NULL, 'Underpromotion', 'Special moves', 1),
    ('theme', 'equality', NULL, 'Equality', 'Goals', 0),
    ('theme', 'advantage', NULL, 'Advantage', 'Goals', 0),
    ('theme', 'crushing', NULL, 'Crushing', 'Goals', 0),
    ('theme', 'oneMove', NULL, 'One-move puzzle', 'Lengths', 0),
    ('theme', 'short', NULL, 'Short puzzle', 'Lengths', 0),
    ('theme', 'long', NULL, 'Long puzzle', 'Lengths', 0),
    ('theme', 'veryLong', NULL, 'Very long puzzle', 'Lengths', 0),
    ('theme', 'master', NULL, 'Master games', 'Origin', 0),
    ('theme', 'masterVsMaster', NULL, 'Master vs Master games', 'Origin', 0),
    ('theme', 'superGM', NULL, 'Super GM games', 'Origin', 0);
