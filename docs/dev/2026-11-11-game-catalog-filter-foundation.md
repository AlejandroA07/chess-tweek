# Game catalog filter foundation

Stage 3 Part 5a adds the first complete-catalog filters without adding selection or analysis actions.

The Games toolbar now filters by owner result and owner color and sorts newest or oldest. Every change resets the page to offset zero and requests the authorized catalog API; Load Older preserves the same query. Totals come from the filtered account-scoped SQL query rather than from client-side filtering of the visible page. Clear Filters restores the default newest-first catalog.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: the existing sealed Lichess session remains required. |
| Object-level authorization | Pass: account identity still comes only from the authorized session. |
| Token/session handling | Pass: no token or session data was added to query parameters or responses. |
| Request validation | Pass: pagination and each filter/sort value are allowlisted before authorization. |
| IDs and enums | Pass: result, color, and sort accept only documented values. |
| File uploads | Not applicable. |
| Response DTOs | Pass: the existing bounded card DTO remains unchanged. |
| Safe errors and logs | Pass: fixed error codes are returned and no new logging was added. |
| Parameterized data access | Pass: account, filter values, pagination, and counts use named JDBC parameters; ordering uses fixed enum-selected SQL constants. |
| Output encoding | Pass: React renders filter labels and card values as text. |
| Redirect allowlist | Not applicable. |
| Abuse resistance | Pass: filtered pages retain the 50-record cap and bounded offset. |
| Verification tokens | Not applicable. |
| Denied-path regression | Pass: disconnected access remains covered and unknown filters are rejected before session access. |

The next independent part adds opening, opponent, speed, date, variant, and analysis-state filters. Game selection remains a later part.
