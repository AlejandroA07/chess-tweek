# More named tactics

Stage 8B Part 1 adds seven named motifs to the Game Review tactic labels, in the order the plan gives: discovered checks and attacks, pins, skewers, removing the defender, trapped pieces, deflection, and in-between moves. The conservative rule from Stage 4 still holds: a label is given only when the stored engine line proves the gain. The rules were written for this project from the descriptions in `docs/planning/09-game-review-explanation-research.md`; no tagger code was copied.

## Owner decision (2026-11-23)

Own Puzzles store their tactic labels when they are created. On every application start, `composition.ownpuzzles.OwnPuzzleTacticRefresh` recomputes each Own Puzzle's labels from the stored analysis it came from, and replaces them when they differ. Nothing is reanalyzed, and attempts, Training Lists, and sessions are untouched. A puzzle whose game or analysis version is no longer reviewable keeps its labels. A failure on one game is logged without details and skips only that game, and no failure stops the application starting.

## The proof

`gamereview.internal.review.LineMotifs` reads one line at a time: the opponent's reply line after the played move (`allowed`) or the best line before it (`missed`). `ReplayedLine` replays up to 20 plies of it once. The side that moves first is the gainer, so its moves are the even ones.

Every motif needs two things:

- The pattern must be on the board within the first 10 plies.
- A gainer capture must follow that leaves the gainer at least 2 points ahead. Material is counted after the opponent's reply to that capture (pawn 1, knight and bishop 3, rook 5, queen 9). The baseline is the higher of two positions: the position where the pattern started, and the position before the opponent's move into the line. A recapture of material just lost therefore never counts as a gain.

| Theme | Pattern | Gain |
|---|---|---|
| `discovered_check` | A gainer move (not castling) uncovers a check by another gainer slider. | That move or the next gainer move captures. |
| `discovered_attack` | A gainer move uncovers a slider's attack on a piece worth at least a knight that is worth more than the slider or undefended. | Within the next two gainer moves, a capture on that square or by the piece that moved. |
| `pin` | A gainer slider, an enemy piece worth at least a knight, and behind it the enemy king or a more valuable piece stand on one line. | The pinned piece is captured on its square while all three still stand there. |
| `skewer` | A gainer slider moves to attack the enemy king, or a piece worth more than the piece behind it, which is worth at least a knight. | The front piece steps off, and the slider takes the piece behind at once. |
| `removing_defender` | The gainer captures the only defender of an enemy piece worth at least a knight. | The gainer's next move takes that piece. |
| `trapped_piece` | After a gainer move, with the opponent not in check, an enemy knight, bishop, rook, or queen is attacked and either undefended or attacked by something cheaper. It is not pinned to its king, and every legal move of it ends the same way unless it takes something at least as valuable. | The piece is captured later, wherever it went. |
| `deflection` | A gainer check, or a capture that is recaptured, makes the replying piece abandon the only defence of an enemy piece worth at least a knight. | The gainer takes that piece at once. |
| `in_between_move` | Right after the opponent captures, the gainer could recapture but first gives check or takes a piece worth at least a knight. | The gainer recaptures later and ends at least 2 points better than the direct recapture could have at best. |

The in-between move needs the opponent's capture into the line. For `allowed`, that is the played move. For `missed`, `GameReviewService` passes the game's previous move; it is used only when it really leads to the judged position.

Each motif reports its earliest occurrence. Several labels can apply to one move; `general_tactics` still appears only when no narrower label does. Overloading, interference, clearance, attraction, and x-rays are not labelled.

## Response and interface

- Each tactic now has `lineIndex`: the zero-based index, in the proving line, of the move after which the pattern stands. It is null for the mate labels and `general_tactics`. Forks get it too.
- `piece` and `square` name the motif's key piece, and `targets` lists the squares it acts on:
  - the uncovering slider and the attacked squares
  - the pinning slider, the pinned square, and the square behind it
  - the skewering slider, the front square, and the square behind it
  - the captured defender and the piece it defended
  - the trapped piece
  - the deflected piece and the square it guarded
  - the in-between move's piece, its destination, and the recapture square
- The coach card's Why? names the move from the line, then the pieces involved, read from the position right after that move. For example: "The best line has 16. Re1: your rook on e1 pins the knight on e7 to the king on e8, and the line wins the pinned piece."
- What should I train? names the matching pattern theme from the imported Puzzle corpus: `discoveredCheck`, `discoveredAttack`, `pin`, `skewer`, `capturingDefender`, `trappedPiece`, `deflection`, and `intermezzo`. It adds a habit where one helps.
- The Own Puzzles summaries name the new groups ("Pin", "Removing the defender", and so on).

## Results on the owner's stored analyses

The detector was run read-only over all 11 labelled moves stored on 2026-11-23. The new rules add no label and change none: the forks, the missed mate, and the two `general_tactics` findings stay as they were. Neither `general_tactics` line contains a clean named motif. The new labels will show up on later games, and the startup refresh applies them to existing Own Puzzles whenever a rule changes.

## Tests

- `TacticDetectorTest` (23): every motif has a proving position and a near miss:
  - a discovered check that wins nothing
  - an uncovered queen that is never taken
  - a pinned knight taken by the rook and recaptured
  - a king that steps beside its rook
  - a second defender, for both removing the defender and deflection
  - a bishop with an escape square
  - an in-between move that only trades back to the same material
  - The missed in-between move is checked with the previous move, without it, and with one that does not lead to the position.
  - A piece pinned to its king is never trapped.
  - The owner's two forks now carry their line index.
- `GameReviewServiceTest`: the previous move passed for each ply.
- `GameReviewControllerTest`: `lineIndex` in the JSON.
- `OwnPuzzleServiceTest`: listing labels, replacing one puzzle's labels without touching another account, no change when equal, and refused invalid sources and labels.
- `OwnPuzzleCreationTest`: the refresh replaces outdated labels, can remove all of them, keeps the puzzle rows, leaves current labels alone, keeps labels when the analysis version differs or is missing, skips a game whose review fails, never stops the application starting, and runs as a startup bean.
- `coach.test.ts`: the pin and in-between wording word for word, and a label, an explanation, and a training item for every theme and kind.
