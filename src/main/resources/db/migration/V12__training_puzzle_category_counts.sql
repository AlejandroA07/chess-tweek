-- The number of Puzzles in each category, refreshed after every import so the Themes and
-- Openings pages never count the membership table on a request.
ALTER TABLE training_puzzle_category
    ADD COLUMN puzzle_count INTEGER NOT NULL DEFAULT 0 CHECK (puzzle_count >= 0);

UPDATE training_puzzle_category
SET puzzle_count = (
    SELECT COUNT(*) FROM training_puzzle_category_member m
    WHERE m.category_id = training_puzzle_category.id
);
