# More named tactics

Stage 8B Part 1 adds seven named motifs to the Game Review tactic labels, in the order the plan gives: discovered checks and attacks, pins, skewers, removing the defender, trapped pieces, deflection, and in-between moves. The conservative rule from Stage 4 still holds: a label is given only when the stored engine line proves the gain. The rules were written for this project from the descriptions in `docs/planning/09-game-review-explanation-research.md`; no tagger code was copied.

## Owner decision (2026-11-23)

Own Puzzles store their tactic labels when they are created. At application start (since 2026-11-24, only once after each tactic-rules change; see `2026-11-24-refresh-on-rules-change.md`), `composition.ownpuzzles.OwnPuzzleTacticRefresh` recomputes each Own Puzzle's labels from the stored analysis it came from, and replaces them when they differ. Nothing is reanalyzed, and attempts, Training Lists, and sessions are untouched. A puzzle whose game or analysis version is no longer reviewable keeps its labels. A failure on one game is logged without details and skips only that game, and no failure stops the application starting.

## The proof

`gamereview.internal.review.LineMotifs` reads one line at a time: the opponent's reply line after the played move (`allowed`) or the best line before it (`missed`). `ReplayedLine` replays up to 20 plies of it once. The side that moves first is the gainer, so its moves are the even ones.

Every motif needs two things:

- The pattern must be on the board within the first 10 plies.
- A gainer capture must follow that leaves the gainer at least 2 points ahead. Material is counted after the opponent's reply to that capture (pawn 1, knight and bishop 3, rook 5, queen 9). The baseline is the higher of two positions: the position where the pattern started, and the position before the opponent's move into the line. A recapture of material just lost therefore never counts as a gain.

| Theme | Pattern | Gain |
|---|---|---|
| `discovered_check` | A gainer move (not castling) uncovers a check by another gainer slider. | That move or the next gainer move captures. |
| `discovered_attack` | A gainer move uncovers a slider's attack on a piece worth at least a knight that is worth more than the slider or undefended. | Within the next two gainer moves, a capture on that square or by the piece that moved. |
| `pin` | A gainer slider, an enemy piece worth at least a knight, and behind it the enemy king or a more valuable piece stand on one line. | While all three still stand there, the gainer captures the pinned piece, or captures something the pinned piece guards, so that taking back would break the pin (rules version 3). |
| `skewer` | A gainer slider moves to attack the enemy king, or a piece worth more than the piece behind it, which is worth at least a knight. | The front piece steps off, and the slider takes the piece behind at once. |
| `removing_defender` | The gainer captures the only defender of an enemy piece worth at least a knight. | Within the next two gainer moves, the gainer takes that piece while it is still undefended (rules version 3; at first only the next move counted). |
| `trapped_piece` | After a gainer move, with the opponent not in check, an enemy knight, bishop, rook, or queen is attacked and either undefended or attacked by something cheaper. It is not pinned to its king, and every legal move of it ends the same way unless it takes something at least as valuable. | The piece is captured later, wherever it went. |
| `deflection` | A gainer check, or a capture of something worth at least the defender that the defender recaptures, makes the replying piece abandon the only defence of an enemy piece worth at least a knight. | Within the next two gainer moves, the gainer takes that piece while it is still undefended (rules version 3; at first only the next move counted). |
| `in_between_move` | Right after the opponent captures, the gainer could recapture but first gives check or takes a piece worth at least a knight. | The gainer recaptures later and ends at least 2 points better than the direct recapture could have at best. |

The in-between move needs the opponent's capture into the line. For `allowed`, that is the played move. For `missed`, `GameReviewService` passes the game's previous move; it is used only when it really leads to the judged position.

On 2026-11-24 the owner pointed out that two first-version rules were too narrow. A pin often wins a different piece, the one the pinned piece can no longer protect, and a defender's loss is often cashed in a move later. Rules version 3 (`GameReviews.TACTIC_RULES_VERSION`) adds both cases, each with a proving test and a near miss. The startup refresh applies them to existing Own Puzzles once (`2026-11-24-refresh-on-rules-change.md`).

Two narrowings remain, on purpose:

- Removing the defender and deflection do not count a mate on the abandoned square, because mates have their own labels.
- A gain that is only positional, such as a weaker king or a passed pawn, is never a tactic label, because the line does not prove it. Stage 8B Part 2 reports such changes as observations.

Pins that were already on the board count too, when the line then wins material through them.

A discovered check is proven by any capture with the same move or the next gainer move, because the check gives the opponent no time to answer elsewhere. A discovered attack is proven by a capture on an uncovered target, or by the piece that moved.

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

- `TacticDetectorTest` (28): every motif has a proving position and a near miss:
  - a discovered check that wins nothing
  - an uncovered queen that is never taken
  - a pinned knight taken by the rook and recaptured
  - a king that steps beside its rook
  - a second defender, for both removing the defender and deflection, with the line stopped before it recaptures, so that only the defender rule keeps the label away
  - a recaptured pawn capture, which never deflects a rook
  - a bishop with an escape square
  - an in-between move that only trades back to the same material
  - The missed in-between move is checked with the previous move, without it, and with one that does not lead to the position.
  - A piece pinned to its king is never trapped.
  - An allowed skewer from the reply line.
  - Rules version 3:
    - A pin that wins the bishop the pinned knight guarded. The near miss has no pin.
    - Removing the defender won two moves later. The near miss wins it three moves later.
    - A deflection won two moves later. The near miss has the king come back to defend first.
  - An allowed pin whose apparent gain is only the recapture of the played capture, which the baseline rules out.
  - The owner's two forks now carry their line index.
- `GameReviewServiceTest`: the previous move passed for each ply.
- `GameReviewControllerTest`: `lineIndex` in the JSON.
- `OwnPuzzleServiceTest`: listing labels, replacing one puzzle's labels without touching another account, no change when equal, and refused invalid sources and labels.
- `OwnPuzzleCreationTest`: the refresh replaces outdated labels, can remove all of them, keeps the puzzle rows, leaves current labels alone, keeps labels when the analysis version differs or is missing, skips a game whose review fails, never stops the application starting, and runs as a startup bean.
- `coach.test.ts`: the pin and in-between wording word for word, and a label, an explanation, and a training item for every theme and kind.

## Review

The branch had a standards review, a spec review, and a security review. The security review found nothing. The fixes from the other two are in this branch:

- The deflection value rule.
- Near misses that isolate the defender rules. Each one was checked by switching its rule off and watching the test fail.
- Allowed-path tests.
- A check that the defended piece is still the one taken.
- Imports in place of inline qualified names.
- Exhaustive Own Puzzle group names.

Kept as they are:

- The refresh skips a puzzle whose analysis version differs. This cannot happen, because a completed side is never analyzed again (`2026-11-23-own-puzzle-creation.md`).
- The refresh runs before the application serves requests. It reads only the games that have Own Puzzles.
