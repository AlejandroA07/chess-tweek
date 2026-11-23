# Own Puzzle creation

Stage 8 Part 8a turns each owner inaccuracy, mistake, and blunder from a completed My Moves analysis into an Own Puzzle. Own Puzzles are stored separately from the Lichess Puzzle Library. This part is backend creation only. Training state (New, Discard, Restore, Training Lists) comes in 8b, and the interface comes in 8c.

## Owner decisions (2026-11-22)

- One Own Puzzle per owner inaccuracy, mistake, and blunder. They come only from My Moves analyses that complete after this ships. Earlier analyses are not backfilled, and Opponent Moves findings never create one.
- The solution is the engine's top line from the position before the owner's labelled move. It has three plies: the owner's move, the engine's reply played automatically, then the owner's move again. A top line shorter than three plies gives a one-move puzzle.
- A side with a stored analysis is never analyzed again. Only the missing side can still be analyzed.

## Delivered boundary

- `gamereview.AnalysisCompletionListener` is a new public interface.
  - `StockfishGameAnalysisService` calls every listener after it stores the versions and reports the job as completed.
  - A listener that throws is logged. It never changes the completed job, and the other listeners still run.
- `composition.ownpuzzles.OwnPuzzleCreation` implements the listener.
  - It ignores `OPPONENT_MOVES`. For `MY_MOVES` and `BOTH`, it reads the public `GameReviews.review(..., MY_MOVES)` and maps every move whose label is not `none` to a `training.OwnPuzzles.NewOwnPuzzle`.
  - The training and game-review modules stay independent, and `ArchitectureTest` is unchanged.
  - The package is not named `composition.training`, because the `..training..` rule would match it.
- `training.OwnPuzzles.create(accountId, puzzles)` returns how many puzzles were new.
  - An invalid account refuses the whole call.
  - A puzzle with an invalid field (game source and ID, ply 1 to 1200, label, phase, tactic theme and kind) is skipped on its own, so one bad move never costs the game its other puzzles. Reanalysis is blocked, so a whole-game refusal could never be recovered.
  - An unusable opening ECO or name is stored as unknown.
  - It replays every solution move and the opponent's previous move with the chess library. A line that cannot be played, or a previous move that does not lead to the puzzle's position, skips only that puzzle.
- `JdbcOwnPuzzleStore` inserts with `ON CONFLICT DO NOTHING` on `(account_id, game_source, game_id, source_ply)` in one transaction. Creating the same puzzles again stores nothing.
- Migration V14:
  - `training_own_puzzle` holds:
    - The source: game source, game ID, ply, and analysis version ID, copied as scoped values with no cross-module foreign key.
    - The position and the previous move.
    - The solution as one or three space-separated UCI moves, the same format as `training_puzzle.solution_uci`.
    - The label and phase.
    - The opening ECO and name.
    - The game's played-at and created-at times.
  - `training_own_puzzle_tactic` holds the proven tactic labels, deleted along with their puzzle.
  - Indexes cover newest source game first and the phase.
- Starting an analysis of a side that already has a stored version returns `ALREADY_ANALYZED`, which the analysis endpoint maps to `409 already_analyzed`.
  - The check runs under the job lock, so an analysis that has just finished is seen.
  - A failed or canceled analysis stores nothing, so it can be started again.
- The Games card offers only the missing sides:
  - Neither side analyzed: Analyze My Moves, plus More with Opponent Moves and Both Sides.
  - One side analyzed: only the other side.
  - Both analyzed: no action.
  - The "reanalyze" wording is gone from the Games card and the Game Review.

## Verification contract

- `OwnPuzzleMigrationTest` covers uniqueness per account, game, and ply, plus the CHECK constraints and the tactic cascade.
- `OwnPuzzleServiceTest` uses a real context and a temporary database. It covers:
  - Stored fields, and the three-move solution taken from a longer line.
  - One-move puzzles from one- and two-ply lines.
  - The game's first move, which has no previous move.
  - Idempotent creation.
  - Skipping unplayable lines and previous moves.
  - Refusal of an invalid account.
  - Skipping only the invalid puzzles.
  - An unusable opening stored as unknown.
  - Account scoping, and no rows in the Lichess Puzzle Library.
- `OwnPuzzleCreationTest` uses a real context with a hand-built review. It covers:
  - One puzzle per labelled owner move with the right position, previous move, solution, label, phase, opening, and tactics.
  - Idempotent repeats.
  - `OPPONENT_MOVES` ignored, and `BOTH` reading only My Moves.
  - No owner analysis creating nothing.
  - Registration as the application's listener.
- `StockfishGameAnalysisServiceTest` covers:
  - `ALREADY_ANALYZED` for a repeated side and for Both when one side exists.
  - The missing side still starting.
  - Another account's analysis not counting.
  - Retrying after a failure.
  - Listeners hearing only about completed analyses.
  - A failing listener leaving the job completed.
- `GameAnalysisControllerTest` covers `409 already_analyzed`.
- `GameAnalysis.test.tsx` covers:
  - Only the missing side offered after analysis, with no Reanalyze.
  - Only My Moves when the opponent side exists.
  - No actions when both sides exist.
  - The `already_analyzed` message.

`node scripts/verify.mjs` remains the only complete definition of done.
