-- Stage 10 Part 1: My games statistics for the opening explorer.
--
-- Each stored standard game is replayed once and its opening positions are indexed, so the
-- explorer can count the owner's games through any position, including transpositions. A
-- position key is the FEN without move counters and with an en passant square only when the
-- capture is legal, the convention the Lichess explorer uses. Only the first 60 plies are
-- indexed: the explorer is about openings, and later positions rarely repeat across games.
--
-- The index is derived data: it is filled when the explorer first asks for an account's games
-- and catches up with games retrieved since. A game whose moves cannot be replayed is marked
-- indexed with no positions, so it is never retried.
CREATE TABLE game_review_position_index_game (
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    source TEXT NOT NULL CHECK (source IN ('lichess', 'chesscom')),
    game_id TEXT NOT NULL,
    indexed_plies INTEGER NOT NULL CHECK (indexed_plies BETWEEN 0 AND 60),
    PRIMARY KEY (account_id, source, game_id),
    FOREIGN KEY (account_id, source, game_id)
        REFERENCES game_review_game (account_id, source, game_id) ON DELETE CASCADE
) WITHOUT ROWID;

-- One row per position a game passed through, with the move played from it. The game's last
-- indexed position has no next move when the game ended inside the indexed plies.
CREATE TABLE game_review_game_position (
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    source TEXT NOT NULL CHECK (source IN ('lichess', 'chesscom')),
    game_id TEXT NOT NULL,
    ply INTEGER NOT NULL CHECK (ply BETWEEN 0 AND 60),
    position_key TEXT NOT NULL CHECK (length(position_key) BETWEEN 20 AND 100),
    next_uci TEXT CHECK (next_uci IS NULL OR length(next_uci) BETWEEN 4 AND 5),
    next_san TEXT CHECK (next_san IS NULL OR length(next_san) BETWEEN 2 AND 8),
    PRIMARY KEY (account_id, source, game_id, ply),
    CHECK ((next_uci IS NULL) = (next_san IS NULL)),
    FOREIGN KEY (account_id, source, game_id)
        REFERENCES game_review_position_index_game (account_id, source, game_id)
        ON DELETE CASCADE
) WITHOUT ROWID;

CREATE INDEX game_review_game_position_key
    ON game_review_game_position (account_id, position_key, source, game_id);
