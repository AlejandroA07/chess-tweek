# Lichess OAuth callback

The second Stage 2 slice completes the backend callback and stores the returned token without adding a dependency or database data.

## Delivered

- One-time, constant-time validation of 43-character state with a five-minute expiry.
- Bounded authorization-code validation before any upstream request.
- Fixed form-encoded exchange with the Lichess token endpoint and short network timeouts.
- AES-256-GCM sealing with a fresh nonce, authenticated expiry, and an owner-only `var/lichess-cookie.key` file.
- A `__Host-` Secure, HttpOnly, SameSite=Strict cookie containing only sealed ciphertext.
- Safe handling for cancellation, wrong/expired/replayed state, rejected codes, malformed token responses, unsafe key paths, and unavailable Lichess.
- No-store and no-referrer callback responses so codes and state are not retained or forwarded.

The callback is anonymous because it completes authentication. Its one-time state is the callback authorization and CSRF boundary. The token and verifier are never logged, returned in a body, stored in SQLite, or exposed to browser JavaScript.

## Deferred

The next backend slice opens and validates the sealed session, exposes minimal connection status, retrieves the current Lichess account, and implements logout/revocation. It is recorded in the next dated report; account UI follows that contract.
