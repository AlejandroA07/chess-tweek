-- Stage 9 Part 1: pattern ladders (docs/planning/03-delivery-plan.md, owner decisions 2026-11-26).
--
-- A ladder is one pattern Theme (the Mate themes, Motifs, and Advanced groups) climbed by rating.
-- The ladder index holds only the quality-gated members of those Themes, ordered by rating, so a
-- request seeks the next Puzzle on a primary key and never sorts or scans a category. It is
-- rebuilt together with the category counts after every import, never on a request.

-- Quality-gated members of every pattern Theme in rating order. Like the category membership it
-- is derived data without foreign keys; selection joins training_puzzle_current, so a Puzzle
-- removed by a later import is never served even before the rebuild.
CREATE TABLE training_puzzle_ladder_member (
    category_id INTEGER NOT NULL,
    rating INTEGER NOT NULL,
    puzzle_id INTEGER NOT NULL,
    PRIMARY KEY (category_id, rating, puzzle_id)
) WITHOUT ROWID;

-- Ladder member counts per 100-point rating band (band = rating / 100), so a rating package's
-- count is a sum of at most 40 small rows.
CREATE TABLE training_puzzle_ladder_band (
    category_id INTEGER NOT NULL REFERENCES training_puzzle_category(id) ON DELETE RESTRICT,
    band INTEGER NOT NULL CHECK (band BETWEEN 1 AND 40),
    puzzle_count INTEGER NOT NULL CHECK (puzzle_count >= 1),
    PRIMARY KEY (category_id, band)
) WITHOUT ROWID;

-- Where the account stopped in one pattern and rating package: the last presented Puzzle's
-- rating and ID, and how many Puzzles were presented since the package was started or restarted.
-- The cursor is a position, not a reference, so it stays valid when that Puzzle is later removed.
CREATE TABLE training_puzzle_ladder_progress (
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    category_id INTEGER NOT NULL REFERENCES training_puzzle_category(id) ON DELETE RESTRICT,
    rating_package TEXT NOT NULL CHECK (rating_package IN (
        'up-to-1000', '1000-1400', '1400-1800', '1800-2200', '2200-2600', '2600-plus'
    )),
    last_rating INTEGER NOT NULL CHECK (last_rating BETWEEN 100 AND 4000),
    last_puzzle_id INTEGER NOT NULL CHECK (last_puzzle_id >= 1),
    presented INTEGER NOT NULL CHECK (presented >= 1),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= 0),
    PRIMARY KEY (account_id, category_id, rating_package)
) WITHOUT ROWID;

INSERT INTO training_puzzle_ladder_member (category_id, rating, puzzle_id)
SELECT m.category_id, c.rating, m.puzzle_id
FROM training_puzzle_category category
JOIN training_puzzle_classification classification
  ON classification.kind = category.kind
 AND classification.classification_key = category.classification_key
JOIN training_puzzle_category_member m ON m.category_id = category.id
JOIN training_puzzle_current c ON c.puzzle_id = m.puzzle_id
WHERE category.kind = 'theme'
  AND classification.group_name IN ('Mate themes', 'Motifs', 'Advanced')
  AND c.rating_deviation <= 90
  AND c.popularity >= 80
  AND c.play_count >= 200;

INSERT INTO training_puzzle_ladder_band (category_id, band, puzzle_count)
SELECT category_id, rating / 100, COUNT(*)
FROM training_puzzle_ladder_member
GROUP BY category_id, rating / 100;
