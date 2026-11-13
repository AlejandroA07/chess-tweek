ALTER TABLE game_review_game
    ADD COLUMN owner_rating_change INTEGER
        CHECK (owner_rating_change IS NULL OR owner_rating_change BETWEEN -1000 AND 1000);
