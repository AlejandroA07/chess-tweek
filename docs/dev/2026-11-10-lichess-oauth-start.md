# Lichess OAuth start

Stage 2 begins with one narrow backend boundary: starting Lichess authorization. There is no account UI, callback exchange, token, cookie, or database change in this slice.

## Delivered

- A same-origin `POST /api/lichess/authorize` endpoint that redirects to the fixed Lichess authorization endpoint.
- Fresh PKCE S256 verifier, challenge, and opaque state for every attempt.
- Exactly the `puzzle:read` scope and a fixed loopback callback URI.
- One in-memory pending attempt with a recorded five-minute expiry; starting another replaces it.
- Denial of missing, foreign, and browser-reported cross-site origins.
- No verifier exposure, logging, browser storage, database storage, or new dependency.

The endpoint is intentionally anonymous because it begins authentication. Its exact local-origin requirement prevents another site from starting that flow through the loopback application.

## Deferred

Callback validation, one-time attempt consumption, code exchange, and sealed token storage are implemented in the next dated report. Account status and logout remain backend work; the UI connection control follows only after the backend flow is complete.
