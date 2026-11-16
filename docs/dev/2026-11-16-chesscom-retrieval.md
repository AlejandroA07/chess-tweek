# Chess.com retrieval

Stage 5 Part 2 stores the owner's chess.com username and retrieves finished chess.com games into the shared catalog through the public Published-Data API. There is no interface yet; Part 3 adds it.

## Username

Migration `V7__game_review_chesscom_player.sql` stores one username per account (3 to 25 letters, digits, `_`, or `-`, kept as entered). `ChessComPlayers` reads, saves, and removes it.

## Retrieval

`ChessComRetrievalService` reads the archive month list, then months newest first, one request at a time (chess.com allows unlimited serial requests and may answer parallel ones with 429). Months after the one holding the last second before the optional date are skipped without a request. Within each month it keeps the newest games that ended before that date until it has the requested 1 to 50, then saves them through the same idempotent catalog store as Lichess, so a repeat creates no duplicate and reports new, updated, and unchanged counts. At most 24 months are read per retrieval; older games are reached with the before date. One chess.com retrieval runs at a time.

`HttpChessComGameClient` builds every URL from the validated, lowercased username and the month; URLs inside responses are only read for their year and month. It sends `Accept: application/json` and the generic `User-Agent: ChessTweek/0.1 (local single-user app)`, with no credentials or personal data (chess.com suggests contact details in the User-Agent; the owner's rules exclude them). Redirects are not followed, responses must be JSON, and sizes are capped (256 KB for the month list, 16 MB per month). A 404 or 410 on the month list means the player does not exist; 429 keeps the `Retry-After` wait (60 seconds when absent).

The archives refresh at most every 12 to 24 hours (chess.com documentation), so a game finished minutes ago may not be available yet.

## Mapping (`ChessComGameParser`)

| Catalog | From chess.com |
|---|---|
| Game ID | The number in `https://www.chess.com/game/live/…` or `…/daily/…` |
| Owner side | The player whose username matches the stored one, ignoring case |
| Result and status | Owner `win`/other side's code, or the shared draw code: `checkmated` mate, `resigned` resign, `timeout` out of time, `abandoned` timeout, `lose` unknown finish, `stalemate` stalemate, `agreed`/`repetition`/`insufficient`/`50move`/`timevsinsufficient` draw |
| Speed | `bullet`, `blitz`, `rapid`; `daily` is correspondence |
| Time control | `600+5` is 600 seconds plus 5; `1/259200` is 3 days per move |
| Played at | `start_time` for daily games, otherwise the PGN `UTCDate` and `UTCTime`; never after `end_time` |
| Moves and clocks | Replayed from the PGN with the approved `chessgame` library; clocks in centiseconds only when every move has `%clk` |
| Opening | PGN `ECO`, with the name from the `ECOUrl` slug (hyphens become spaces) |
| Rating | The owner's and opponent's ratings; the archive has no per-game rating change, so it stays empty |

Only standard chess from the normal starting position is stored. Variants (chess960, crazyhouse, and others), games set up from a position, illegal or unreadable moves, unknown result pairs, and missing or unexpected values in the fields above are rejected and counted, never stored partially. Fields the parser does not use are ignored.

Known limit: live and daily games are numbered separately on chess.com, so a live and a daily game could share a number. Chess Tweek would treat them as one game. This needs one live and one daily game of the owner with the same number and is not handled.

Checked against real data: the parser read all 583 games of one public player's month (October 2026) with no rejections, clocks on every game, and opening names on every game. That file was used only for the check and is not committed.

## Endpoints

All need the Lichess session of the active account; changes also need the same-origin check. Input is validated before the session check.

| Request | Response |
|---|---|
| `GET /api/chesscom/player` | `{"username": "…" or null}` |
| `PUT /api/chesscom/player` with `{"username"}` | `200` with the username; `400 invalid_chesscom_username` |
| `DELETE /api/chesscom/player` | `204` |
| `POST /api/chesscom/games/retrievals` with `{"count": 1-50, "before": "YYYY-MM-DD"?}` | `200` with requested, received, rejected, created, updated, unchanged, before. `400 invalid_retrieval_request`, `409 chesscom_username_missing`, `404 chesscom_player_not_found`, `429 chesscom_rate_limited` with `Retry-After`, `502 invalid_chesscom_response`, `503 chesscom_unavailable`, `503 catalog_unavailable`, `409 retrieval_already_running` |

Every endpoint also answers `401 not_connected` or `503 session_unavailable` for the session.

## Tests

`ChessComGameParserTest` (7), `HttpChessComGameClientTest` (7), `ChessComRetrievalServiceTest` (8), `ChessComRetrievalPersistenceTest` (2, real database: username per account, repeat retrieval without duplicates, database check), and `ChessComControllerTest` (6).
