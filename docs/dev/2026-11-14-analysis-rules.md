# Analysis rules

Stage 4 Part 3 implements the fixed rules that turn Stockfish searches into move verdicts. The rules run against a search interface and are tested with a fake engine; no real game analysis runs yet. Part 4 connects them to Stockfish and stores the results.

The code is in `gamereview.internal.analysis`.

## Replaying a game

`ReplayedGame` replays the stored SAN moves from the standard start with the existing chess library and records every position the game passed through:

- position `i` is the board after `i` plies, and move `i` (counting from 1) is played from position `i - 1`
- each position keeps its FEN, side to move, the UCI moves from the start (so the engine can see repetitions and the fifty-move count), queen count, non-pawn material, and whether it is checkmate or stalemate
- illegal moves and games over 1,200 plies are rejected

## Expected score

Stockfish reports win/draw/loss in permille for the side to move. Expected score is win plus half the draw. It is held as an exact integer from 0 to 2,000 (20 units per percentage point) so half a permille is never rounded.

For a played move:

- **Before:** the best line of the search of the position the move was played from, with the mover to move.
- **After:** the best line of the search of the resulting position, with the opponent to move, flipped to the mover's side. A move that delivers checkmate counts as a certain win, and stalemate counts as a draw. Neither position is sent to the engine.
- **Loss:** before minus after, clamped at zero.

Playing the engine's best move is never a loss. The two searches can disagree slightly about the same line, and that noise must not create a penalty. This rule is not stated in the plan; it applies the plan's intent that engine noise must not create a fictitious penalty.

## Labels

| Label | Expected-score loss |
|---|---|
| Inaccuracy | 8 to under 18 percentage points |
| Mistake | 18 to under 35 percentage points |
| Blunder | 35 percentage points or more |

The plan's "8 through 17" and "18 through 34" are read as these half-open ranges, so a loss such as 17.5 points has exactly one label.

## Forced mates

Each position has a forced-mate state for the mover: mating, being mated, or neither, taken from Stockfish's mate score.

- **Missed mate:** the mover had a forced mate before the move and does not after it.
- **Allowed mate:** the mover was not already being mated, and after the move is being mated.

Either one makes the move a blunder whatever the expected-score loss. Keeping a mate with a longer distance is not a miss. A player who is already being mated cannot allow mate again.

## Second pass

The quick pass searches every position around the requested side's moves at the profile's quick node budget. A position shared by two moves (when both sides are analyzed) is searched once.

A move is searched again when it lost at least 5 percentage points or its forced-mate state changed, unless it was the engine's best move. The deep pass searches both of its positions at the deep budget, and the move's verdict then comes only from the deep searches. Each finding records which pass produced it.

Any failed search fails the whole analysis, so no partial result can be mistaken for a complete one.

## Game phases

A move belongs to the phase of the position it was played from. Phases run in order (opening, middlegame, endgame) and never go back.

- **Supplied:** Lichess's division is used when stored. A division with only an endgame start runs the opening straight into the endgame. A division with only a middlegame start never reaches the endgame.
- **Inferred:** without a division, plies 1 through 20 are the opening. The endgame starts at the first later position with no queens and at most 20 points of combined non-pawn material (knight and bishop 3, rook 5, queen 9). Everything between is middlegame.

The result records whether the phases were supplied or inferred. An empty division cannot be told apart from a missing one in storage, so it is treated as missing.

## Tests

`MoveRulesTest`, `ReplayedGameTest`, `GamePhasesTest`, and `TwoPassAnalysisTest` cover:

- every label boundary
- clamping, and best-move noise
- missed, allowed, kept, and already-lost mates
- checkmate and stalemate after the move
- the 5-point and mate-change triggers
- deep-pass searches and verdicts
- shared positions and per-side filtering
- search failure
- supplied, partial, empty, and inferred phases

## Security review

| Check | Verdict |
|---|---|
| Untrusted input | Pass: game moves are replayed through the chess library and rejected when illegal; replay length is bounded. |
| Untrusted engine output | Pass: evaluations reject WDL that does not sum to 1,000, out-of-range scores, more than three lines, malformed UCI moves, and variations over 64 moves. Part 4's parser must produce these types. |
| Resource limits | Pass: each position is searched at most once per budget, and the deep pass touches only flagged moves. |
| New endpoints, storage, or process execution | None in this part. |

## Next

Part 4 runs single-game analysis:

- parse Stockfish's search output into these evaluations
- implement the position search over the existing engine access
- run `TwoPassAnalysis` for My moves, Opponent moves, or Both
- store an immutable analysis version linked to its engine profile
