# Game review data

Stage 4 Part 5 is split in two. Part 5a, recorded here, adds the read model and endpoint that a game review needs. Part 5b adds the review page: board, move list, tabs, and findings.

## Endpoint

`GET /api/games/{gameId}/review?side=my_moves|opponent_moves` requires the Lichess session.

- The game id must be 8–32 letters or digits, and `side` is allowlisted. Both are checked before authorization.
- Without `side`, the owner's moves are chosen when analyzed, otherwise the opponent's, otherwise none.
- Responses are `no-store`. Failures are `400 invalid_review_request`, `401 not_connected`, `404 game_not_found`, `503 session_unavailable`, and `503 review_unavailable`.

The response contains:

| Field | Contents |
|---|---|
| `game` | The same card header as the Games library, with per-side analysis summaries. |
| `initialFen`, `plies` | The start position and every move: ply, color, SAN, UCI, and the position after it. These are replayed from the stored game, so they are empty for catalog-only variants. |
| `phases` | Source (`supplied` or `inferred`) and position-index boundaries. They come from the selected version when there is one, otherwise from the game. |
| `myMoves`, `opponentMoves` | The newest version of each side, or `null`: id, completion time, whether it used the newest profile, and counts. |
| `selected` | The chosen side's newest version, or `null`. See below. |

`selected` contains:

- the version, analyzed color, engine, node budgets, completion time, and current-profile flag
- statistics:
  - judged moves and Inaccuracy, Mistake, and Blunder counts
  - average expected-score loss in percentage points
  - average centipawn loss: each move's drop from Stockfish's best line before to the best line after, capped at 1,000 centipawns, with a mate counted as 1,000 and the engine's best move counted as 0
  - the same counts, average loss, and average centipawn loss per opening, middlegame, and endgame
- evaluations: White's expected score (0–2000) and engine score for the positions before and after each of the selected side's moves. Usually that is almost every position, but the start position is missing when only Black was analyzed, and the final position is missing when the other side made the last move. The graph should draw only the positions it has. A position where the game ended on the board has an expected score but no engine score.
- every judged move:
  - SAN, UCI, and pre-move FEN
  - phase, the pass that decided it, label, mate event, and whether it was the best move
  - the best move in UCI and SAN, expected scores before and after, and loss
  - whether the move ended the game
  - Stockfish's lines before and after, each with its move, score, WDL, and variation in UCI and SAN

Owner and opponent findings are never combined. Each request returns one side, so the evaluations and phase boundaries come from that side's version and can differ slightly between tabs. Each finding keeps the phase stored with its own version.

Data the page takes from elsewhere:

- the owner's name comes from the connected session
- a running or failed analysis comes from `GET /api/analysis`

"Newest" means the most recently stored version of a side, and `currentProfile` means it used the newest benchmark profile. When Stage 5 adds a separate low-heat profile, both definitions must become per-mode.

## How it is built

`GameReviewService` (`gamereview.internal.review`):

1. Looks up the game in the caller's account.
2. Replays it with `ReplayedGame`.
3. Reads the newest version of each side through `AnalysisVersionReader`, joining the profile to report the engine and budgets.
4. Reads every move of the selected version.
5. Converts each stored variation to SAN from its own position with the chess library, stopping at the first move that is not legal. Bad stored data therefore never produces wrong notation.

Stored line JSON is re-validated when read:

- 1–3 lines, with before-lines required
- exactly one integer score: centipawns within ±100,000, or a non-zero mate within ±1,000
- three integer WDL values that sum to 1,000
- a variation of 1–64 UCI moves that starts with the line's move

Malformed JSON, invalid lines, or a stored move that does not replay from its stored position fail the request as `review_unavailable`, never as a raw server error.

## Tests

- **`GameReviewPersistenceTest`:** stores Scholar's mate with both sides analyzed by a scripted engine on a real SQLite database. It checks:
  - the owner side is the default
  - every ply and position is present
  - `Qxf7#` is recognized as the best move, with the game ended on the board
  - all 8 evaluations are present
  - the opponent side is separate, with `Nf6` as a deep-passed blunder that allowed mate, best move `Qe7`, and its refutation shown as `Qxf7#`
  - statistics and phase breakdowns are correct
  - unanalyzed and catalog-only games are handled
  - another account cannot read the game
- Corrupt stored evidence is reported as invalid. The cases are empty, non-JSON, non-array, impossible WDL, string scores, two scores, a variation not starting with the move, and a non-UCI variation move; a stored move that does not replay is also rejected.
- **`GameReviewServiceTest`:** SAN conversion stopping at illegal moves or an unreadable position, and centipawn-loss capping including mates and stalemate.
- **`GameReviewControllerTest`:**
  - the response shape
  - side selection
  - validation before authorization
  - denied and unavailable sessions
  - missing games
  - a safe storage error

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: the session is required; denied callers never reach storage. |
| Input validation | Pass: game id pattern and side allowlist before authorization. |
| Cross-account access | Pass: the game lookup and every version query are scoped to the caller's account. |
| Parameterized data access | Pass: constant SQL with named parameters. |
| Untrusted stored data | Pass: stored JSON is strictly re-validated, variations are replayed before they are returned, and any failure is a safe `review_unavailable`. |
| Safe errors | Pass: fixed codes; no database or parsing detail. |

## Next

Part 5b builds the review page on this endpoint, opened from Games with Open Game.
