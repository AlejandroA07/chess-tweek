# Games catalog API and cards

Stage 3 Part 3 exposes the stored catalog through an authorized, account-scoped read API and replaces the Games placeholder with the first responsive library view.

## Delivered behavior

- `GET /api/games` reads only the account ID obtained from the sealed Lichess session.
- Offset and limit are validated before session authorization. Pages are capped at 50 games and all SQL remains parameterized.
- Responses contain card data only; moves, PGN, clocks, tokens, and persistence fields are not exposed.
- The Games route validates API responses, shows matching card skeletons, empty and connection states, safe retry, responsive cards, two perspective analysis statuses, unsupported variants, and Load Older.
- Keyboard focus is moved to the resulting catalog status after Retry and Load Older. Long opponent content wraps safely, and numeric chess data uses tabular technical styling.

## Security checklist

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: a validated sealed Lichess session is required. |
| Object-level authorization | Pass: callers cannot provide an account ID; the authorized account ID scopes every query. |
| Token/session handling | Pass: token material is neither returned nor passed into catalog storage. |
| Request validation | Pass: offset and limit are length, type, range, and maximum-page validated before authorization. |
| IDs and enums | Pass: the response is built from previously validated catalog values and the client validates its bounded schema. |
| File uploads | Not applicable. |
| Response DTOs | Pass: dedicated card DTOs omit raw game and database data. |
| Safe errors | Pass: session and storage failures use fixed error codes without internal details. |
| Safe logging | Pass: no token, game payload, or personal-data logging was added. |
| Parameterized data access | Pass: account, limit, and offset use JDBC parameters. |
| Output encoding | Pass: React text rendering is used without raw HTML. |
| Redirect allowlist | Not applicable: the endpoint does not redirect. |
| Abuse resistance | Pass: a page is capped at 50 records and offset is bounded. |
| Verification token rules | Not applicable. |
| Denied-path regression | Pass: disconnected access is denied before catalog reads, and invalid paging is rejected before session access. |

## Remaining Stage 3 work

The next independent slice adds the Get Lichess Games side panel, filtering, and selection. Owner rating change is not yet available to cards because the ingestion schema does not currently store Lichess `ratingDiff`; that extension remains explicit future work rather than an invented value.
