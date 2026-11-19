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

## Re-scoped plan (owner decision, 2026-11-14)

The owner's goal is to go from about 1300 to 2000 rapid on chess.com while also playing on Lichess. `04-training-goals.md` records the goal, the wishes, the tool research, and the decisions. Stages 0 to 4 are unchanged. From Stage 5 on, the plan below replaces the old stages 5 to 12, with the standalone guided course inserted as Stage 6 by owner decision on 2026-11-16. Guided thinking comes before repetition infrastructure, puzzles, and openings. The old stage sections further down are kept as "Maybe later" reference and are not scheduled.

Training follows its standalone prompt and `docs/app-material/DESIGN.md`; future Patterns and Openings views still need prompts. Every new view uses project tokens and CSS Modules in the existing shell; no second visual system is introduced.

### Stage 5 — Chess.com games

**Branch:** `feature/chesscom-games` · **Needs:** Stage 3.

- Owner-entered chess.com username stored under the active account.
- Manual Get Chess.com Games through the public Published-Data API: 1 to 50 finished games, optional before-date, idempotent by source and game ID.
- One catalog with a source filter and badge; ratings and history per site; standard games analysis-eligible.
- Acceptance: re-fetching creates no duplicate; 404 username, 429, malformed data, and network failure are visible and recoverable; no personal data is sent in request headers.

Delivered in three parts (owner approval, 2026-11-16):

1. **Catalog for two sites.** Games and analysis versions gain a `source` (`lichess` or `chesscom`) and are keyed by source and game ID; the catalog accepts the chess.com values it needs. Existing games and analyses carry over unchanged; Lichess behavior does not change. No visible change.
2. **Chess.com retrieval.** The validated username is stored under the active account. One request at a time reads the public monthly archives newest first; games are mapped into the shared catalog with moves read from the PGN through the approved `chessgame` library. Idempotent with new, updated, and unchanged counts. The archives carry no per-game rating change, so that field stays empty for chess.com games.
3. **Interface.** Chess.com username on Admin next to the Lichess connection, a Get Chess.com Games panel on Games, the source filter and badge, per-site ratings and history on the Dashboard, and analysis and review for chess.com games.

No new dependency is expected; if `chessgame` cannot read the PGN, a proposal comes to the owner first.

### Stage 6 — Grandmaster Thinking Method

**Branch:** `feature/guided-training-method` · **Needs:** Stage 4.

- Add Training as a first-class shell destination and a Dashboard shortcut into a standalone **Grandmaster Thinking Method** course dashboard.
- Ship the complete first module, Repeatable Move Routine: four browseable lessons with original explanations and recall checklists. Each lesson has a Foundation (Lichess 1500–1700), Development (1750–1950), and Stretch (2000–2200) exercise curated from the CC0 Lichess puzzle database; the bands are source-puzzle selection bands, not rating equivalence claims.
- Reuse the project-styled board for click, keyboard, and drag moves. A submitted move changes the board immediately and rolls back when the server rejects it; answer revelation never draws an automatic arrow. Right-button drag is the pointer shortcut for annotations, with a labeled Draw arrow mode for keyboard use. Validate every submitted UCI move server-side with `chessgame`; keep solutions and explanations server-side until a correct attempt or explicit reveal.
- Keep course content readable offline. Require the authorized Lichess account for lesson starts, hints, attempts, reveals, completion, and account-scoped durable progress.
- Show later course modules as Planned. Do not personalize from game evidence, add spaced-repetition grading, or claim improvement in this stage.
- Acceptance: anonymous callers can read only sanitized authored content; protected actions reject missing sessions and cross-origin writes; invalid lesson/exercise keys and illegal moves are rejected safely; progress survives restart; all 12 exercises, complete source lines, rating tiers, and Lichess source records pass content validation; the interface remains keyboard-operable and responsive.

#### Stage 6 follow-up — Focused practice board

**Branch:** `feature/training-practice-experience` · **Needs:** Stage 6.

- Show one active exercise board at a time with Previous and Next controls, while keeping its prompt, hints, feedback, and explanation beside the board or below it on narrow screens.
- Use the shared board behavior everywhere: distinguish the origin and destination of the last move, allow multiple right-drag or keyboard-created arrows, and clear user arrows on the next normal left interaction or move.
- Keep the 12 existing exercises and progress contract unchanged. This slice does not create a second library or pre-empt the Puzzle Library schema.
- Acceptance: only one exercise board is present; navigation preserves the lesson order; accepted and revealed moves show their last-move squares without automatic solution arrows; rejected attempts restore the position and previous highlight; multiple annotations stack and clear predictably; the interface remains keyboard-operable and responsive.

#### Stage 6 follow-up — Board exploration

**Branches:** `feature/board-exploration`, `feature/legal-board-exploration` · **Needs:** Focused practice board.

- Keep an answered or revealed position interactive without creating another course attempt or changing saved progress. Alternate the side to move and retain a local position history; scrolling over the board moves backward and forward through that history, and a move made after rewinding starts a new continuation.
- Enforce legal local exploration: piece movement, blockers, captures, promotion, castling and en passant state, and king safety. An illegal move leaves the position and side to move unchanged and gives accessible feedback.
- Let planning arrows start on any square, including an empty one, because they may describe a future line. Accept any straight, diagonal, or knight movement vector without checking occupancy or blockers; reject vectors no chess piece can make. Render knight vectors with a right-angle path and preserve the existing stacking and clearing behavior.
- Acceptance: answered positions never lock; continued moves do not call the course-attempt endpoint; wheel navigation restores every recorded position and last-move highlight; illegal moves never enter history; planning arrows work from empty squares; `b1-c4` is rejected; knight arrows are bent; click, drag, keyboard annotation, orientation, and responsive behavior remain covered.

### Stage 7 — Shared training foundation

**Needs:** Stage 6.

Deliver this stage as two independently reviewed parts:

1. **Puzzle Library and taxonomy — delivered in PR #42.** Branch: `feature/training-core`. Add the central `training`-owned Puzzle, classification, Puzzle Collection, and Exercise-reference schema from ADR-009. Extract the bounded Lichess parser/normalizer from the existing course selector into one explicit local importer. Preserve CC0 provenance and snapshot metadata, expose all current Lichess theme names, keep openings as a separate hierarchy, and migrate the 12 guided-method exercises to stable Puzzle references before adding the full catalog interface.
2. **Repetition core — next implementation part.** Branch: `feature/training-repetition`. Add the shared spaced-repetition scheduler (Again, Hard, Good, Easy derived from the attempt), mastery states New, Learning, Mastered, Lapsed, and reusable account-scoped attempt history for every global-library Puzzle. Wrong moves and reveals map to Again, a solved attempt using a hint maps to Hard, a first-try solve maps to Good, and a sufficiently fast first-try solve maps to Easy.

Before Part 2 implementation begins, record the exact Easy time threshold and the deterministic interval/state-transition table. Those values are product behavior and must not be invented inside implementation code.

- Part 1 acceptance: import is explicit, bounded, deterministic, resumable, and reports its storage result; duplicate provider/external IDs do not create duplicate Puzzles; methods can invoke an exact Puzzle by internal ID without owning it; removing a method does not remove its Puzzle.
- Part 2 acceptance: attempts are scoped to the active account and reusable across methods; removing a method does not remove Puzzle attempt history; outcome mapping and scheduling are deterministic under a fixed clock; mastery and due state survive restart; guided-course completion remains independent from repetition state.

### Stage 8 — My Mistakes deck

**Branch:** `feature/my-mistakes` · **Needs:** Stage 7 Part 2.

- One five-ply drill for every owner mistake and blunder in any completed owner analysis, with alternative-line acceptance.
- Groups by phase, opening, tactic label, and source site; links back to the game review.
- Acceptance: every eligible error yields exactly one drill per analysis version; attempts survive reanalysis.

### Stage 9 — Pattern library

**Branch:** `feature/pattern-library` · **Needs:** Stage 7 Part 2. Themes, rating range, and import through Homebrew `zstd` are decided (owner, 2026-11-14).

- Browse and practice the Stage 7 Puzzle Library through the complete Lichess theme catalog and its separate opening-family/variation hierarchy. Use 50 curated puzzles as the normal collection size, 20 for scarce patterns, and up to 100 for high-value, high-volume patterns; begin with the owner-selected 1200-to-1700 range while the guided method keeps its own progression bands.
- Mastery percentage per deck, Practice weakest, fixed-set repetition sessions, and custom positions.
- Acceptance: collections reference shared Puzzles rather than copying them; named filters and counts match the imported snapshot; random mixed practice is separate from named training; storage size is reported.

### Stage 10 — Opening explorer

**Branch:** `feature/opening-explorer` · **Needs:** Stage 5 and Stage 7 Part 2.

- Move statistics for any position from Lichess Masters, Lichess by rating band and speed, and My games computed from stored games of both sites.
- Acceptance: the Lichess token never reaches the browser; upstream failure and 429 leave My games usable; responses are cached.

### Stage 11 — Repertoire builder

**Branch:** `feature/repertoire-builder` · **Needs:** Stage 10.

- White and Black repertoires starting with the London System and the Caro-Kann Defense; move tree, coverage target, next and biggest gap, transpositions, notes, PGN import and export.
- Acceptance: imported PGN comments become notes; export round-trips; gaps match the coverage rule.

### Stage 12 — Repertoire trainer

**Branch:** `feature/repertoire-trainer` · **Needs:** Stage 11.

- Due repertoire positions drilled with the shared scheduler, popularity-weighted opponent replies, notes after each move, and session statistics.
- Acceptance: a wrong move shows the prepared move and is scheduled as Again; due counts are correct across restarts.

### Stage 13 — Backup and hardening

**Branch:** `feature/product-hardening` · **Needs:** Stages 5 to 12.

- Simple timestamped local backup and guarded restore with a safety backup first.
- The hardening checklist below.

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
- A versioned root `AGENTS.md` contract plus a local-only, Git-excluded harness implementation.

Do not create an application scaffold, dependencies, lockfile, database, runtime data, or feature code.

Acceptance:

- The repository is `/Users/manuelalmeida/dev/chess-tweek` on `feature/project-planning`.
- Git sees only the intended repository baseline files; root `AGENTS.md` is versioned while machine-local harness implementation and raw design exports remain excluded.
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
- Git contains no secrets, runtime data, or machine-local harness implementation files.

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
- Single-game and filtered-group selection for eligible actions.
- Cataloged but analysis-ineligible non-standard variants.
- Current ratings, rating history built from stored rated games, and bot-opponent record on Dashboard.

Acceptance:

- Re-fetching creates no duplicate and older batches can be retrieved.
- Retrieval summaries distinguish new, updated, unchanged, and rejected games exactly.
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

## Maybe later — Persistent bulk analysis queue

**Branch:** `feature/analysis-queue`
**Needs:** Stage 4.

Deliver:

- Select 2–20 games; bulk always analyzes My Moves only.
- Process one game at a time.
- Persist queued, running, paused, completed, failed, and canceled states.
- Pause, Resume, Cancel Current, Cancel Remaining, and Retry Failed.
- Reconcile interrupted work safely after restart.
- Thermal-aware pausing: read macOS thermal pressure without administrator rights. Pause between searches with a visible "Paused: Mac is hot" reason while macOS limits the CPU, and resume on its own when it clears.
- Low-heat mode: an owner-selectable 1-thread analysis mode with its own benchmarked profile, recorded on every analysis version that uses it.

Acceptance:

- Completed queue work survives restart.
- Bulk requests reject opponent and both-side modes.
- Cancel stops Stockfish cleanly without removing completed results.
- Ten-game and twenty-game batches are exercised through the real interface.
- Storage-cap checks run before work and during persistence.
- A simulated thermal limit pauses the queue after the current search and resumes it when cleared, without losing or duplicating work.
- Low-heat mode runs with 1 thread, uses its own profile, and is recorded on the analysis version.

## Maybe later — Overall Games Analysis core

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

## Maybe later — Report-owned Mistake/Blunder Puzzle package (replaced by the Stage 8 My Mistakes deck)

**Branch:** `feature/report-mistake-puzzles`
**Needs:** Stage 7.

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

## Maybe later — Related Lichess Puzzle package

**Branch:** `feature/report-lichess-puzzles`
**Needs:** Stage 7; follow Stage 8 to reuse the proven player.

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

## Maybe later — Normal Daily and Mixed puzzles

**Branch:** `feature/normal-puzzles`
**Needs:** Stage 2 and the puzzle infrastructure proven through Stage 9.

Deliver:

- Independent Lichess Daily and Mixed flows.
- Local attempts and histories separate from report packages.
- Easy, Medium, Hard, and Failed classifications defined in the product specification.
- The same two-level hints as report puzzles.

Acceptance:

- Solutions remain server-side until authorized validation or hint disclosure.
- Normal and report-owned histories stay separate.
- No puzzle-write permission or endpoint is used.

## Maybe later — Admin dependencies and deletion

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

## Maybe later — Storage and database maintenance

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

## Hardening checklist (used by Stage 13)

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

### Delivered

- Stages 0 through 3 are complete, including the owner's live catalog check.
- Stage 4 was delivered in independently reviewed parts through PR #32 on 2026-11-15: Stockfish detection and health, benchmark profile, analysis rules, single-game analysis, game review, and tactic labels with coach cards. The owner dropped the "what I was thinking" note. The 2026-11-16 live check confirmed the Part 6 labels and findings on stored My Moves analyses of games `ye7Uy9js` and `nMp7giWQ`; opponent-side coach wording still has only automated coverage because no stored opponent analysis was available.
- Stage 5 is complete: the two-site catalog foundation (PR #35), chess.com retrieval (PR #36), and the chess.com interface (PR #37) merged on 2026-11-16.
- Stage 6 and its board follow-ups are complete: the guided method (PR #38), layout repair (PR #39), immediate movement, annotations, and the version 2 Lichess exercise ladder (PR #40), focused practice (PR #41), replay repair (PR #43), wheel-history exploration (PR #44), and legal exploration with empty-square planning arrows (PR #45). PR #45 merged on 2026-11-18.
- Stage 7 Part 1 is complete in PR #42. The shared Puzzle Library owns stable puzzle records, taxonomy, opening tags, provenance, bounded importing, and the 12 guided-method exercise references. The owner-approved design is recorded in `06-puzzle-library-taxonomy-and-sizing.md` and ADR-009.

### Not yet delivered

- The real restart/persistence smoke test remains to be recorded.
- Stage 7 Part 2—account-scoped attempts, mastery, grading, and deterministic spaced repetition—has not started.
- The Puzzle Library browsing/practice interface remains Stage 9 work; the Stage 7 foundation is not that interface.

This section is the only record of delivery status. Update it here when a stage or part completes.

**Next immediate action (2026-11-19):**

1. Back up `var/chess-tweek.db`; start the merged app; confirm all 12 guided exercises load; complete one exercise; restart; confirm its progress persists; record the result here.
2. Decide and record the exact Easy threshold and deterministic interval/state-transition table for Stage 7 Part 2.
3. Implement Stage 7 Part 2 on `feature/training-repetition`, verify it, and stop for owner review before beginning My Mistakes or the Pattern Library interface.
