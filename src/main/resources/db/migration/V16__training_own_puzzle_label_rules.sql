-- The tactic-label rules version the stored Own Puzzle labels were last refreshed with (Stage 8B).
-- The application refreshes the labels once when its rules version is newer than this, then
-- records it, so the refresh is skipped on every later start. No row means never refreshed.
CREATE TABLE training_own_puzzle_label_rules (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    rules_version INTEGER NOT NULL CHECK (rules_version >= 1)
);
