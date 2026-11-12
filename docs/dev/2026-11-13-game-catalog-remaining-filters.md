# Remaining game catalog filters

Stage 3 Part 5 completes the approved catalog filter set without adding selection or analysis actions.

The Games toolbar now combines result, owner color, editable opening ECO/name, opponent type, Lichess speed, inclusive UTC from/to dates, variant, analysis eligibility, and newest/oldest order. Every change resets pagination to the first filtered page; Load Older and matching totals preserve the complete query. Opening search escapes SQL wildcard characters, bot users remain distinct from titled human users, and catalog-only analysis maps to stored analysis ineligibility.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: a valid sealed Lichess session remains required. |
| Object-level authorization | Pass: callers cannot provide an account ID; the authorized session scopes every query. |
| Token/session handling | Pass: no token or session data enters filter URLs or responses. |
| Request validation | Pass: text and dates are bounded, enums are allowlisted, and reversed/out-of-range dates are rejected before authorization. |
| IDs and enums | Pass: opponent type, speed, variant, analysis state, and sort accept only documented values. |
| File uploads | Not applicable. |
| Response DTOs | Pass: the existing bounded card-only response remains unchanged. |
| Safe errors | Pass: invalid filters return a fixed error code without internal details. |
| Safe logging | Pass: no query values, tokens, or personal data are newly logged. |
| Parameterized data access | Pass: all filter values use named JDBC parameters; only allowlisted fixed SQL fragments select opponent rules and sort direction. |
| Output encoding | Pass: React renders labels, values, and cards as text without raw HTML. |
| Redirect allowlist | Not applicable. |
| Abuse resistance | Pass: filtered pages retain the 50-record cap and bounded offset. |
| Verification tokens | Not applicable. |
| Denied-path regression | Pass: disconnected access remains covered; malformed advanced filters are rejected before session access. |

The next independent part adds single-game and filtered-group selection. Bulk actions and analysis remain later stages.
