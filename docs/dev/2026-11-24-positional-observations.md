# Positional observations and the only move

Stage 8B Part 2 gives the coach card's "Why?" measured positional observations when no tactic explains a move. It also adds the "only move" signal. Both are computed each time a review opens, from the stored engine lines, so earlier analyses show them without reanalysis. Nothing is stored, and Own Puzzle labels are unaffected, so the tactic rules version stays at 2.

## Owner decisions (2026-11-24)

- Observations apply to inaccuracies as well as mistakes and blunders (research decision 6 in `docs/planning/09-game-review-explanation-research.md`).
- The "only move" signal is included in this part, with the proposed starting threshold of 20 expected-score points (research decision 5).

## When observations are computed

`GameReviewService.positional` computes them for a judged move only when all of these hold:

- it is labelled an inaccuracy, mistake, or blunder
- it has no tactic label
- it has no mate event
- it did not end the game

Otherwise `positional` is null.

## Matched positions

`gamereview.internal.review.PositionalObservations` compares two positions:

- the end of the engine's best line from the judged position
- the end of the played move followed by the opponent's stored reply line

Both are taken the same number of plies after the judged position. The depth is the first one from 4 to 8 plies at which both positions are settled: the last move was not a capture (en passant included), the side to move is not in check, and the line's next stored move is not a capture either. A half-finished or imminent trade therefore never shows as a material change. The research proposed about four to six plies; the range reaches 8 so that lines with a trade in progress can still settle. When the stored lines never reach such a depth, for example because one line is only two moves long, `positional` is null.

## Features

Each feature is measured for the side that played the judged move (`mover`) and for its opponent. Only differences that pass the threshold are reported.

| Feature | Measure | Reported when |
|---|---|---|
| `material` | Pawn 1, knight and bishop 3, rook 5, queen 9; the mover's balance only. | It differs. |
| `bishop_pair` | Two or more bishops. | Kept in one line only. |
| `king_shelter` | Own pawns on the king's file and the files beside it, one or two ranks in front of the king. | It differs by 1, and the king is on a wing (a to c or f to h files) in both positions. |
| `open_files_near_king` | Of the king's file and the files beside it, those with none of the side's own pawns. | As for `king_shelter`. |
| `castling` | 2 castled in the line, 1 may still castle, 0 cannot. | One line can no longer castle and the other has castled or still can. |
| `passed_pawns` | No enemy pawn ahead on the same or an adjacent file. | The count differs; `squares` names the passed pawns on files the other line has none on. |
| `isolated_pawns` | No own pawn on an adjacent file. | The count differs. |
| `doubled_pawns` | Pawns beyond the first on each file. | The count differs. |
| `development` | Knights and bishops still on their starting squares. | The count differs, and the judged move is in the opening phase. |
| `mobility` | Squares attacked by knights, bishops, rooks, and queens that are not occupied by their own pieces, counted geometrically. | It differs by at least 10. |

Two choices narrow the research table on purpose:

- **King cover only on a wing.** A king still in the centre loses "cover" with every normal central pawn move, which would make a meaningless observation in almost every opening.
- **Mobility counts attacked squares, not legal moves.** Legal moves exist only for the side to move, and a geometric count is the same for both sides.

## The only move

`GameReviewService.onlyMove` compares the first two stored engine lines from the judged position. When the second scores at least 20 expected-score points (400 units) less than the first, the response names that move and the gap. It applies to every labelled move, with or without tactics.

## Response and interface

- `JudgedMove.positional` is `{ plies, observations: [{ feature, side, best, played, squares }] }` or null.
- `JudgedMove.onlyMove` is `{ nextBestMove, nextBestSan, gap }` or null.
- The interface validates both and rejects a review with an unknown feature, a bad square, an out-of-range depth, or a malformed only move.
- The coach card's Why? wording:
  - Only move, after the engine's preference: "6… Bf5 was the only move that kept it there: the next best, 6… Nd7, scored 21 points less."
  - Observations: "Compared with the engine's line at the same point, 4 half-moves on, after 6… e6: your king has 2 pawns in front of it instead of 3; your opponent has 1 passed pawn instead of 0 (on d5). These are observations; the engine does not say they are the reason."
  - Nothing measured changed: "No tactic or measured feature explains the difference. The engine's line keeps a better position for reasons this review cannot name."
  - No comparison possible: "No forcing tactic was found, and the stored lines give no settled position a few moves on to compare." This replaces the earlier claim that the difference was positional.
- No sentence uses causal words. The training suggestions for moves without a tactic are unchanged and still by phase.

## Results on the owner's stored analyses

The rules were run read-only over the owner's 11 stored findings on 2026-11-24. The database was opened read-only, and the throwaway runner was deleted.

- The 5 findings with tactic labels keep them and get no observations.
- 7… c5 (ply 14, mistake, opening): at 7 plies, the owner has 2 undeveloped minor pieces instead of 1, the opponent has 1 instead of 0, and the opponent's pieces reach 20 squares instead of 30.
- 7. Ne5 and 42. Ke5: nothing measured changed, so the card says so.
- 5. Bd3 and 37. h3: a stored line is only two moves long, so nothing was compared.
- 17. Rdg1: within 8 plies the lines never reach a position without a capture just played or about to be played, so nothing was compared.
- Only move: 24. fxg6 (next best Qe3) and 37. h3 (next best a4), each about 49 points behind the best line.

## Tests

- `PositionalObservationsTest` (16): a proving position and a near miss for every feature, including:
  - a king in the centre whose central pawn moves are not reported
  - castling counted only when one line can no longer castle
  - development only in the opening
  - mobility only from a difference of 10
  - the mover's view when Black moved
  - a depth that moves past a pending recapture, and past a quiet move that a capture follows
  - no result when the lines never settle or are too short
  - castling by the played move itself
- `GameReviewServiceTest`: a quiet inaccuracy with nothing changed; no observations for unlabelled moves, moves with a tactic, mate events, or a final position; the only move at exactly 20 points but not at 19.95, and never for an unlabelled move or a single line.
- `GameReviewControllerTest`: `positional` and `onlyMove` in the JSON.
- `GameReview.test.tsx`: parsing and rejection of malformed observations and only moves.
- `coach.test.ts`: the observation, empty, and only-move wording word for word, Black's view, no observations when a tactic explains the move, and wording for every feature in both directions.
