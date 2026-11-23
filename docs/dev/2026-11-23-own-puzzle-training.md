# Own Puzzle training

Stage 8 Part 8b adds the whole backend for training Own Puzzles, so that Part 8c is only the screens (owner decision, 2026-11-23). Own Puzzles follow the performance rules in `docs/planning/07-performance-training-lists.md`, the same as normal Puzzles, but keep completely separate attempts, Training Lists, sessions, and history.

## Owner decisions (2026-11-23)

- 8b covers all backend:
  - Attempts and the list rules.
  - New, Discard, and Restore.
  - Repetition sessions and Mastered practice.
  - The opening, phase, and tactic summaries.
  - The endpoints for all of these.
- Openings are grouped by family: the stored name before its first `:` (Lichess, "Indian Game: London System") or `...` (chess.com, "Indian Game...3.e3 d5"). A puzzle without an opening goes under "Unknown opening".

## One engine, two kinds of Puzzle

- `training.internal.persistence.JdbcPerformanceState` holds the attempt, Training List, and Practice Session SQL that used to live in `JdbcPuzzlePerformanceStore`.
  - Each kind builds one instance over its own fixed table names.
  - Its transactions are opened in code, so they hold wherever the solver calls it.
- `training.internal.performance.AttemptSolver` holds the server-checked solving that used to live in `PuzzleTrainingService`: moves, the opponent's replies, Wrong Moves, the hint, Show solution, completion, and immediate demotion.
  - It works on a position with the owner to move and a solution line.
  - A practice entry never changes list state.
- The normal Puzzle store and service delegate to both. Their tests are unchanged and still pass.
- `composition.web.TrainingRequests` holds the request checks both training controllers use:
  - A write checks the same-origin request, then its input, then the session.
  - A read checks the session.
  - A storage or rule failure becomes `503 training_unavailable`.

## Delivered boundary

- `training.OwnPuzzleTraining`, implemented by `OwnPuzzleTrainingService` over `JdbcOwnPuzzleTrainingStore`. Every query is scoped to the account.
- **New Own Puzzles:** an Own Puzzle with no Training List and no discard, newest source game first. A first attempt starts only from New; leaving it unfinished keeps the puzzle New. The New check and the attempt run in one transaction.
- **Discard:**
  - Allowed only before classification; otherwise `CLASSIFIED`.
  - It abandons any attempt in progress in the same transaction, so that attempt can never classify the puzzle.
  - Discarding twice changes nothing.
- **Restore:** deletes the discard, returning the puzzle New with no progress.
- **Repetition and Mastered practice:**
  - Sessions cover one active list.
  - Mastered practice covers one opening family, phase, or tactic label and never changes state.
- **Lists:** a page of the Own Puzzles in any Training List, so the dashboard can show each list.
- **Summaries:** counts by opening family, phase (in game order), and tactic theme over every kept Own Puzzle, and the same over Mastered Own Puzzles.
- **Migration V15:**
  - `training_own_puzzle_discard`, `_performance`, `_attempt`, `_session`, and `_session_item`.
  - Every state row references the Own Puzzle through `(account_id, puzzle_id)`, so no account can hold state on another's puzzle.
  - Two triggers keep a discarded puzzle from being classified and a classified puzzle from being discarded.
- **Endpoints under `/api/training/own-puzzles`:**
  - `GET /` returns the overview. Part 8c adds `newBySource`.
  - `GET /new` and `GET /discarded` take `offset` and `limit`, with a limit of 1 to 50. Part 8c adds `source` to `GET /new` (`docs/dev/2026-11-23-own-puzzles-interface.md`).
  - `POST /{id}/discard` and `POST /{id}/restore` return `204`.
  - `POST /new/attempts` and `POST /{id}/attempts`. Part 8c adds `source` and `order` to `POST /new/attempts`.
  - `POST /attempts/{id}/moves`, `/hint`, and `/reveal`.
  - `GET /lists/{list}` pages the Own Puzzles in one Training List, including Mastered.
  - `GET /repetition` and `GET /repetition/mastered`.
  - `POST /repetition/{list}/sessions` and `POST /repetition/mastered/{opening|phase|tactic}/{key}/sessions`.
  - `POST /sessions/{id}/attempts`.
  - Every response is `no-store`, and no solution appears before Show solution.
  - New error codes: `own_puzzle_not_found`, `own_puzzle_classified`, `own_puzzle_not_discarded`, and `no_new_own_puzzles`.

## Verification contract

- `OwnPuzzleTrainingMigrationTest` covers:
  - The account-scoped foreign keys.
  - The two triggers.
  - The entry, list, and session-group CHECKs.
  - The indexes.
- `OwnPuzzleTrainingServiceTest` uses a real context and a temporary database. It covers:
  - New order, and an unfinished attempt keeping a puzzle New.
  - Every first-attempt classification.
  - A three-move solution with the opponent's reply, and any mating move solving.
  - The hint and Show solution.
  - Discard and Restore, including an attempt abandoned by Discard.
  - Sessions that serve each classified puzzle once.
  - Mastered practice by group that never changes state.
  - Account scoping, and separation from normal Puzzles.
  - Progress across a restart.
- `OpeningFamilyTest` covers chess.com, Lichess, plain, and missing names.
- `OwnPuzzleTrainingControllerTest` covers:
  - Every endpoint's happy path and fixed error codes.
  - Page validation before authorization.
  - Cross-origin refusal and disconnected sessions before the service is touched.
  - `503` on failure.
  - No solution in attempt or page responses.

`node scripts/verify.mjs` remains the only complete definition of done.
