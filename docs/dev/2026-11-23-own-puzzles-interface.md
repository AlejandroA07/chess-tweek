# Own Puzzles interface

Stage 8 Part 8c adds the Own Puzzles screens over the Part 8b endpoints (`docs/dev/2026-11-23-own-puzzle-training.md`). It also adds a small backend extension that the owner asked for. The visual rules are the "Own Puzzles view" section of `docs/app-material/DESIGN.md`. With this part, Stage 8 is complete.

## Owner decisions (2026-11-23)

- Lichess and chess.com Own Puzzles are shown together. The owner can filter New Own Puzzles by site and solve them newest first or in random order.
- "Open source game" opens the exact Game Review position the puzzle was created from.

## Backend extension

- `OwnPuzzleTraining.Site` (`ALL`, `LICHESS`, `CHESSCOM`) and `NewOrder` (`NEWEST`, `RANDOM`) are new.
  - `newPuzzles(account, site, offset, limit)` and `startNext(account, site, order)` take them.
  - The earlier two-argument forms remain as defaults for all sites, newest first.
- A random start picks one New puzzle uniformly with `SecureRandom` over the filtered count. The count, the pick, and the start share one transaction, so a discard or solve in another tab can neither push the offset past the end nor start a stale pick. The overview reads its counts in one transaction too, so the total always equals the split by site.
- `Overview` gains `newLichess` and `newChessCom`.
- Endpoints:
  - `GET /api/training/own-puzzles/new` takes `source=all|lichess|chesscom`, with `all` as the default.
  - `POST /new/attempts` takes `source` and `order=newest|random`, with `newest` as the default.
  - `GET /` adds `newBySource: {lichess, chesscom}`.
  - An unknown value is refused with `400 invalid_training_request` before the session is checked. A write still checks the same-origin request first.
- The New list stays newest first, because a random order cannot be paged stably.

## Interface

- Routes, all allowlisted in `ownPuzzleRoute` (`OwnPuzzles.tsx`):
  - `#/puzzles/own`: the dashboard.
  - `#/puzzles/own/new` and `#/puzzles/own/new/{site}/{order}`: New Own Puzzles.
  - `#/puzzles/own/solve/{site}/{order}` and `.../{id}`: the New player, optionally opening one chosen puzzle first.
  - `#/puzzles/own/discarded`: Discarded Own Puzzles.
  - `#/puzzles/own/lists/{list-slug|mastered}`: the puzzles in one Training List.
  - `#/puzzles/own/repetition`, `#/puzzles/own/repetition/{list-slug}`, `#/puzzles/own/repetition/mastered`, and `#/puzzles/own/repetition/mastered/{opening|phase|tactic}/{key}`.
  - A Mastered group key is URI-encoded. It is decoded and validated as the server validates it, so a malformed key is not a route.
- The side bar adds an "Own Puzzles" link, and "Repetition: own puzzles" is now a link. The Puzzle dashboard's Own Puzzles card opens the dashboard. Nothing in Puzzles is Planned any more.
- `PuzzlePlayer.tsx` is the one player for both kinds.
  - A `PlayerSource` supplies where attempts come from (one at a time, or a Practice Session with automatic rounds), the API calls, the wording, an optional Discard, and the completion links.
  - The normal-Puzzle player in `PuzzleTraining.tsx` is now such a source. Its behaviour and tests are unchanged.
- `ownPuzzleApi.ts` parses every Own Puzzle response strictly. It checks the source game with `isGameRef` and a ply of 1 to 1200, so a link can never point outside a Game Review route. It shares the request helpers and move, hint, reveal, and session parsers with `puzzles.ts`. The error codes `own_puzzle_*` and `no_new_own_puzzles` are in `trainingErrorCodes.ts`, which `TrainingErrorCodesTest` keeps aligned with the server.
- `puzzleViews.tsx` holds the list slugs, list rules, `useLoaded`, and `ListCard`, which both kinds use.
- Game Review accepts `#/games/{site}/{id}/ply/{n}`, with `n` from 1 to 1200.
  - It requests the My Moves review, shows the position after that move, and focuses its finding, as choosing it from the findings list does.
  - A ply without a finding shows the position only.
  - A later reload keeps the owner's own position.

## Verification contract

- `OwnPuzzleTrainingServiceTest` covers the site filter, the newest and random starts within it, random reaching every New puzzle, and never reaching a discarded or classified one, and an empty filter.
- `OwnPuzzleTrainingControllerTest` covers the new parameters and `newBySource`, and the refusal of unknown values before the service is touched.
- `OwnPuzzles.test.tsx` covers:
  - Route parsing, including refused keys.
  - The dashboard and its empty state.
  - The New list with its site filter, order, pages, long opening names, and source links.
  - Discard confirmation, focus, and a refused discard.
  - Restore.
  - A full New solve with the opponent's reply under StrictMode, with exactly one start.
  - Discard in the player and an empty filtered pool.
  - A chosen puzzle that is no longer New.
  - An Own Practice Session.
  - The Own Repetition and Mastered groups and Mastered practice.
  - A revoked session.
  - Rejection of a source game outside the allowed shape.
- `GameReview.test.tsx` covers the ply link with and without a finding, and a ply past the limit. `App.test.tsx` and `PuzzleTraining.test.tsx` cover the new side-bar and dashboard links.

`node scripts/verify.mjs` remains the only complete definition of done.
