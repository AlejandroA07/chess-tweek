# Chess Tweek product specification

## Goal

Chess Tweek is a local-first desktop chess training application for one active Lichess account. It imports completed Lichess games, optionally analyzes selected sides with local Stockfish, summarizes the owner's patterns across chosen games, and turns those findings into practice material. The supported platform is macOS on Apple Silicon.

The first success milestone is not the whole product. It is one verified vertical slice at a time, beginning only after the owner supplies approved Google Stitch designs.

## Load-bearing qualities

1. **Local-first data ownership:** game history, engine findings, reports, attempts, and backups remain on the owner's computer.
2. **Evidence before coaching:** every claim must trace to Lichess data, a versioned Stockfish result, or a deterministic rule. No paid or generative AI is required.

## Decision log

### Product and operating model

- The project root is `/Users/manuelalmeida/dev/chess-tweek`.
- The product is a local web application and will not be deployed in v1.
- macOS on Apple Silicon is the only supported platform.
- One Lichess account is active at a time. All persisted records remain scoped by the Lichess account ID.
- The owner plays games on Lichess, not inside Chess Tweek.
- There is no live-game monitoring, engine assistance, or Board API integration.
- Expensive or destructive work is always manually initiated.
- Work is delivered as small feature branches. The full gate and a real manual drive are required before handoff.

Rejected alternatives:

- Public hosting was rejected because it adds operations and cost before the local product is proven.
- In-app Lichess play was rejected because it materially increases scope and creates an unnecessary fair-play boundary.
- A full platform, social features, and multiplayer infrastructure were rejected as unrelated to the training goal.

### Technology

- Stage 1 will use Java 25, Spring Boot 4.1, and the Maven Wrapper for the backend and application runtime.
- The interface will use React 19, strict TypeScript, Vite 8, Node 24 LTS, and pnpm. Node is a build tool, not the application server.
- Spring Boot will serve the built interface and local API from one process bound to `127.0.0.1`.
- The UI will use CSS Modules and project design tokens rather than a component framework.
- SQLite is the local database, accessed through the SQLite JDBC driver and parameterized Spring JDBC operations. Flyway owns schema migrations.
- Chess-rule, PGN/FEN, and board packages are deferred until the first feature that needs them and require owner approval before addition.
- Native Stockfish is controlled through UCI by a Java `ProcessBuilder` adapter. The Apple Silicon binary is installed through Homebrew and discovered from the standard macOS location or an explicit local configuration.
- Stockfish is a system prerequisite, not copied into the repository.
- The project is a modular monolith. Details and reopening conditions are in `02-architecture.md`.

Rejected alternatives:

- Separate frontend and API deployments were rejected because the local product needs one process and one origin.
- PostgreSQL and Docker were rejected because a single-user local application does not need a database service.
- Browser Stockfish WASM was rejected because a native process offers better local performance and persistent queue control.
- Tailwind and a component library were rejected until the approved design demonstrates a real need.

### Lichess authentication and permissions

- Use OAuth2 Authorization Code with PKCE and `S256`.
- Request account identity and read-only puzzle access only.
- Never request `board:play`, bot-play, puzzle-write, messaging, or other write scopes.
- Keep the Lichess access token out of SQLite, backups, logs, and browser JavaScript.
- Store the token in a sealed Secure, HttpOnly, SameSite cookie backed by a generated local key with `0600` permissions.
- Bind the application server to `127.0.0.1`.
- Logout revokes the Lichess token when possible and always clears the local session.

Failure behavior:

- Invalid OAuth state, canceled authorization, expired code, revoked token, and upstream unavailability produce safe, actionable UI states.
- Logged-out callers cannot read or mutate account-owned resources.
- Every mutating endpoint validates Origin/CSRF state and all boundary input.

### Getting Lichess games

- The user-facing action is named **Get Lichess Games**, not Sync.
- It is never automatic.
- One request accepts a count from 1 through 50 and an optional before-date for older batches.
- Fetch finished games only. Ongoing or aborted/no-start games are excluded.
- Store game ID, played date, moves/PGN, clocks when available, result, owner color and rating, opponent identity/rating/title, speed, time control, rated/casual state, variant, opening ECO/name, and Lichess phase divisions when available.
- Requests are idempotent. A repeated Lichess game ID updates safe metadata and never creates a duplicate.
- All variants can appear in the catalog. Only standard chess is eligible for v1 Stockfish analysis.
- No analysis starts after retrieval.

Catalog behavior:

- Show responsive game cards with win/loss/draw, color, opening, opponent type, speed, date, variant, and analysis status.
- Filter by result, color, opening, opponent type, speed, date, variant, and analysis state.
- Support selecting one game or a filtered group for eligible actions.
- Display Lichess's own speed label. A five-minute game may therefore appear as Blitz.

Failure behavior:

- Only one Lichess request runs at a time.
- A 429 response honors `Retry-After` when provided; otherwise wait at least 60 seconds before a retry.
- Malformed NDJSON, oversized PGN, unknown enums, and invalid moves are rejected per game without corrupting already accepted games.
- Network failure leaves the existing catalog untouched and offers Retry.

### Rating and bot context

- Show current and historical Lichess ratings by speed using Lichess data.
- Current ratings come from the connected Lichess account. Rating history and the most recent change are built from stored rated standard games (each game's rating plus its Lichess rating change), not from a separate Lichess rating-history request. Owner decision, 2026-11-13.
- Do not invent a Chess Tweek Elo.
- Show win/draw/loss against Lichess AI and accounts titled `BOT`, grouped by displayed opponent rating.
- Casual bot games can contribute to bot records even when they do not alter an official rating.

### Stockfish analysis

- Analysis happens only after a finished game is stored and the owner requests it.
- Single-game analysis offers three perspectives: My moves, Opponent moves, or Both.
- Bulk analysis accepts 2 through 20 games and always analyzes My moves only.
- Only one game is processed at a time.
- Default engine settings are two threads, 256 MB hash, `MultiPV=3`, and WDL output.
- A benchmark on the owner's M4 derives fixed-node quick and deep budgets. The engine version, settings, benchmark, and node budgets are stored with every analysis version.
- First pass evaluates every move made by the requested side. A second pass deepens any position with at least five percentage points of expected-score loss or a forced-mate state change.
- Target duration is approximately one to two minutes for one side of a normal game and two to four minutes for both sides. The UI reports measured progress rather than promising an exact duration.

Expected-score calculation:

- Convert Stockfish WDL to expected score from the analyzed player's perspective: win probability plus half the draw probability.
- Compare expected score before and after the played move.
- Clamp negative loss to zero; engine noise must not create a fictitious improvement penalty.

Move labels:

- Inaccuracy: 8 through 17 percentage points lost.
- Mistake: 18 through 34 percentage points lost.
- Blunder: 35 or more percentage points lost.
- Losing a forced mate or allowing a forced mate is a blunder even when the numeric threshold is lower.
- Store centipawn/mate evaluation alongside WDL for explanation, but classification uses expected-score loss.

Game phases:

- Prefer Lichess's supplied opening/middlegame/endgame division.
- When missing, classify opening through ply 20.
- Start the inferred endgame after both queens are gone and combined non-pawn material is at most 20 standard material points.
- Everything between is middlegame.
- Record whether a phase is supplied or inferred.

Conservative tactic labels:

- Fork.
- Hanging piece.
- Missed forced mate.
- Allowed forced mate.
- Back-rank issue.
- General tactics when no narrower label is proven.

Do not guess pins, skewers, discoveries, sacrifices, or other motifs without a tested deterministic detector.

Versioning and queue behavior:

- Store owner and opponent findings separately.
- Reanalysis creates a new immutable analysis version.
- A game has a current analysis version per perspective and profile, while reports retain exact older versions.
- Queue states are queued, running, paused, completed, failed, and canceled.
- Provide Pause, Resume, Cancel Current, Cancel Remaining, and Retry Failed.
- Thermal-aware pausing (owner decision, 2026-11-14): the owner's Mac is a fanless MacBook Air, so heat slows the chip rather than risking damage. Before each queued game and between searches, the queue reads macOS thermal pressure, which is available without administrator rights. When macOS reports that it is limiting the CPU, the queue finishes the current search, pauses with a visible "Paused: Mac is hot" reason, and resumes on its own once the limit clears. The owner can still resume manually. Exact temperatures are not read.
- Low-heat mode (owner decision, 2026-11-14): an owner-selectable mode analyzes with 1 thread instead of 2, roughly halving heat at about twice the duration. It uses its own benchmarked profile, so its node budgets and every analysis version it produces stay reproducible and record that they used low-heat mode.
- On restart, reconcile a previously running item into a safe resumable state.
- Persist completed position work transactionally so a crash cannot manufacture a completed analysis.

### Individual game review

- Orient the board to the analyzed side and allow manual board flip.
- Show the move list, evaluation progression, opening identity, phase boundaries, and selected-side statistics.
- When Both is available, show separate My Moves and Opponent Moves tabs. Never combine their counts.
- Show every classified position with the exact pre-move FEN, played move, best move, before/after evaluation, expected-score loss, severity, phase, verified theme, and principal variations.
- Coach cards are deterministic and offer preset follow-ups: Why?, What was the threat?, and What should I train?
- No free-text imitation and no LLM calls.

### Overall Games Analysis

- The UI name is **Overall Games Analysis**.
- Generation is always manual.
- A report requires exactly 10 distinct games with a completed owner-move analysis.
- Opponent-only findings never make a game report-eligible.
- The same game may appear in more than one report.
- Store an immutable report snapshot tied to the exact owner analysis versions, filters, engine profiles, and creation time.
- There is no separate improvement report and no automatic 50-game trigger.

Report content:

- Every owner inaccuracy, mistake, and blunder.
- Raw totals and rates per game and per 100 analyzed owner moves.
- Opening, middlegame, and endgame totals and rates.
- ECO, exact opening, and opening-family groupings.
- Conservative tactic groupings.
- Rating and bot-opponent context for the selected games.
- Every error position with its best move and continuation.
- Deterministic training priorities ranked by severity, frequency, and sample size.
- Clear sample size and selection/filter summary.

### Report package 1: Mistake/Blunder Puzzles

- Every report owns one Mistake/Blunder Puzzles package.
- Include one exercise for every owner mistake and blunder in the report; inaccuracies do not create exercises.
- Start immediately before the error and ask the owner to replace it with a better line.
- Each exercise is five plies: owner, opponent, owner, opponent, owner.
- Precompute up to three candidate lines.
- Accept an alternative first move when its expected score is within three percentage points of the best move and continue its stored line when available.
- Record attempts, incorrect moves, both hint levels, give-up, solved state, and local difficulty.

### Report package 2: Related Lichess Puzzles

- Every report owns a separate Related Lichess Puzzles package containing at most 20 normal Lichess puzzles.
- Use owner mistakes and blunders only.
- Score themes with three points per blunder and two points per mistake.
- Select up to four supported themes, ordered by score, frequency, then stable theme name.
- Give each selected theme at least one slot, then distribute remaining slots proportionally by score using largest remainders and stable tie-breaking.
- Map only verified report themes/opening identifiers to official Lichess puzzle angles.
- Deduplicate against all puzzle IDs already stored locally.
- Never fill with an unrelated `mix` puzzle. Returning fewer than 20 is correct.
- If no safe mapping exists, keep an empty package and explain why.
- If Lichess retrieval fails, preserve the report, mark this package Pending or Failed, and provide Retry.
- Puzzle results remain local and are never submitted to Lichess.

### Normal Lichess puzzles

- Daily and Mixed are independent from report-owned packages.
- Fetch with read-only puzzle permission.
- Keep puzzle solutions server-side until needed for validation or an authorized hint.
- Store attempts locally and never update the Lichess puzzle rating.

Difficulty classification for all interactive puzzles:

- Easy: solved on the first attempt with no hint.
- Medium: solved after one incorrect attempt with no hint.
- Hard: solved after at least two incorrect attempts or after either hint.
- Failed: the owner gives up.

Hints:

- Hint 1 highlights the piece to move.
- Hint 2 reveals the destination square.

### Report and game deletion

- Reports retain restrictive references to their exact game and analysis versions.
- A referenced game or analysis version cannot be deleted.
- A blocked deletion returns every blocking report ID and title.
- Admin shows selected games on the left and blocking reports on the right.
- Allow deleting only a report, deleting a report with both packages, or atomically deleting the report/packages and then selected games after explicit confirmation.
- Deleting a report cascades both packages and their attempts.
- Partial destructive operations roll back completely.

### Admin and local operations

Admin exposes every project operation that exists in v1:

- Lichess connection and account status.
- Current ratings and last successful game retrieval.
- Get Lichess Games controls.
- Stockfish path, version, benchmark, and health test.
- Analysis queue controls and failed-job retry.
- Game/analysis version management.
- Report creation, list, open, and dependency-aware deletion.
- Normal and report-owned puzzle history management.
- Database size, journal size, available disk, and configured cap.
- Backup, guarded restore, and compaction.
- Safe diagnostics without tokens, raw cookies, secrets, or internal stack traces.

### Storage and maintenance

- Store the database, local secret material, and backups under ignored `var/` paths.
- Default combined SQLite/database-journal cap is 5 GB.
- Warn at 80 percent and 90 percent.
- Stop new persistence at 100 percent or before free disk would fall below 10 GB.
- Never auto-delete data.
- A restore always creates a pre-restore safety backup.
- Restore failure must leave the original database usable.
- Compaction is manual and reports the reclaimed size.

### Visual design

- The owner approved the supplied hero/Grandmaster Studio direction as the canonical visual language on 2026-07-22.
- The prompt source is `docs/app-material/view-prompts.md`.
- Initial designs are desktop-only at approximately 1440 px.
- Shared tone: calm analysis studio, mahogany/espresso brown, warm maple beige, and white/off-white.
- Use the hero's subtle light chessboard-square pattern behind the main workspace on every view. Keep cards, forms, tables, sidebars, and chessboards on solid surfaces for readability.
- Use Playfair Display for restrained headings, Manrope for interface and body copy, and JetBrains Mono for notation and technical data.
- Every screen must specify loading, empty, error, disabled, selected, and destructive-confirmation states where applicable.
- The approved system is recorded in `docs/app-material/DESIGN.md`; Stage 1 must encode it as design tokens before styling screens.

## Out of scope

- Deployment, hosting, or a public production environment.
- Multi-user operation or team administration.
- Paid, external, or local LLM coaching.
- Free-text chatbot behavior.
- In-app Lichess play, Board API, bot-play API, live-game monitoring, or live assistance.
- Automatic game retrieval, automatic analysis, and automatic report generation.
- Puzzle result write-back to Lichess.
- Chess.com integration.
- Analysis of variants other than standard chess.
- Opponent findings in multi-game reports or report-owned packages.
- A separate improvement report or a Chess Tweek-created Elo.
- Mobile-specific Stitch designs in the initial design pass.

## Acceptance checks

### Stage 0

- `git branch --show-current` prints `feature/project-planning`.
- `git status --short` shows only the intended repository baseline files; the root `AGENTS.md` guide is versioned while machine-local harness implementation and raw design exports are excluded.
- No `package.json`, application source, dependency lockfile, database, or runtime data exists.
- Every planning folder has a `00-README.md` index.
- The product specification contains a decision log, out-of-scope list, observable acceptance checks, and ordered checkbox slices.
- `docs/app-material/view-prompts.md` contains 15 standalone desktop prompts with controls and states.
- `docs/app-material/DESIGN.md` records the approved canonical direction and implementation contract.

### Future product gate

- `node scripts/verify.mjs` is the only definition of done after Stage 1 and runs locked backend and frontend dependency checks, Java and TypeScript compilation, lint and format checks, JUnit/Spring tests, Vitest tests, ArchUnit architecture tests, the production build, and Playwright tests.
- OAuth tokens never appear in Git, SQLite, logs, client JavaScript, or backups.
- Getting 50 games is bounded, idempotent, and safe across Lichess 401/429 and malformed-stream failures.
- Single analysis supports owner, opponent, and both; bulk rejects opponent/both.
- Queue progress survives restart and cancellation stops Stockfish safely.
- Reports reject any selection other than 10 eligible owner analyses and remain immutable after reanalysis.
- Opponent findings never enter reports or report packages.
- Every report owns exactly two package records.
- Mistake/Blunder exercises reproduce the original position and validate a five-ply line.
- Related Lichess packages contain no more than 20 unique, safely mapped puzzles and no unrelated filler.
- A referenced game deletion returns every blocking report.
- Backup/restore is proven against a disposable database and always creates a safety backup.
- All approved desktop screens pass keyboard, contrast, focus, empty/loading/error-state, and long-content checks.

## Slices

- [x] **0. Repository, planning, and design selection** — create the repository baseline, local harness skeleton, documentation model, decision-complete specification, architecture record, 15 Stitch prompts, and approved canonical design direction. Stop for owner review.
- [x] **1. Application foundation** — implement the approved `DESIGN.md`, scaffold the verified Spring Boot/React/SQLite application and shared shell, complete the harness, CI, architecture tests, and `scripts/verify.mjs`. Needs 0 and the approved canonical design direction.
- [x] **2. Lichess connection** — deliver PKCE login, secure local session, account/rating display, reconnect, and logout end to end. Needs 1.
- [x] **3. Lichess game catalog** — deliver Get Lichess Games, durable idempotent catalog, filters/cards, and bot/rating context. Needs 2.
- [ ] **4. Single-game analysis** — deliver Stockfish benchmark, selectable perspective, versioned findings, individual review, and coach cards. Needs 3.
- [ ] **5. Bulk analysis queue** — deliver owner-only 2–20 game jobs, persistent states, pause/resume/cancel/retry, and restart recovery. Needs 4.
- [ ] **6. Overall Games Analysis** — deliver exact-ten report builder, immutable owner-only aggregation, saved report library/detail, and deletion restrictions. Needs 5.
- [ ] **7. Mistake/Blunder Puzzles** — deliver report-owned five-ply exercises, alternative-line validation, hints, and attempts. Needs 6.
- [ ] **8. Related Lichess Puzzles** — deliver weighted mapping, at-most-20 related puzzles, deduplication, failure isolation, and Retry. Needs 6.
- [ ] **9. Normal puzzles** — deliver independent Daily/Mixed practice and local history. Needs 2; follow 8 to reuse proven puzzle infrastructure.
- [ ] **10. Admin dependency management** — deliver side-by-side references and guarded transactional deletion. Needs 6–9.
- [ ] **11. Database maintenance** — deliver 5 GB cap, disk reserve, backup, restore, and compaction. Needs 10.
- [ ] **12. Product hardening** — complete security, accessibility, visual QA, real Lichess/Stockfish drive, operations docs, and full verification. Needs all prior slices.

## Branch and handoff rules

- One slice equals one `feature/<topic>` branch and one coherent outcome.
- Do not start a slice until its prerequisite is reviewed.
- Tests ship with behavior, never in a later cleanup branch.
- Run `node scripts/verify.mjs` before every handoff after Stage 1.
- Handoffs list changed files, commands run, manual checks, known limitations, and the created commit.
- After verification, the agent commits coherent work and may push only the explicit current feature branch to `origin` when publication is requested. The owner alone merges.
