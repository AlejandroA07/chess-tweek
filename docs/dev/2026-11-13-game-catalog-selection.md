# Game catalog selection

Stage 3 Part 6 adds selection without adding an analysis or bulk-action endpoint.

Each analysis-eligible card has a keyboard-operable checkbox and a selected state that does not rely on color alone. Catalog-only variants show a disabled checkbox with an explicit reason. The selection controls support individual games, Clear Selection, and Select All Filtered. Filtered-group selection uses the existing authorized catalog query with the current filters and analysis eligibility enforced, so its exact count includes eligible games on unloaded pages without downloading the entire catalog. Visible games can be excluded from or restored to that group. Changing filters, changing accounts, or retrieving another batch clears selection so its meaning cannot become stale.

Selection remains in browser memory and cannot start work. The sticky summary reports the exact selection count and distinguishes an explicit selection from the eligible filtered group.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: filtered selection reuses the authenticated `GET /api/games` endpoint. |
| Object-level authorization | Pass: the sealed session supplies the account scope; the browser cannot select another account. |
| Token/session handling | Pass: no token or session value enters selection state, URLs, or rendered output. |
| Request validation | Pass: the existing bounded and allowlisted catalog filters remain the only request input. |
| IDs and enums | Pass: individual IDs come only from validated catalog responses; filter enums remain allowlisted server-side. |
| File uploads | Not applicable. |
| Response DTOs | Pass: the existing bounded card response is reused with a one-record page for the eligible total. |
| Safe errors | Pass: selection failures use fixed user-facing text and expose no internal details. |
| Safe logging | Pass: selection adds no logging. |
| Parameterized data access | Pass: the reused catalog query remains parameterized and account-scoped. |
| Output encoding | Pass: React renders all card and selection text without raw HTML. |
| Redirect allowlist | Not applicable. |
| Abuse resistance | Pass: filtered selection requests one card, uses the bounded catalog total, and aborts superseded requests. |
| Verification tokens | Not applicable. |
| Denied-path regression | Pass: the existing disconnected catalog test protects the reused endpoint before account reads. |

The next independent Stage 3 part adds exact retrieval write-result accounting. Rating-change ingestion and Dashboard rating/bot context follow before Stage 4 single-game Stockfish analysis.
