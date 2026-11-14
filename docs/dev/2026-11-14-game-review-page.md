# Game review page

Stage 4 Part 5b adds the game review page on top of the Part 5a endpoint (`2026-11-14-game-review-data.md`). Tactic labels and coach cards (Why?, What was the threat?, What should I train?) and the owner's "what I was thinking" note are later parts.

## Opening a review

- Every Games card has an **Open Game** link to `#/games/{gameId}`.
- The route is accepted only when the id matches the server's game-id pattern (8–32 letters or digits); any other `#/games/...` hash falls back to the normal route table and sends no request.
- The page needs a connected Lichess session. Games stays selected in the navigation and the top bar reads Game Review. The workspace is wider than other routes (up to 92rem) because the page has a board and an analysis panel side by side.

## What the page shows

| Area | Contents |
|---|---|
| Header | Back to Games, result, both players with ratings (long names wrap), date, clock and speed, rated or casual, ECO and opening. |
| Board | A board drawn with project styles and system chess glyphs; no dependency. It is oriented to the analyzed side (or the owner's color when nothing is analyzed), with a visible orientation note and Flip Board. The last move's from and to squares are marked. First, Previous, Next, and Last buttons; the focusable board panel also takes Left, Right, Home, and End. The board's accessible name describes the position, for example "Position after 3… Nf6. Black at the bottom." |
| Evaluation bar | A vertical bar beside the board, like chess.com's, filled with White's share of the expected score at the shown position and labeled with the engine score (`+0.5`, `#1`) at the favored side's end, or the result (`1-0`, `0-1`, `½-½`) when the game ended on the board. Its bottom is the side at the bottom of the board, so it flips with the board. A position without its own evaluation uses the nearest earlier one (the first one at the start). It animates only when reduced motion is not requested, and is hidden when the side is not analyzed. |
| Evaluation graph | White's expected score for every evaluated position, with the 50% line, dashed phase boundaries, and a cursor at the shown position. Positions without an evaluation are skipped, as the endpoint documents. The caption gives the percentage and engine score at the cursor. |
| Moves | Numbered move pairs; each move is a button that jumps the board. Judged errors carry `?!`, `?`, or `??` and their severity in the accessible name. A row is labeled where the middlegame or endgame starts. |
| Analysis | My Moves and Opponent Moves tabs. An unanalyzed side's tab is disabled and an Analyze button for it appears next to the tabs (plus Analyze Both Sides when nothing is analyzed). Analysis runs through the existing analysis controls and progress panel, and the review reloads when it completes. |
| Statistics | Engine, analyzed color and date, an earlier-profile note when relevant, moves judged, inaccuracy/mistake/blunder counts, average expected-score loss, average centipawn loss, and a per-phase table with the phase source. |
| Findings | Every inaccuracy, mistake, and blunder in game order: severity, move, phase, played and best move, loss, expected score before and after, engine score from White's view before and after, and a missed or allowed mate note. **View Position** shows the position before the move with one best-move arrow and "Show the played move". **Show Engine Line** is a collapsible, numbered engine line plus the opponent's reply line after the played move. |

States: loading, game not stored (no retry), session unavailable (retry), review unavailable or malformed (retry), lost session (the app returns to its connection prompt), not analyzed, no findings, and catalog-only games (no moves to replay).

## Untrusted data

The review response is validated in full before anything is shown (`frontend/src/reviewData.ts`): FEN and UCI shapes, ply numbering, score and WDL ranges, line lengths, exactly one of centipawns or mate per line, per-phase statistics, and that the selected version matches the side's newest version. Any mismatch shows "This review could not be loaded" instead of a partial page. All text is rendered as React text, never as HTML. The board refuses a FEN whose placement does not parse.

## Design

`docs/app-material/DESIGN.md` gained board and evaluation tokens (light and dark squares, marked squares, arrow, and the two evaluation fills), implemented in `frontend/src/styles/tokens.css`. Layout: board, graph, and moves on the left and analysis on the right at 1024px and wider; one column below that. Checked in a browser at 1280, 1440, 375, and 320px with no page-level horizontal scrolling.

## Tests

`frontend/src/GameReview.test.tsx` (16 tests) covers opening from Games, the evaluation bar (fill, score placement, game result, flipping, hidden when unanalyzed), header, statistics and phase table, findings, View Position with the arrow and engine line, button, move-list, and keyboard navigation, board flip and orientation, disabled unanalyzed tab and starting its analysis, switching sides, unanalyzed and catalog-only games, missing game, Retry after an unavailable review, a malformed review, a lost session, invalid review links, move and line numbering, and parser rejection of out-of-range or inconsistent evidence.

## Manual check

The page was driven in a real browser against the Vite dev server with mocked API responses (the Lichess session cookie is not available to the agent's browser). The owner's live check against real analyzed games is still needed.
