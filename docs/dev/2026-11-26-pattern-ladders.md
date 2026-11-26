# Pattern ladders

Stage 9 Part 1 implements the pattern ladders the owner decided on 2026-11-26 (`docs/planning/03-delivery-plan.md`, Stage 9). A ladder is one pattern climbed by rating. The owner picks a pattern and a fixed rating package, and plays its puzzles from the lowest rating to the highest. They can stop and switch to another package or pattern at any time.

## Rules

- **Patterns:** the Lichess Themes in the catalog groups Mate themes, Motifs, and Advanced (44 Themes). The tags are trusted as they are; there is no project check of the final position.
- **Quality gates:** rating deviation at most 90, popularity at least 80, and at least 200 plays, as for the guided course.
- **Rating packages:** Below 1000, 1000–1399, 1400–1799, 1800–2199, 2200–2599, and 2600 and above. `PatternLadders.RatingPackage` holds the bounds, lower inclusive and upper exclusive. The URL keys are `up-to-1000`, `1000-1400`, `1400-1800`, `1800-2200`, `2200-2600`, and `2600-plus`.
- **Order:** rating, then Puzzle ID for equal ratings.
- **Skipped:** Puzzles the account has Mastered, as By Category does. Puzzles in other Training Lists are included.
- **Resume:** each account, pattern, and package keeps a place: the last presented Puzzle's rating and ID, and its position in the package (since Part 2, `docs/dev/2026-11-26-fast-ladder-and-jump.md`; Part 1 counted Puzzles shown). Starting the next Puzzle moves the place there, so a puzzle left unfinished is not shown again until the package is restarted. Restart forgets the place only; Training List state is never touched.
- **Attempts:** a ladder attempt is an ordinary `category` attempt of the shared Puzzle engine, so Wrong Moves, hints, Show solution, and Solve Time update the same Training List state as every other Puzzle. Ladders reference Puzzles by ID and never copy them.

## Storage (migration V17)

- `training_puzzle_ladder_member (category_id, rating, puzzle_id)` is the ladder index: the quality-gated members of every pattern Theme, keyed in rating order. The next Puzzle is one primary-key seek.
- `training_puzzle_ladder_band (category_id, band, puzzle_count)` stores counts per 100-point band (`rating / 100`), so a package size is a sum of a few small rows. No request counts members.
- `training_puzzle_ladder_progress` stores each account's place per pattern and package.
- The index and band counts are rebuilt inside `PuzzleCategoryIndex.refreshCounts()`, which already runs once after every verified corpus import and every bounded or built-in import. V17 builds them once for an existing library.
- Selection joins `training_puzzle_current`, so a Puzzle that a later import removed is never served, even while an import is paused and the index has not been rebuilt yet.

Measured on a copy of the owner's library (2026-11-01 snapshot, 6,193,530 Puzzles) on the target Mac:

- V17 took 7.6 seconds and indexed 3,157,500 ladder entries in 1,045 band rows. The database grew by about 45 MB.
- Listing the patterns took 2 ms. A package's next-Puzzle seek and its size each took under 1 ms. The query plan for the seek is a primary-key range search with key lookups for the current and Mastered checks.
- Sizes range from Balestra mate (1,124) to Fork (456,127). Opera mate has 27,763.

## Endpoints

All require the connected owner session. Writes are same-origin `POST`s, and inputs are checked against allowlists before the session is authorized. Every response is `no-store`.

- `GET /api/training/puzzles/ladders`: patterns with their ladder sizes.
- `GET /api/training/puzzles/ladders/{pattern}`: the six packages with sizes and the account's place. `404 pattern_not_found` for any key that is not a ladder pattern.
- `POST /api/training/puzzles/ladders/{pattern}/{package}/attempts`: starts the next Puzzle and returns the attempt (without its solution) and the new place. `409 ladder_complete` when nothing is left after the place.
- `POST /api/training/puzzles/ladders/{pattern}/{package}/restart`: `204`.

Moves, hints, and Show solution use the normal `/api/training/puzzles/attempts/{id}` endpoints.

## Interface

The routes are `#/puzzles/ladders`, `#/puzzles/ladders/{pattern}`, and `#/puzzles/ladders/{pattern}/{package}`. `ladderRoute` accepts only allowlisted package keys. The Puzzles sub-menu and dashboard gain Pattern ladders. The visual rules are in the "Pattern ladders view" section of `docs/app-material/DESIGN.md`. The shared player gained two optional features: a `fact` on an attempt (the "Ladder" place) and an `endAction` that replaces "Try another puzzle" when a package is used up.

## Tests

- `PatternLadderTest`: the gates and pattern groups, rating then ID order, resume per package and account, the end of a package, restart, the Mastered and removed-Puzzle skips, and a ladder solve updating Training List state.
- `PatternLadderMigrationTest`: V17 indexes an existing library, rejects a Puzzle below each gate, and leaves out Themes outside the pattern groups.
- `PatternLadderControllerTest`: responses, no solution in the attempt, error codes, input rejected before authorization, cross-origin writes refused, disconnected sessions denied, and storage failures as `training_unavailable`.
- `PatternLadders.test.tsx`: route parsing, the dashboard card, the pattern list order, package cards with Start, Continue, the empty state, and the Restart confirmation, the player's place and end action, load failure with retry, and a rejected response shape.
- `DatabaseFoundationTest` now counts 17 migrations.
