# Exact retrieval write counts

Stage 3 Part 7 makes the manual retrieval result precise without changing retrieval limits or starting analysis.

Before each bounded batch is upserted, SQLite reads the existing account-scoped rows for those game IDs in one parameterized query. A missing row is new, a row whose stored game data changed is updated, and an identical stored game is unchanged. The comparison excludes only the retrieval timestamp; every accepted game is still upserted transactionally so its latest retrieval time remains current. Duplicate IDs inside one batch are also classified against the preceding item rather than counted as multiple new rows.

The protected retrieval response now reports received, rejected, created, updated, and unchanged counts with the invariant `created + updated + unchanged = received`. The React client validates that invariant before displaying “new,” “updated,” and “unchanged” in the side-panel success summary. Retrieval remains manual and never starts analysis.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: the existing sealed Lichess session remains required. |
| Object-level authorization | Pass: the authorized account ID scopes both the existing-row read and every upsert; callers cannot submit an account ID. |
| Token/session handling | Pass: tokens remain inside the existing authorization callback and never enter the response or logs. |
| Request validation | Pass: the existing 1–50 count and exact optional UTC date validation are unchanged. |
| IDs and enums | Pass: game IDs and enums still come from the bounded, validated Lichess parser rather than client input. |
| File uploads | Not applicable. |
| Response DTOs | Pass: the response adds only bounded aggregate integer counts. |
| Safe errors | Pass: storage and upstream failures retain fixed error codes without database or token details. |
| Safe logging | Pass: no token, game payload, or personal data is newly logged. |
| Parameterized data access | Pass: the account ID and bounded game-ID collection use named JDBC parameters; upserts remain parameterized. |
| Output encoding | Pass: React renders the validated counts as text without raw HTML. |
| Redirect allowlist | Not applicable. |
| Abuse resistance | Pass: retrieval remains limited to 50 games, single-flight, and rate-limit aware. |
| Verification tokens | Not applicable. |
| Denied-path regression | Pass: disconnected and foreign-origin requests remain covered before authorization or retrieval work. |

The next independent Stage 3 part ingests and displays the owner's Lichess rating change. Dashboard rating and bot-opponent context follows before Stage 4 single-game Stockfish analysis.
