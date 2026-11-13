# Owner rating change

Stage 3 Part 8 stores and shows how much the owner's Lichess rating changed in each retrieved game. It uses only the number Lichess reports and does not calculate or estimate a rating.

The Lichess game export already supplies `players.<color>.ratingDiff` for rated games, so the retrieval request is unchanged. The parser reads `ratingDiff` only for the owner's side. The value must be a JSON integer from -1000 through 1000. A rating change on a casual game is inconsistent upstream data, so that game is rejected like any other invalid game, without affecting the rest of the batch. The opponent's rating change is not stored.

Migration `V3__game_owner_rating_change.sql` adds the nullable `owner_rating_change` column with the same range enforced by a database `CHECK` constraint. Null means Lichess supplied no change: a casual game, or a rated game exported without one. Zero is kept as a real, unchanged rating. Games stored before this migration keep a null change. A later Get Lichess Games run that returns a change for one of them now reports it as updated rather than unchanged, because the stored game data is different.

The protected catalog response adds `ownerRatingChange` to each card. The React client accepts only null or an integer from -1000 through 1000, and rejects the whole page otherwise. Cards show the change after the owner's rating, for example `You 1,800 (+8)`, `(−12)`, or `(±0)`. When there is no change, nothing is added. The change is plain muted text, because the design system keeps success and error colors for their own meanings.

Known limitation: Lichess's provisional-rating flag is not stored yet. Part 9's Dashboard rating history can add it if the design needs it.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: retrieval and catalog reads still require the sealed Lichess session. |
| Object-level authorization | Pass: the change is stored and read only inside the authorized account's rows. |
| Token/session handling | Unchanged: no token or session data is added to responses or logs. |
| Request validation | Unchanged: no new request parameter exists. |
| Upstream payload validation | Pass: an integer-only bounded value, owner side only, rejected on casual games; enforced again by a SQLite `CHECK` constraint. |
| Response DTOs | Pass: one bounded nullable integer is added. |
| Client validation and output encoding | Pass: the client checks the range and React renders the value as text. |
| Safe errors and logging | Unchanged. |
| Parameterized data access | Pass: the new column is written through a named JDBC parameter. |
| Denied-path regression | Pass: the existing disconnected and invalid-filter catalog tests still cover the denied paths. |

The next Stage 3 part is the Dashboard's current and historical ratings and the record against Lichess AI and BOT opponents.
