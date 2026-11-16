# Tactic labels and coach cards

Stage 4 Part 6 adds conservative tactic labels to every judged inaccuracy, mistake, and blunder, and a deterministic coach card for the mistake shown on the board. The owner dropped the planned "what I was thinking" note on 2026-11-15.

## Tactic labels

`TacticDetector` (`gamereview.internal.review`) labels a move only when the stored engine line proves the gain. Labels are computed when the review is read, from the immutable stored evidence (position before, played move, label, mate event, best line, and the opponent's reply line), so earlier analyses get them without reanalysis. Each label is `allowed` (from the opponent's reply line after the played move) or `missed` (from the best line before it).

| Theme | Proof required |
|---|---|
| `allowed_mate`, `missed_mate` | The stored mate event. |
| `back_rank` | That mate line replays to checkmate, delivered by a rook or queen on the mated king's own back rank with the king on that rank. |
| `hanging_piece` | The line's first move for the gaining side captures a knight, bishop, rook, or queen that no enemy piece attacked (defended). |
| `fork` | A gaining-side move in the first ten plies lands a piece that attacks two or more enemy pieces worth at least a knight (or the king), each the king, worth more than the attacker, or undefended; and the gaining side captures on one of those squares within the next eight plies. |
| `general_tactics` | A mistake or blunder with none of the above whose line still changes material by at least two points within six plies, measured after the losing side's reply. |

Pins, skewers, discoveries, deflections, and other motifs are not labeled. Attacks are counted geometrically (pins ignored), which can only make a piece look defended, never hanging. Inaccuracies get only the narrow labels, never `general_tactics`. An unreadable stored position yields no label.

The fork window was widened after checking the owner's real game `ye7Uy9js`: 8...Nd7 allowed 9.dxe6 fxe6 10.Ng5 Nf6 11.Bg6+ hxg6 12.Qxd8+ Kxd8 13.Nf7+, forking the king and the rook on h8, which falls only at 16.Nxh8 in the stored line.

Results on the owner's stored findings:

| Move | Label |
|---|---|
| 6...e6 (mistake) | Missed a fork: 8...Qa5+ attacks the king on e1 and the bishop on f5. |
| 7...c5 (mistake) | None: positional (queen trade into a worse structure). |
| 8...Nd7 (mistake) | Allowed a fork: 13.Nf7+ on the king and the rook. |
| Bd3 (inaccuracy) | None. |
| fxe3 (blunder) | Missed mate. |

The labels are returned as `tactics` on every judged move: `theme`, `kind`, and, when known, `piece`, `square`, and the forked `targets`.

## Coach cards

`frontend/src/coach.ts` builds three fixed answers from the stored data only; there is no model or free text.

- **Why?** The expected-score drop, the engine's preferred move, then one sentence per label (for example, which forked pieces the queen attacks, read from the position after the forking move). Without a label it says no forcing tactic was found and the difference is positional.
- **What was the threat?** The opponent's best reply after the played move and whether it is a capture, check, or quiet move, then the reply line and the best line. When every label is a missed chance, it says the problem was the missed chance rather than a threat.
- **What should I train?** Pattern-library themes (`fork`, `hangingPiece`, `mateIn2`, `backRankMate`) and habits per label, or a phase-based suggestion for positional mistakes.

The card appears under the board when View Mistake shows a finding (second person for My Moves, colors for Opponent Moves) and hides while an engine line is shown. Finding cards show the labels as chips. The reply-line button was renamed from "Why It Was a …" to **Show Their Best Reply** so it is not confused with the coach's Why?.

## Tests

- `TacticDetectorTest` (12): the owner's missed queen fork and allowed knight fork, hanging and defended pieces, a missed free piece, an unproven fork, missed and allowed back-rank mates, a mate not on the back rank, the general fallback, no labels for good moves, inaccuracies without patterns, and unreadable data, and sliding attacks stopping at blockers.
- `GameReviewControllerTest`: tactics in the JSON response.
- `coach.test.ts` (5): the owner's Caro-Kann card word for word, opponent wording by color, positional mistakes and phase training, a hung piece, and label names.
- `GameReview.test.tsx`: label chips and the three coach answers on the review page.

Driven in a browser with mocked API responses. The owner's live check is recorded in `docs/planning/03-delivery-plan.md`.
