# Opening explorer backend

Stage 10 Part 1 adds the server side of the opening explorer: Lichess Masters and Lichess database statistics through a cached server-side proxy, and My games statistics from the owner's stored games of both sites (`docs/planning/03-delivery-plan.md`, Stage 10). The explorer page is Part 2. No dependency was added.

## Positions

- `shared.chess.ExplorerPosition` is the only way to turn untrusted text into a position. It accepts a full six-field FEN of a legal standard position, up to 100 characters. That means eight ranks of eight files, exactly one king per side, no pawn on the first or last rank, and the side that just moved not left in check.
- Two positions are the same when their pieces, side to move, castling rights, and en passant capture match. Move counters are ignored, and an en passant square counts only when a legal en passant capture exists. This is the Lichess explorer convention, so transpositions meet at one key.

## My games

- Migration V19 adds a derived position index owned by `game-review`. Each stored standard game is replayed once, and its first 60 plies are indexed: each position with the move played from it, plus the final position when the game ended within those plies. `game_review_position_index_game` marks a game as indexed. A game whose moves cannot be replayed is marked with 0 plies, so it is never retried.
- The index catches up on each My games request, in batches of 200 games, newest first. There is no hook into game retrieval, so the index cannot drift from the catalog.
- A game counts once for a position, with the move it played the first time it reached it. Results are counted as White wins, draws, and Black wins, like the Lichess explorer.
- Filters: owner color, speed, and site, each optional. Other accounts' games and non-standard variants are never counted.
- The answer lists the moves played from the position (most played first) and the 8 newest games through it, with the site and game ID for a link to the Game Review.

## Lichess explorer

- `training` asks `https://explorer.lichess.org/masters` (12 moves, 15 top games) and `https://explorer.lichess.org/lichess` (standard, 12 moves, 4 top games, no recent games). The `lichess` request can be narrowed by speeds and by the explorer's rating groups (0, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2500).
- The request carries the owner's token as a bearer header. The token is never cached, logged, or returned. Redirects are not followed, the connect timeout is 5 s, and the read timeout is 10 s.
- The answer must be JSON of at most 256 KB. Every field is checked: counts, the UCI and SAN pattern of each move, ECO codes, game IDs, titles, the winner, years, and names (no control characters, bounded length). Lists hold at most 64 items. Anything unexpected rejects the whole answer.
- Migration V20 adds `training_explorer_cache`, keyed by a SHA-256 digest of the database, the position key, and the filters. It stores only the validated answer. Masters answers stay fresh for 30 days and Lichess answers for 7 days. The cache keeps at most 20,000 entries and drops the oldest first.
- Only one upstream request runs at a time. After a 429, nothing is sent until the wait is over: at least 60 s, or longer if Lichess says so. When Lichess is rate-limited, unreachable, or answers invalidly, an expired cached answer is shown with `stale: true`. A refusal (401 or 403) is never cached and does not end the app session.

## Endpoints

Each endpoint needs a connected session and returns `Cache-Control: no-store`. Invalid input returns `400 invalid_explorer_request` before the session is checked.

- `GET /api/explorer/masters?fen=` returns the opening, results, moves, top games, `fetchedAt`, and `stale`.
- `GET /api/explorer/lichess?fen=&speeds=rapid,blitz&ratings=1600,1800` returns the same shape. Lists must hold distinct known values.
- `GET /api/explorer/my-games?fen=&color=&speed=&source=` returns the results, the moves, and the recent games.
- Errors:
  - `401 not_connected`
  - `503 session_unavailable`
  - `429 lichess_rate_limited`, with `Retry-After`
  - `503 lichess_unavailable`
  - `502 invalid_lichess_response`
  - `502 lichess_explorer_refused`
  - `503 my_games_unavailable`
- A Lichess explorer failure never affects My games.

## Tests

- `ExplorerPositionTest`: the key convention, en passant with and without a legal capture, transpositions, and rejected positions.
- `OpeningPositionsTest`: indexed positions and moves, the 60-ply limit, the final position, and illegal moves.
- `MyGamesExplorerPersistenceTest`: transpositions across both sites, account and variant scoping, filters, catching up with later games, a repeated position counted once, and an unplayable game.
- `HttpLichessExplorerClientTest`: request parameters and the bearer header, parsing, the rate-limit wait, refusal and failure mapping without the token, and invalid or oversized answers.
- `OpeningExplorerServiceTest`: fresh and expired cache entries, separate keys per filter, stale answers, the rate-limit gate, no caching of refusals, and an unreadable cache entry.
- `ExplorerCachePersistenceTest`: replacement, the entry limit, and survival across a restart.
- `OpeningExplorerControllerTest`: responses without the token, filter parsing, validation before the session check, the session requirement, error mapping, and My games working while the explorer is rate-limited.

## Not checked live

The Lichess explorer was not called from this branch, because the owner's token lives only in the browser session. The request shape follows the official API specification (`lichess-org/api`, `openingexplorer/masters.yaml` and `lichess.yaml`). The first real check comes with the Part 2 page.
