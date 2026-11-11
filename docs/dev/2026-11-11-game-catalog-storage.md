# Lichess game catalog storage

Stage 3 Part 2 adds durable, account-scoped storage behind the protected manual retrieval endpoint. It does not add catalog UI or start Stockfish analysis.

## Delivered behavior

- Flyway migration `V2` creates a compact SQLite catalog keyed by Lichess account ID and game ID.
- Re-fetching updates safe metadata without creating duplicates, and catalog data survives restart.
- Stored fields cover moves and PGN, clocks, result, owner color and rating, opponent context, speed, time control, rated state, variant, opening, and phase divisions when available.
- `io.github.wolfraam:chessgame:2.3` validates legal SAN for standard games. Non-standard variants are stored but marked analysis-ineligible.
- Malformed JSON, invalid UTF-8, oversized games, unknown values, and illegal standard moves are rejected per game. The safe response reports accepted and rejected counts.
- Lichess transport failure occurs before persistence, so the existing catalog remains unchanged. Retrieval never starts analysis.

## Security checklist

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: the existing sealed Lichess session remains required. |
| Object-level authorization | Pass: the account ID comes from the authorized session, never request input; cross-account catalog isolation is tested. |
| Token/session handling | Pass: the bearer token is used only for the outbound request and is not stored or returned. |
| Request validation | Pass: count and date remain bounded and same-origin is required. |
| IDs and enums | Pass: upstream IDs, statuses, variants, speeds, titles, and chess data are allowlisted or validated. |
| File uploads | Not applicable. |
| Response DTOs | Pass: only requested, accepted, rejected, and before-date values are returned. |
| Safe errors | Pass: upstream and storage failures map to fixed error codes without internal details. |
| Safe logging | Pass: no token or raw game logging was added. |
| Parameterized data access | Pass: all catalog values use JDBC parameters. |
| Output encoding | Not applicable: no HTML rendering was added. |
| Redirect allowlist | Not applicable: this endpoint does not redirect. |
| Abuse resistance | Pass: only one retrieval runs at a time and Lichess rate limits are honored. |
| Verification token rules | Not applicable. |
| Denied-path regression | Pass: logged-out, foreign-origin, and invalid-input requests are tested before session or Lichess access. |

## Verification

The repository gate covers frontend tests and build, backend compilation and tests, Flyway restart behavior, architecture rules, packaging, and Git whitespace checks. The next Stage 3 part exposes the authorized catalog to the Games interface with its approved filters and cards.
