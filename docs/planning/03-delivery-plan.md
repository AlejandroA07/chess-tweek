# Incremental delivery plan

Chess Tweek is delivered as small, reviewable vertical slices. One stage equals one feature branch and one coherent outcome. A later stage does not begin until its prerequisite has been reviewed.

## Gate for every implementation stage

1. Implement one coherent capability.
2. Add unit, integration, architecture, and relevant browser tests with the behavior.
3. Exercise the capability manually through the real interface.
4. Run `node scripts/verify.mjs` after Stage 1 has created and proven it.
5. Inspect the complete diff and update implementation documentation.
6. Commit the verified stage and push the explicit current feature branch to `origin` when publication is requested.
7. Stop for owner review and merge.

Every handoff lists changed files, commands run, manual checks, and known limitations. The agent commits verified work and may push only the explicit current feature branch to `origin`; the owner alone merges.

## Stage 0 — Repository and planning

**Branch:** `feature/project-planning`
**Status:** Ready for owner review after the checks in this document pass.

Deliver only:

- Documentation indexes.
- The decision-complete product specification and decision log.
- Architecture decisions and boundary-test obligations.
- Explicit non-goals and acceptance checks.
- This ordered development backlog.
- Fifteen standalone Google Stitch view prompts and instructions for returning selected designs.
- A local-only, Git-excluded harness skeleton.

Do not create an application scaffold, dependencies, lockfile, database, runtime data, or feature code.

Acceptance:

- The repository is `/Users/manuelalmeida/dev/chess-tweek` on `feature/project-planning`.
- Git sees only the intended repository baseline files; local harness and raw design-export files remain excluded.
- Every documentation area has a `00-README.md` index.
- The prompt file contains all 15 planned desktop views with visible controls, interactions, and states.
- `docs/app-material/DESIGN.md` records the approved canonical direction and implementation contract.
- The local secret scan and Git publish guard are active and have been observed blocking test inputs.
- Work stops until the owner reviews the repository baseline and approved design direction.

## Stage 1 — Design and application foundation

**Branch:** `feature/app-foundation`
**Needs:** Stage 0 and the approved canonical design direction in `docs/app-material/DESIGN.md`.

Deliver:

- Encode `docs/app-material/DESIGN.md` as the application's only visual system and verify it against the approved reference screens.
- Scaffold Java 25, Spring Boot 4.1, the Maven Wrapper, React 19, strict TypeScript, Vite 8, Node 24 LTS, and pnpm.
- Serve the built React interface and local API from one Spring Boot process bound to `127.0.0.1`.
- Use CSS Modules and design tokens without a component framework.
- Complete the reusable project harness and committed security/quality baseline.
- Configure Java and TypeScript linting/formatting, JUnit, Spring Boot Test, MockMvc, Vitest, Playwright, ArchUnit, dependency audit, secret scanning, CodeQL, CI, and `scripts/verify.mjs`.
- Create the shared desktop shell and empty Dashboard, Games, Reports, Puzzles, and Admin routes.
- Create SQLite JDBC/Flyway migration infrastructure and the ignored `var/` runtime directory.

Acceptance:

- The shell matches the approved designs.
- Every empty route renders correctly and has an intentional state.
- Architecture tests enforce the approved module boundaries.
- `node scripts/verify.mjs` succeeds from a clean locked install.
- Git contains no secrets, runtime data, or local harness files.

## Stage 2 — Lichess connection

**Branch:** `feature/lichess-connection`
**Needs:** Stage 1.

Deliver:

- OAuth2 Authorization Code with PKCE using `S256`.
- Only identity and read-only puzzle permissions.
- A sealed Secure, HttpOnly, SameSite cookie for the token; never SQLite or backups.
- A server bound to `127.0.0.1`.
- Connected account, ratings by speed, connection failures, reconnect, and logout.

Acceptance:

- New and returning authorization work.
- Invalid state, cancellation, expired authorization code, revoked token, and upstream unavailability are handled safely.
- Tokens never appear in client JavaScript, logs, SQLite, Git, or backups.
- Logged-out requests cannot access account-owned data.

## Stage 3 — Get Lichess Games and catalog

**Branch:** `feature/lichess-game-catalog`
**Needs:** Stage 2.

Deliver:

- Manual Get Lichess Games for 1–50 finished games with an optional before-date.
- Moves, clocks, result, ratings, opponent, owner color, speed, opening ECO/name, variant, and available phase divisions.
- Idempotent storage keyed by Lichess game ID.
- The approved library and filters for result, color, opening, opponent type, speed, date, variant, and analysis status.
- Cataloged but analysis-ineligible non-standard variants.
- Current ratings and bot-opponent record on Dashboard.

Acceptance:

- Re-fetching creates no duplicate and older batches can be retrieved.
- Lichess 401, 429, malformed NDJSON, oversized payload, invalid game, and network failures are visible and recoverable.
- Retrieval never starts analysis.
- The catalog survives an application restart.

## Stage 4 — Single-game Stockfish analysis

**Branch:** `feature/single-game-analysis`
**Needs:** Stage 3.

Deliver:

- Detect the official Homebrew Apple Silicon Stockfish binary.
- Benchmark the owner's M4 and persist a reproducible fixed-node profile.
- Analyze My Moves, Opponent Moves, or Both for one game.
- Default to two threads, 256 MB hash, `MultiPV=3`, WDL output, and two passes.
- Store owner and opponent findings separately and immutably.
- Build game review with board, move list, phases/opening, statistics, every error position, principal variations, and deterministic follow-ups.

Acceptance:

- One-side analysis targets about 1–2 minutes and both sides about 2–4 minutes on the measured profile.
- Missing or crashed Stockfish yields a recoverable state.
- Reanalysis creates a new version without mutating old evidence.
- Owner and opponent findings are never combined.

## Stage 5 — Persistent bulk analysis queue

**Branch:** `feature/analysis-queue`
**Needs:** Stage 4.

Deliver:

- Select 2–20 games; bulk always analyzes My Moves only.
- Process one game at a time.
- Persist queued, running, paused, completed, failed, and canceled states.
- Pause, Resume, Cancel Current, Cancel Remaining, and Retry Failed.
- Reconcile interrupted work safely after restart.

Acceptance:

- Completed queue work survives restart.
- Bulk requests reject opponent and both-side modes.
- Cancel stops Stockfish cleanly without removing completed results.
- Ten-game and twenty-game batches are exercised through the real interface.
- Storage-cap checks run before work and during persistence.

## Stage 6 — Overall Games Analysis core

**Branch:** `feature/overall-games-analysis`
**Needs:** Stage 5.

Deliver:

- Build a report from exactly 10 distinct games with completed owner analysis.
- Filter candidates by result, color, opening, opponent type, speed, and date.
- Save an immutable report tied to exact analysis versions.
- Include every owner inaccuracy, mistake, and blunder; normalized phase, opening, and supported-theme rates; rating and bot context; and every exact error position.
- Allow one game in multiple reports and exclude opponent analysis.
- Enforce restrictive game/report references.

Acceptance:

- Any selection other than 10 eligible games is rejected.
- Reanalysis never changes an existing report.
- Reports reopen after restart.
- Deleting a referenced game is blocked with every blocking report returned.
- All report queries are account-scoped.

## Stage 7 — Mistake/Blunder Puzzle package

**Branch:** `feature/report-mistake-puzzles`
**Needs:** Stage 6.

Deliver:

- One report-owned exercise for every owner mistake and blunder.
- Start immediately before the error and play five plies: owner, opponent, owner, opponent, owner.
- Precompute up to three lines.
- Accept alternatives within three expected-score percentage points of the best move.
- Track attempts, hints, solved/failed state, and local difficulty.

Acceptance:

- Every eligible report error creates one reproducible exercise.
- Close alternatives are accepted and inferior moves rejected.
- Hint 1 identifies the piece and Hint 2 identifies the destination.
- Deleting the report deletes this package and its attempts.

## Stage 8 — Related Lichess Puzzle package

**Branch:** `feature/report-lichess-puzzles`
**Needs:** Stage 6; follow Stage 7 to reuse the proven player.

Deliver:

- A second, separately owned package record for every report.
- Theme scoring of three points per blunder and two per mistake.
- Up to four safely mapped themes and at most 20 proportionally allocated puzzles.
- Deduplication against every locally stored Lichess puzzle ID.
- Fewer puzzles when related unique material is unavailable, never unrelated filler.
- Isolated retrieval failure with Retry.

Acceptance:

- The two report package types remain distinct.
- Only owner-report evidence affects selection and the package never exceeds 20.
- Retrieval failure does not roll back or hide the report.
- Results remain local and are never submitted to Lichess.
- Report deletion cascades the package and attempts.

## Stage 9 — Normal Daily and Mixed puzzles

**Branch:** `feature/normal-puzzles`
**Needs:** Stage 2 and the puzzle infrastructure proven through Stage 8.

Deliver:

- Independent Lichess Daily and Mixed flows.
- Local attempts and histories separate from report packages.
- Easy, Medium, Hard, and Failed classifications defined in the product specification.
- The same two-level hints as report puzzles.

Acceptance:

- Solutions remain server-side until authorized validation or hint disclosure.
- Normal and report-owned histories stay separate.
- No puzzle-write permission or endpoint is used.

## Stage 10 — Admin dependencies and deletion

**Branch:** `feature/admin-data-management`
**Needs:** Stages 6–9.

Deliver:

- The approved side-by-side game/report dependency view.
- Every report blocking a selected game deletion.
- Guarded choices to delete a report while retaining source games or atomically delete the report-owned data and selected games.
- Management for analysis versions, failed jobs, reports, and puzzle histories.

Acceptance:

- Restrictive references cannot be bypassed.
- Every destructive action names its exact targets and retained records.
- Any partial failure rolls back the transaction.
- Unrelated data remains untouched.

## Stage 11 — Storage and database maintenance

**Branch:** `feature/database-maintenance`
**Needs:** Stage 10.

Deliver:

- A configurable default 5 GB database-plus-journal cap.
- Warnings at 80% and 90%.
- Stop new persistence at 100% or before free disk falls below 10 GB.
- Timestamped backup, guarded restore, mandatory pre-restore safety backup, integrity checks, and SQLite compaction.

Acceptance:

- Cap and disk-reserve behavior are tested with disposable storage.
- Nothing is silently deleted.
- Restore always creates a safety backup first.
- A failed restore leaves the original database usable.
- Compaction demonstrably reclaims deleted space.

## Stage 12 — Final product hardening

**Branch:** `feature/product-hardening`
**Needs:** Every prior stage.

Deliver:

- Security review of every endpoint and imported payload.
- Complete loading, empty, disabled, retry, and error behavior.
- Accessibility and keyboard navigation.
- Desktop visual comparison against every approved Stitch design.
- Cold-start and measured performance checks.
- Operating documentation and a complete real Lichess/native Stockfish drive.

Acceptance:

- `node scripts/verify.mjs`, CodeQL, dependency audit, secret scan, and workflow hardening are green.
- No external or paid AI call exists.
- No live-game or in-app-play capability exists.
- Every promised view and Admin control is manually exercised.

## Current stopping point

The Stage 0 planning baseline and approved canonical design direction are ready for owner review. After that review, Stage 1 begins on `feature/app-foundation`. No product interface or application scaffold begins before the review.
