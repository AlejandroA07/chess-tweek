-- Stage 10 Part 2: the owner's opening notes, one per position.
--
-- The project ships starter notes for the London System and the Caro-Kann Defense as read-only
-- content. A row here is the owner's own text for a position: it replaces the starter note there,
-- or adds a note to a position without one. Empty text hides a starter note; deleting the row
-- brings the starter note back. The FEN is kept so the position can be opened from the notes list.
CREATE TABLE training_opening_note (
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    position_key TEXT NOT NULL CHECK (length(position_key) BETWEEN 20 AND 100),
    fen TEXT NOT NULL CHECK (length(fen) BETWEEN 20 AND 100),
    note_text TEXT NOT NULL CHECK (length(note_text) <= 4000),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= 0),
    PRIMARY KEY (account_id, position_key)
) WITHOUT ROWID;
