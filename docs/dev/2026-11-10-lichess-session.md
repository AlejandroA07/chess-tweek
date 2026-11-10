# Lichess session status and logout

The third Stage 2 slice completes the backend connection contract without adding dependencies or database data.

## Delivered

- Authenticated opening of the AES-GCM cookie across application restarts, including its embedded expiry.
- `GET /api/lichess/session` with disconnected, connected, revoked-token, and temporary-upstream-failure behavior.
- Current account ID, username, and bullet, blitz, rapid, classical, and correspondence ratings only.
- `POST /api/lichess/logout` with exact local-origin validation.
- Fixed bearer-authenticated Lichess account and token-revocation requests with short timeouts and no redirects.
- Cookie clearing for invalid, expired, tampered, or revoked sessions and every accepted logout.
- Best-effort upstream revocation that never prevents local logout.

The session endpoint is anonymous so first-run clients can learn that no connection exists. It returns account data only after successfully opening the local sealed cookie and confirming that token against Lichess. Logout is a protected mutation and rejects missing, foreign, or cross-site origins.

## Deferred

The next slice connects this contract to the account region and first-run interface, then drives the full OAuth flow manually with the owner account. No game retrieval or automatic work is part of that slice.
