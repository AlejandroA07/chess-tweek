# Games analysis controls

Stage 4 Part 4b adds analysis controls to the Games library: the owner can start a Stockfish analysis from a game card, watch it run, cancel it, and see each side's stored result on the card.

## Cards

Every analysis-eligible card shows one status line per side:

- **Not analyzed**
- **Analyzing…** while the running analysis covers that side
- **Analyzed**, with the newest stored version's counts, for example `2 blunders · 1 mistake · 3 inaccuracies`

Actions:

- **Analyze My Moves** is the primary card action. It reads **Reanalyze My Moves** once that side has a stored version.
- **More** is a disclosure button (`aria-expanded`, `aria-controls`). It reveals **Analyze Opponent Moves** (or **Reanalyze Opponent Moves**) and **Analyze Both Sides**. Escape closes it and returns focus to More.
- While an analysis is starting or running, every card's actions are disabled, with the reason "Available when the current analysis finishes." next to them.

Catalog-only games keep "Catalog only — analysis unavailable" and have no analysis actions.

## Progress panel

The panel sits above the game count and is labelled "Game analysis".

| State | Shows |
|---|---|
| Running | "Analyzing your moves against Rival" (or the opponent's moves, or both sides), the current step, a progress bar, elapsed time, a note that local analysis can warm the Mac, and **Cancel Analysis** |
| Completed | "Analysis saved" with the time taken; the cards refresh |
| Canceled | "Nothing was saved", with **Analyze Again** |
| Failed | A plain reason and next step, **Try Again**, and **Open Admin** when the fix is on Admin |
| Refused start | Why it did not start (another analysis running, no benchmark, unsupported or missing game), with **Open Admin** when relevant |

The current step reads:

- "Starting Stockfish…"
- "First pass · 24 of 41 moves"
- "Second pass · checking 3 of 5 moves more deeply"
- "Saving results…"

The step text is in a polite live region. Failures use `role="alert"`, and successes and cancellations use `role="status"`.

## Behavior

- On opening Games, the library asks for the current analysis once. A running analysis is picked up and followed. A finished one from earlier is not shown again.
- While an analysis runs, the library polls `GET /api/analysis` once a second. Polling stops when the analysis ends or the view closes. A missed poll is retried on the next tick.
- When an analysis completes, the first catalog page is fetched again quietly, without the loading skeleton, so the card shows the saved result.
- Cancel shows "Stopping…" until the backend confirms. It notes that the analysis stops after the current search and that nothing will be saved.

## Backend

`GET /api/games` cards now carry, per side, the newest stored version:

```json
"analysis": {
  "eligible": true,
  "status": "analyzed",
  "myMoves": {
    "id": 12,
    "completedAt": "…",
    "moves": 40,
    "inaccuracies": 3,
    "mistakes": 1,
    "blunders": 2
  },
  "opponentMoves": null
}
```

`status` is one of `not_analyzed`, `analyzed`, or `unsupported_variant`. `GameAnalysisSummaries` answers for at most the 50 games on one page with one parameterized, account-scoped query. Game ids and the account id are validated before querying.

## Client validation

`analysis.ts` and `gameCatalog.ts` validate every field before rendering:

- job states, passes, and failures are allowlisted
- counts are bounded
- a failure is present exactly when the job failed
- `finishedAt` is present exactly when the job is not running
- a version's severity counts cannot exceed its move count

Unknown server error codes become a generic "could not be started" message.

## Tests

`GameAnalysis.test.tsx` covers:

- start, progress, completion, and card refresh
- the More disclosure and keyboard close
- Both sides
- engine failure and retry
- a refused start
- cancel
- ignoring an earlier finished analysis
- no actions on catalog-only games

Backend tests cover the catalog summaries and the newest-version query.

## Security review

| Check | Verdict |
|---|---|
| New endpoints | None. The catalog response gains summaries for the caller's own games only. |
| Data access | One constant parameterized query, scoped to the account; at most 50 validated ids. |
| Client trust | Every response field is validated before use; React renders all text, and no HTML is injected. |
| Mutations | Start and cancel use the existing same-origin, session-protected endpoints. |

## Next

The owner live-checks a real analysis from Games. Stage 4 then continues with the game review view.
