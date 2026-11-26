# Fast ladder and jumping inside a package

Stage 9 Part 2 adds two owner requests made after trying Part 1 live on 2026-11-26 (`docs/planning/03-delivery-plan.md`, Stage 9).

## Jump inside a package

- A package card with more than one puzzle offers "Go to puzzle (1–N)" with Go, plus "Last 20" and "Last 10", each shown only when the package has more puzzles than that.
- Positions count every quality-gated puzzle of the package in rating order, including Mastered ones, so they match the package size on the card. Mastered puzzles are still skipped when served.
- A jump to position P stores the puzzle at P − 1 as the place, with "presented" set to P − 1, so the player's "Puzzle P in …" fact matches. Position 1 forgets the place, like Restart.
- The store finds position P − 1 with an `OFFSET` on the ladder index alone, which takes a few milliseconds even in the largest package.
- `POST /api/training/puzzles/ladders/{pattern}/{package}/jump` with `{"position": P}` returns `204`. A position outside 1..size returns `400 invalid_training_request`, and a key that is not a ladder pattern returns `404 pattern_not_found`.

## Fast ladder

Each start draws a new run for one pattern and replaces the account's previous run. A run is never resumed.

1. **Rating steps:** one random puzzle from each step: below 800, 800–999, 1000–1199, 1200–1399, 1400–1599, 1600–1799, 1800–1999, 2000–2199. A step with no eligible puzzle is left out.
2. **Combination steps:** for each other pattern Theme (the other 43), one random puzzle of this pattern whose Themes also include it. The search starts at 2000–2199. For combinations still missing, it widens in 200-point ranges, alternately below and above, until every rating is covered. A combination that never occurs is left out. These steps come in random order.

- Mastered puzzles and puzzles no longer current are skipped. A puzzle appears at most once in a run.
- The rating pick draws a uniform offset over the stored band counts and finds a start on the ladder index alone. The first eligible puzzle at or after that start wins, wrapping to the start of the range.
- The combination pick reads each candidate's Themes in one pass per range and keeps one puzzle per wanted pattern by reservoir sampling.
- Measured on a copy of the owner's library:
  - A rating pick in Fork's largest step took about 6 ms.
  - The 2000–2199 combination pass took 45 ms for Fork and 0.2 s for Kingside attack on first read.
  - When some combinations never occur, the search reads the whole pattern. That took about 0.7 s for Fork (456,127 puzzles) and 0.8 s for Kingside attack (269,238) on first read. Small patterns take milliseconds.
- Migration V18 stores the run (`training_puzzle_fast_ladder`) and its items in order. Each item names its rating range or the other pattern it is combined with. Corpus retention keeps any puzzle a run references.
- Endpoints:
  - `POST /api/training/puzzles/ladders/{pattern}/fast` returns `201 {fastLadderId, total}`, or `404 no_puzzle_available`.
  - `POST /api/training/puzzles/ladders/fast/{id}/attempts` returns the attempt (without its solution), its position, the total, and the step. It returns `409 ladder_complete` at the end, and `404 session_not_found` for a run that is not the account's current one.
- The player reuses the Practice Session flow, with a "Fast ladder" position fact and a "Step" fact. At the end it stops and offers "Back to" the pattern; it never draws a new set by itself.

## Tests

- `PatternLadderTest`:
  - Jump: to a position, back to 1, outside the range, in an empty package, for another account, and for a key that is not a pattern.
  - Fast ladder: the rating steps in order and the combination steps.
  - Fast ladder: a gated-out puzzle is never used, Mastered puzzles are skipped, and a new run replaces the old one.
  - Fast ladder: a run belongs to one account, and a pattern without puzzles has none.
  - A puzzle referenced by a run is retained by a later import.
- `PatternLadderControllerTest`: jump and fast ladder responses and error codes, input rejected before authorization, and cross-origin writes refused.
- `PatternLadders.test.tsx`: the fast route, jump by number and by Last 20 or Last 10 (each shown only when useful), the fast ladder card leading the page, and a fast ladder run through a rating step and a combination step to its end.
- `DatabaseFoundationTest` now counts 18 migrations.
