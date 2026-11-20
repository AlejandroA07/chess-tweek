# Puzzle performance core

Stage 7 Part 3a adds the backend for performance Training Lists over normal Lichess Puzzles: account-scoped attempts, server-checked solving, the list rules, exclusion-aware selection, and corpus retention. It adds no interface. Part 3b adds the Puzzle dashboard, 3c the Repetition dashboard and Practice Sessions, and 3d the guided-method link (`docs/planning/03-delivery-plan.md`).

## Delivered boundary

- Flyway migration V11 adds `training_puzzle_performance` (one row per account and classified Puzzle: list, consecutive successes, and Retiring strikes) and `training_puzzle_attempt` (every attempt, including unfinished ones, with its server-side solving cursor). Both reference the canonical Puzzle by ID only.
- `PuzzleTraining` is the public interface. It provides Random, By Category, move, hint, Show solution, and per-Puzzle performance state. `PerformanceRules` is a pure function of the current state and one completed attempt. It implements the decision table in `docs/planning/07-performance-training-lists.md` exactly.
- `POST /api/training/puzzles/random/attempts`, `.../categories/{theme|opening-family|opening-variation}/{key}/attempts`, `.../attempts/{id}/moves`, `.../attempts/{id}/hint`, and `.../attempts/{id}/reveal` require a same-origin loopback request, validated input, and the connected owner session, checked in that order. Errors are fixed codes, and every response is `no-store`.

## Solving rules

- A started attempt exposes the position after the setup move, the setup move, the rating, the number of owner moves, the fast-solve limit, and the current list. It never exposes the solution.
- The server checks each move. A legal move that matches the solution, or any move that mates (as on Lichess), is correct, and the response carries the opponent's reply. Any other legal move is a Wrong Move, and the position stays the same. Illegal moves are refused and not counted.
- Solve Time runs on the server clock from the start of the attempt until the final correct move. The fast-solve limit is 6 seconds plus 5 for each further owner move.
- The hint returns the square of the piece that makes the next correct move. Show solution returns the remaining line and completes the attempt as hinted (owner decisions, 2026-11-20).
- Starting an attempt abandons the account's other active attempt. An abandoned or unfinished attempt never classifies its Puzzle.
- Every write is guarded by the expected solving cursor and the active status, so a repeated or concurrent request cannot count a move or a completion twice. A completion and its list state commit in one transaction.

## Selection

- Random seeks the first Puzzle at or after a uniform point in the ID range that has no list state for the account, and wraps once to the start of the range. By Category does the same within one category's members and skips only Mastered Puzzles. This addresses the ADR-010 reopen condition: the walk past excluded Puzzles is bounded by the account's own classified Puzzles, not by the size of the corpus.

## Retention

- `JdbcPuzzleCorpusStore.RETAINED` now also keeps a Puzzle that any account's attempt or list state references. Both new tables have a `puzzle_id` index for this check. Their `ON DELETE RESTRICT` foreign keys are a backstop: without the retention rule, a refresh pauses instead of deleting attempted Puzzles.

## Verification contract

- `PerformanceRulesTest` covers every first-attempt priority, every list transition, Retiring strikes and cumulative successes, the terminal Mastered list, and the fast-solve limit.
- `PuzzleTrainingServiceTest` runs against SQLite and a controllable clock. It covers Random never returning a classified Puzzle (per account), Wrong Moves with an unchanged position, refused illegal moves, priority classification, slow solves, the hint and Show solution, abandonment of unfinished attempts, account scoping, By Category applying the current list and skipping Mastered Puzzles, persistence across restart, and alternative mates.
- `PuzzleTrainingControllerTest` covers the request order (same origin, then input, then session), every outcome code, and a start response that does not contain the solution.
- `PuzzleCorpusLifecycleTest` covers a refresh that keeps an unfinished-attempt Puzzle and a classified Puzzle while removing an unreferenced one.

`node scripts/verify.mjs` remains the only complete definition of done.
