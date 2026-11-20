# Puzzle dashboard

Stage 7 Part 3b adds the Puzzles interface over the Part 3a backend: the Puzzle dashboard, Random, and By Category. Repetition (Part 3c) and Own Puzzles (Stage 8) appear on the dashboard as **Planned** modules. The visual rules are the "Puzzle training view" section of `docs/app-material/DESIGN.md`.

## Delivered boundary

- `#/puzzles` is the dashboard, `#/puzzles/random` is the Random player, and `#/puzzles/category/{theme|opening-family|opening-variation}/{key}` is the By Category player. `puzzleRoute` accepts only these hashes. Every other `#/puzzles…` hash falls back to Dashboard, as before.
- `GET /api/training/puzzles/categories` requires the connected owner session and returns every Theme and opening category that has at least one Puzzle, each with its display name, catalog group, and parent family. `PuzzleLibrary.categories()` probes the membership primary key once per category and never counts members.
- Themes are grouped by their Lichess catalog group. About 1,600 openings are found through a labeled search, which needs at least two letters and shows at most 20 matches plus the total.
- `puzzles.ts` parses every response strictly, including a Lichess-only source-game URL, and maps safe error codes. An unexpected response shape stops the start rather than rendering partial data.
- `InteractiveChessBoard` gains three optional props, `line`, `initialLastMove`, and `hintSquare`. Existing boards that do not pass them behave as before.

## Player behaviour

- Start puzzle creates the attempt, and the board becomes interactive as soon as it returns. This implements the owner's 2026-11-20 decision that Solve Time starts when the server creates the attempt.
- A Wrong Move reverts the board, leaves the position unchanged, and updates the Wrong Move count. A correct move shows the opponent's reply. The final move, or any mating move, completes the puzzle.
- Hint marks the square of the piece to move and disables itself. Show solution plays the remaining line on the board. Both state that they place the puzzle in Hard to Solve.
- Completion says where the puzzle went ("Placed in…", "Stays in…", or "Moved from … to …"). It also shows Solve Time against the limit (or "Not counted" after Show solution), Wrong Moves, hint use, progress within the list for repetitions, themes, and a link to the source game. Next puzzle starts another attempt from the same pool.
- An exhausted pool explains itself. Random says every stored puzzle is already in a Training List or the library is empty, and By Category says every puzzle in that category is Mastered. An attempt that ended elsewhere, for example because another puzzle was started, returns to Start puzzle with an explanation.
- Focus follows the work. Starting moves focus to the side-to-move heading, completion moves it to the result, and an ended attempt moves it back to Start puzzle. After Show solution, Solve Time is still shown, marked "solution shown". Times round down, so a solve under the limit never displays as the limit.

## Verification contract

- `PuzzleTraining.test.tsx` covers route parsing, the disconnected state, the dashboard modules, grouped themes, opening search with a long name, category-load failure, a full Random solve with a Wrong Move and an opponent reply, hint highlighting and Show solution from a repetition list, an exhausted pool, an ended attempt, and rejection of an unexpected response shape.
- `ChessBoard.test.tsx` covers replaying a line from a setup move and the hint square.
- `PuzzleTrainingControllerTest` and `PuzzleLibraryPersistenceTest` cover the category endpoint, its session requirement, and the rule that excludes empty categories.

`node scripts/verify.mjs` remains the only complete definition of done.
