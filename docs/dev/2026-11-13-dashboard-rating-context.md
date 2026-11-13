# Dashboard rating and bot context

Stage 3 Part 9 completes the Stage 3 implementation. It adds current Lichess ratings with stored history, the most recent rating change, and the record against Lichess AI and BOT accounts to the Dashboard.

## Decision

On 2026-11-13 the owner chose to build rating history from games already stored in the local catalog, not from Lichess's rating-history endpoint. Chess Tweek makes no new Lichess request and needs no new permission. The history covers only the games that have been fetched, and the Dashboard says so.

## Data

`GET /api/dashboard/rating-context` is protected by the existing sealed Lichess session. It reads only the authorized account's rows through `JdbcGameRatingContext`, which uses named, parameterized SQL.

- **Rating history:** covers Rapid, Blitz, Bullet, and Classical, in that order at the owner's request, counting only rated standard-chess games that have an owner rating. Variant games have their own Lichess ratings and are excluded.
  - Each game becomes one point: its pre-game rating plus its Lichess rating change. A missing change counts as zero.
  - The response holds the 30 most recent points per speed (oldest first), the number of qualifying games, and the most recent game's change. That change is null when Lichess supplied none.
- **Bot record:** counts every stored game against Lichess AI or a `BOT`-titled account, rated or casual and any variant.
  - It reports wins, draws, and losses, the number of games, and the lowest and highest rated BOT opponent.
  - Groups are one per Lichess AI level, then 200-point BOT rating bands (for example 1400–1599), then BOT accounts without a displayed rating.
  - Lichess AI has no displayed rating, so it is grouped by level.
- **Validation:** the React client validates every count, bound, and group. The group totals must add up to the record total, and an inconsistent response is shown as a recoverable failure instead of being rendered.

## Interface

- **Rating panel:** the Dashboard shows one "Ratings and bot record" panel under the account panel. Each Rapid, Blitz, Bullet, and Classical card shows:
  - the current Lichess rating from the session
  - the most recent change, such as `Last game −6`
  - an SVG history line with a visible caption giving the first and last rating, the number of games shown, and the low and high, so the line can be checked against Lichess
  - the number of stored rated games
- **Other ratings:** Correspondence appears in a compact line. The account panel no longer repeats the ratings.
- **Bot record:** the card spans the row below the four rating cards, with totals on the left and the group table on the right.
- **Current ratings stay visible:** they show while the history loads and after a history failure. The failure offers Retry.
- **Empty states:** they explain what is missing and link to Games.
- **Placeholder:** the old "No chess activity yet" Dashboard placeholder now shows only while Lichess is disconnected.

The history line is hand-written SVG, and no dependency was added. It was checked at 1440 px and 390 px with no horizontal page scroll.

## Security review

| Check | Verdict |
|---|---|
| Explicit authentication | Pass: the endpoint requires the sealed Lichess session; disconnected callers get `401` and a cleared cookie when appropriate. |
| Object-level authorization | Pass: the account ID comes only from the authorized session and scopes every query. |
| Token/session handling | Pass: no token or session data enters the response or logs. |
| Request validation | Pass: the endpoint takes no parameters. |
| Parameterized data access | Pass: account ID, speeds, and point limit are named JDBC parameters; SQL text is constant. |
| Response DTOs | Pass: bounded integers, fixed enum strings, and ISO timestamps only. |
| Safe errors | Pass: storage failures return the fixed `rating_context_unavailable` code without database detail. |
| Output encoding | Pass: React renders all values as text; the SVG uses computed numeric coordinates only. |
| Denied-path regression | Pass: disconnected, unavailable, and rate-limited sessions are tested to return before any storage read. |

With Part 9, every Stage 3 deliverable is implemented. The owner's live check against the real Lichess account closes Stage 3, and Stage 4 single-game Stockfish analysis follows.
