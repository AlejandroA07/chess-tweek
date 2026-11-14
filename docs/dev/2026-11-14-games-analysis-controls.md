# Games analysis controls

Stage 4 Part 4b adds analysis controls to the Games library: the owner can start a Stockfish analysis from a game card, watch it run, cancel it, and see each side's stored result on the card.

## Cards

Every analysis-eligible card shows one status line per side:

- **Not analyzed**
- **Analyzing…** while the running analysis covers that side
- **Analyzed**, with the newest stored version's counts, for example `2 Blunders · 1 Mistake · 3 Inaccuracies`
- "Earlier engine profile · reanalyze to update" under an analyzed side whose version used an older benchmark profile than the newest one
- "Last analysis failed. Nothing was saved." on each side of the card that a failed analysis this session covered

Actions:

- **Analyze My Moves** is the primary card action. It reads **Reanalyze My Moves** once that side has a stored version.
- **More** is a disclosure button (`aria-expanded`, and `aria-controls` while open). It reveals **Analyze Opponent Moves** (or **Reanalyze Opponent Moves**) and **Analyze Both Sides** (or **Reanalyze Both Sides** once both sides have versions). Escape closes it and returns focus to More.
- While an analysis is starting or running, every card's actions are disabled, with the reason "Available when the current analysis finishes." next to them.

Catalog-only games keep "Catalog only — analysis unavailable" and have no analysis actions.

## Progress panel

The panel sits above the game count and is labelled "Game analysis".

| State | Shows |
|---|---|
| Starting | "Starting analysis…" while the request is sent |
| Running | "Analyzing your moves against Rival" (or the opponent's moves, or both sides), the current step, separate First pass and Second pass bars (so progress never appears to go backwards), elapsed time, a note that local analysis can warm the Mac, and **Cancel Analysis** |
| Completed | "Analysis saved" with the time taken; the cards refresh |
| Canceled | "Nothing was saved", with **Analyze Again** |
| Failed | A plain reason and next step, **Try Again**, and **Open Admin** when the fix is on Admin |
| Refused start | Why it did not start (no benchmark, unsupported or missing game), with **Open Admin** when relevant. When the refusal is "another analysis is already running", that analysis is fetched and shown below the refusal so it can be followed or canceled |

The current step reads:

- "Starting Stockfish…"
- "First pass · 24 of 41 moves" (with "(both sides)" when both sides are analyzed)
- "Second pass · checking 3 of 5 moves more deeply"
- "Saving results…"

The visible step text is not a live region, so screen readers are not interrupted every second. A visually hidden polite live region stays mounted and announces only changes of pass and the outcome. Failures and refused starts use `role="alert"`.

## Behavior

- On opening Games, the library asks for the current analysis once. A running analysis is picked up and followed. A finished one from earlier is not shown again.
- While an analysis runs, the library polls `GET /api/analysis` once a second. Polling stops when the analysis ends or the view closes. A missed poll is retried on the next tick.
- When an analysis completes, every loaded catalog page is fetched again quietly, without the loading skeleton, and matching cards are updated in place. Cards from Load older stay where they are. A filter change or leaving the view aborts this refresh.
- Cancel shows "Stopping…" until the analysis has ended. Starting another analysis always clears it.

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

`status` is one of `not_analyzed`, `analyzed`, or `unsupported_variant`. Each version also carries `currentProfile`, which is false when a newer benchmark profile exists. `GameAnalysisSummaries` answers for at most the 50 games on one page with one parameterized, account-scoped query. Game ids and the account id are validated before querying.

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
- refreshing a card from an older page in place
- the More disclosure and keyboard close
- Both sides
- engine failure and retry, and the card's failure line
- a refused start, and a busy refusal that shows the running analysis
- cancel, and analyzing again after a cancel
- the earlier-profile marker
- ignoring an earlier finished analysis
- no actions on catalog-only games

Backend tests cover the catalog summaries and the newest-version query across games, both sides, and a newer profile.

## Security review

| Check | Verdict |
|---|---|
| New endpoints | None. The catalog response gains summaries for the caller's own games only. |
| Data access | One constant parameterized query, scoped to the account; at most 50 validated ids. |
| Client trust | Every response field is validated before use; React renders all text, and no HTML is injected. |
| Mutations | Start and cancel use the existing same-origin, session-protected endpoints. |

## Next

The owner live-checks a real analysis from Games. Stage 4 then continues with the game review view.
