# Single-game analysis (backend)

Stage 4 Part 4 is split in two. Part 4a, recorded here, runs a real Stockfish analysis of one stored game in the background and stores the results as immutable versions. Part 4b adds the Games controls, progress, and status badges. Until then the analysis is reachable only through the API.

## Flow

1. The owner asks to analyze one stored game from one perspective: My moves, Opponent moves, or Both.
2. The request is refused immediately when:
   - the game is not the account's
   - the game is not standard chess or cannot be replayed
   - no benchmark profile exists
   - another analysis is running
3. Otherwise the analysis starts on a background thread and the request returns at once.
4. The background run takes the single engine slot (`StockfishEngineAccess`), so a health check or benchmark cannot run alongside it.
5. Stockfish must report the same name as the profile it was benchmarked with. A different version fails with `profile_outdated` and asks for a new benchmark.
6. The engine gets the profile's settings (threads, hash, `MultiPV`, WDL) and one `ucinewgame`.
7. Each position is sent as `position startpos moves ...`, so Stockfish sees the real game history for repetitions and the fifty-move rule.
8. Each search is `go nodes N` with the profile's quick or deep budget, and is limited to 60 seconds.
9. `TwoPassAnalysis` judges every move of the requested sides, reporting progress per move in each pass.
10. When every search succeeded, the findings are split by mover and stored in one transaction: one version for the owner's moves, one for the opponent's, never combined.

## Reading Stockfish output

`UciSearchOutput` treats engine output as untrusted:

- It keeps the latest complete line for each `multipv` rank.
- It keeps bound (`lowerbound`/`upperbound`) lines. When the node limit stops a search partway through an iteration, Stockfish's final line for the move it plays is often a bound. Ignoring bounds made about one search in 30 fail during the live check, because the kept best line then disagreed with `bestmove`. A bound score lies within Stockfish's narrow aspiration window. The test uses the real output that failed.
- It ignores `info string` and lines without WDL or a variation.
- Every number is bounded and every move must be UCI.
- Variations are cut to 64 moves.
- `mate 0` is never accepted. Positions that are already over are judged on the board and never searched.
- The result is rejected unless the ranks run 1, 2, 3 without gaps and the first line's move equals Stockfish's `bestmove`.

## Failures

Every failure is recoverable by fixing the cause and starting again. Nothing partial is ever stored.

| Failure | Cause |
|---|---|
| `engine_busy` | A health check or benchmark held the engine when the analysis began. |
| `engine_unavailable` | Stockfish is missing, not executable, not Stockfish, or not responding. |
| `engine_failed` | A search timed out, the process died, or the output was unusable. |
| `profile_outdated` | The installed Stockfish is not the one the profile measured. |
| `storage_failed` | The database rejected the results; the transaction leaves no version behind. |

Canceling stops before the next search, which is at most one deep search (about 3 seconds) later, and stores nothing. A cancel that arrives during the last search is still honored. If the background work ends in any unexpected way, the job is marked failed, so it can never block later analyses.

Only one analysis exists in memory at a time. It is lost when the app restarts. The persistent queue, restart recovery, and thermal pausing are Stage 5.

## Storage

Migration `V5__game_review_analysis_version.sql` adds two tables.

`game_review_analysis_version` holds one row per analyzed side:

- the account and game, side (`owner` or `opponent`), the perspective the owner requested, and analyzed color
- the profile, and a rules version (`MoveRules.RULES_VERSION`, now 1)
- the phase source and boundaries
- totals: moves, deep-passed moves, inaccuracies, mistakes, blunders, and expected-score loss
- start and completion times

The game and profile references are `RESTRICT`, so a game or profile with analyses cannot be deleted silently. A game's current version for a side and profile is the newest one, served by the index on account, game, side, profile, and id. Deleting a version is not offered yet. Admin data management (Stage 10) adds guarded deletion once reports reference versions.

`game_review_analysis_move` holds every judged move of a version:

- the ply, SAN, UCI, and pre-move FEN
- the phase, the pass that produced the verdict, the label, the mate event, and whether the best move was played
- the best move, the expected scores before and after, and the loss
- whether the move ended the game
- Stockfish's lines before and after the move as JSON, including centipawn or mate score, WDL, and variation

`CHECK` constraints repeat the rules: a best move has no loss or label, a mate event is always a blunder, and a finished game has no after-lines. Triggers reject every `UPDATE` on both tables, so reanalysis can only add a new version.

## API

| Request | Result |
|---|---|
| `POST /api/games/{gameId}/analyses` with `{"perspective": "my_moves" \| "opponent_moves" \| "both"}` | `202` with the job. `400 invalid_analysis_request`, `404 game_not_found`, `409 analysis_not_supported`, `409 analysis_busy`, `409 engine_profile_missing`, `503 analysis_unavailable`. |
| `GET /api/analysis` | `200` with the account's running or most recent job, or `null`. |
| `POST /api/analysis/cancel` | `200` with the job, or `409 no_running_analysis`. |

A job reports its state, current pass (`starting`, `quick`, `deep`, `saving`), moves judged out of the moves in that pass, failure, times, and the stored version ids.

## Live check

Real Stockfish 19 runs at the owner's budgets (880,000 and 3,540,000 nodes) on this Mac.

A 14-move miniature, both sides, took 36 seconds:

- every position returned three lines
- 4 moves were checked again in the deep pass
- `...Nd4` was a mistake, `Nxe5` and `Nxf7` were blunders, and `Be2` allowed mate in one

An 80-ply game played by Stockfish at about 1600 strength:

| Perspective | Moves judged | Deep-passed | Time |
|---|---|---|---|
| My moves | 40 | 9 | 126 s |
| Opponent moves | 40 | 5 | 103 s |
| Both | 80 | 13 | 150 s |

Both sides is well inside the 2–4 minute target. One side is at the top of the 1–2 minute target, because judging one side still searches nearly every position of the game once. Each deep-passed move adds about 6 seconds. A game with many errors will take longer, and Stage 5's progress display should show measured progress rather than promise a time.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: all three endpoints require the sealed Lichess session; denied callers never reach the analysis service. |
| Mutation protection | Pass: start and cancel require the same local Origin before authorization. |
| Input validation | Pass: the game id must be 8–32 letters or digits and the perspective is allowlisted, both before authorization. The game is looked up only within the caller's account. |
| Command execution | Pass: UCI commands contain only fixed text, profile integers, and UCI moves produced by the chess library from stored, replayed games. |
| Untrusted process output | Pass: see Reading Stockfish output. |
| Resource limits | Pass: one analysis at a time, fixed node budgets, 60 seconds per search, games of at most 1,200 plies, bounded stored JSON. |
| Parameterized data access | Pass: constant SQL with named parameters. |
| Safe errors | Pass: fixed error and failure codes; no process, path, or database detail. |
| Cross-account access | Pass: another account cannot see or cancel the analysis. |

## Next

Part 4b adds to Games:

- Analyze My Moves, and Analyze Opponent and Analyze Both in the More menu
- progress with Cancel
- recoverable failure messages
- analysis status badges built from stored versions
