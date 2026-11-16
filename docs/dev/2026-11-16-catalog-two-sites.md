# Catalog for two sites

Stage 5 Part 1 prepares the catalog for chess.com games. Nothing changes on screen; Lichess games behave as before.

## Storage

Migration `V6__game_review_game_source.sql` keys games and analysis versions by account, `source` (`lichess` or `chesscom`), and `game_id`. SQLite cannot change a primary key in place, so the game, analysis version, and analysis move tables are rebuilt: the new tables are filled from the old ones, the old ones are dropped moves first (dropping a version cascades to its moves), and the new tables take the old names, which also updates their foreign keys. Indexes and the immutability triggers are recreated.

- Every game and analysis stored before V6 becomes a Lichess game with the same values, version IDs, and moves. New version IDs continue after the highest copied one.
- Lichess IDs stay 8 to 32 letters and digits. Chess.com IDs are 1 to 20 decimal digits. The same ID from both sites is two different games.
- The value checks (status, speed, variant, and the rest) are unchanged; chess.com values are mapped onto them in Part 2.

`MultiSourceCatalogMigrationTest` builds the V5 schema with Flyway, stores games, versions, and moves, migrates, and checks that they carry over, that foreign keys and integrity hold, and that the new key and checks work. Before committing, V6 was also applied to a scratch copy of the owner's database: 50 games, 2 versions, and 39 moves before and after, with clean foreign key and integrity checks.

## Code

- `GameRef` (`gamereview`) is a source and a validated ID. It replaces the bare Lichess ID in `CatalogGame`, the catalog store, the analysis version store, analysis jobs, and game review. `GameRef.parse` is the only way untrusted path values become a game.
- `GET /api/games/{source}/{gameId}/review` and `POST /api/games/{source}/{gameId}/analyses` replace the ID-only paths. An unknown source or an ID outside that source's format is `400` before any session check.
- Catalog cards and analysis jobs carry `source`.
- Dashboard ratings and the bot record count only Lichess games until per-site ratings arrive in Part 3.
- The interface keys selection, analysis jobs, and review links by source and ID (`frontend/src/gameRef.ts`). Review links are `#/games/{source}/{gameId}`.

## Tests

- `MultiSourceCatalogMigrationTest` (2), `GameRefTest` (3), and new cases in `GameCatalogPersistenceTest` (the same ID from two sites), `GameRatingContextPersistenceTest` (chess.com games left out), and the review and analysis controller tests (unknown source, wrong ID format).
- `gameRef.test.ts` (3) and review-link cases for an invalid ID, the wrong site, an unknown site, and the old ID-only form.
