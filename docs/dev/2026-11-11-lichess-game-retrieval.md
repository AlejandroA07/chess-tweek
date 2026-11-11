# Lichess game retrieval boundary

Stage 3 Part 1 adds an authenticated manual retrieval boundary without adding persistence, catalog UI, or analysis.

`POST /api/lichess/games/retrievals` accepts a count from 1 through 50 and an optional ISO local date. The endpoint requires the exact local application origin and a valid sealed Lichess session. The account username and bearer token come only from the validated session, never from request input.

The fixed Lichess export request asks for finished games in reverse chronological order as NDJSON. It rejects redirects, non-NDJSON responses, unfinished statuses, invalid identifiers, excess games, oversized lines, and oversized responses. Credentials and raw game data never enter the browser response. Concurrent retrieval is rejected so this local application cannot start overlapping exports.

Recoverable outcomes distinguish rejected credentials, rate limiting, malformed upstream data, upstream unavailability, and an already-running retrieval. No game is written to SQLite and no analysis begins in this part.

The next Stage 3 part will define and migrate the account-scoped game catalog, then persist validated exports idempotently by Lichess game ID.
