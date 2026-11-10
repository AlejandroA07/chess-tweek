# Lichess connection interface

The final Stage 2 implementation slice connects the verified backend contract to the Dashboard without adding dependencies or database data.

## Delivered

- A same-origin authorization form that navigates through the protected local start endpoint.
- Disconnected, checking, connected, revoked/disconnected, and upstream-unavailable account states.
- Current bullet, blitz, rapid, classical, and correspondence ratings.
- Retry and logout controls with disabled progress states and safe visible failures.
- Fixed Dashboard callback notices for cancellation, invalid or expired authorization, and upstream unavailability.
- Runtime validation of the session response before account data is rendered.
- Responsive account status and controls using the canonical tokens and CSS Modules.

The browser receives only account identity and ratings. The sealed credential remains in the Secure, HttpOnly, SameSite cookie and never enters browser JavaScript or SQLite. Callback failures redirect only to fixed local Dashboard locations, remove their query marker after rendering, and disclose no upstream detail.

## Verification

The repository gate covers the frontend flows, protected backend boundaries, safe callback outcomes, production build, backend package, architecture rules, and whitespace checks. A manual packaged-app check confirmed the compact layout and followed Connect Lichess to the real Lichess sign-in page with the approved read-only puzzle scope.

The owner must sign in and approve access to complete the live account acceptance check. Stage 3 game retrieval remains out of scope until that check and review are complete.
