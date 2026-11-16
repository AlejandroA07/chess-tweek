-- The chess.com username the owner entered for an account. Chess.com usernames are 3 to 25
-- letters, digits, underscores, or hyphens; the letter case is kept as entered.
CREATE TABLE game_review_chesscom_player (
    account_id TEXT NOT NULL PRIMARY KEY CHECK (length(account_id) BETWEEN 1 AND 32),
    username TEXT NOT NULL CHECK (
        length(username) BETWEEN 3 AND 25 AND username NOT GLOB '*[^A-Za-z0-9_-]*'
    ),
    saved_at_ms INTEGER NOT NULL CHECK (saved_at_ms >= 0)
) WITHOUT ROWID;
