# Development documentation

Delivery status and the next step live in [`../planning/03-delivery-plan.md`](../planning/03-delivery-plan.md) under "Current stopping point".

The database is a local `var/chess-tweek.db` file with Flyway migrations, WAL journaling, foreign keys, normal synchronization, a five-second busy timeout, and at most two pooled connections. Run `node scripts/verify.mjs` for the complete gate.

The interface now uses the canonical design tokens and shared shell at desktop and compact widths. Training is the sixth first-class destination and the Dashboard links to its guided course; remaining unimplemented destinations keep their empty first-use explanations without exposing future actions.

| File | What it contains | Kind |
|---|---|---|
| `00-README.md` | Current implementation-documentation state and index. | living topic doc |
| `2026-07-22-macos-target.md` | Records the macOS-only product target and Stockfish integration decision. | dated report |
| `2026-07-22-canonical-design.md` | Records the canonical visual direction and design-system gate. | dated report |
| `2026-11-09-database-foundation.md` | Records the SQLite, migration, architecture-test, and resource decisions. | dated report |
| `2026-11-10-stage1-interface-foundation.md` | Records the completed responsive interface and verification evidence. | dated report |
| `2026-11-10-lichess-oauth-start.md` | Records the first Stage 2 OAuth boundary and its deferred work. | dated report |
| `2026-11-10-lichess-oauth-callback.md` | Records callback validation, token exchange, and sealed cookie storage. | dated report |
| `2026-11-10-lichess-session.md` | Records sealed-session validation, account status, and logout. | dated report |
| `2026-11-10-lichess-connection-ui.md` | Records account UI, safe callback messages, and manual OAuth-start evidence. | dated report |
| `2026-11-11-lichess-game-retrieval.md` | Records the protected, bounded Stage 3 Part 1 retrieval boundary. | dated report |
| `2026-11-11-game-catalog-storage.md` | Records the durable, validated Stage 3 Part 2 game catalog. | dated report |
| `2026-11-11-game-catalog-interface.md` | Records the authorized Stage 3 Part 3 catalog API and card interface. | dated report |
| `2026-11-11-game-retrieval-controls.md` | Records the manual Stage 3 Part 4 retrieval panel and catalog refresh. | dated report |
| `2026-11-11-game-catalog-filter-foundation.md` | Records the server-backed result, color, and sort filter foundation. | dated report |
| `2026-11-13-game-catalog-remaining-filters.md` | Records the remaining server-backed catalog filters and validation. | dated report |
| `2026-11-13-game-catalog-selection.md` | Records eligible single-game and exact filtered-group selection. | dated report |
| `2026-11-13-game-retrieval-write-counts.md` | Records exact new, updated, and unchanged retrieval accounting. | dated report |
| `2026-11-13-game-rating-change.md` | Records the owner's validated per-game Lichess rating change. | dated report |
| `2026-11-13-dashboard-rating-context.md` | Records the Dashboard rating history and bot record built from stored games. | dated report |
| `2026-11-13-no-stale-app-page.md` | Records why the browser kept an old page and the caching fix. | dated report |
| `2026-11-13-stockfish-health.md` | Records Stockfish detection, the bounded UCI health check, and Admin engine status. | dated report |
| `2026-11-14-engine-benchmark-profile.md` | Records the benchmark, node budgets, and stored analysis profile. | dated report |
| `2026-11-14-analysis-rules.md` | Records expected score, move labels, forced-mate rules, the second pass, and game phases. | dated report |
| `2026-11-14-single-game-analysis.md` | Records background single-game analysis, Stockfish output parsing, immutable versions, and the analysis API. | dated report |
| `2026-11-14-games-analysis-controls.md` | Records Games card analysis controls, the progress panel, and per-side status badges. | dated report |
| `2026-11-14-game-review-data.md` | Records the game review endpoint, statistics, evaluations, and readable engine lines. | dated report |
| `2026-11-15-tactic-labels-coach-cards.md` | Records the proven tactic labels and the Why?, threat, and training coach cards. | dated report |
| `2026-11-14-game-review-page.md` | Records the game review page: board, moves, evaluation graph, side tabs, statistics, and findings. | dated report |
| `2026-11-16-catalog-two-sites.md` | Records the source-scoped catalog and analysis foundation shared by Lichess and chess.com. | dated report |
| `2026-11-16-chesscom-retrieval.md` | Records bounded chess.com retrieval, username persistence, parsing, and catalog storage. | dated report |
| `2026-11-16-chesscom-interface.md` | Records chess.com controls on Admin, Games, and Dashboard. | dated report |
| `2026-11-16-guided-training-method.md` | Records the standalone Grandmaster Thinking Method course, protected progress boundary, original content, and interface. | dated report |
| `2026-11-18-puzzle-library-foundation.md` | Records the shared Puzzle Library schema, bounded local importer, stable course references, and security limits. | dated report |
| `2026-11-19-full-puzzle-corpus.md` | Records the manual full Lichess Puzzle corpus lifecycle, corrected record rules, indexed selection, and target-Mac measurements. | dated report |
| `2026-11-20-puzzle-performance-core.md` | Records account Puzzle attempts, server-checked solving, the performance list rules, exclusion-aware selection, and retention. | dated report |
| `2026-11-20-puzzle-dashboard.md` | Records the Puzzles dashboard, the Random and By Category player, the category endpoint, and board line support. | dated report |
| `2026-11-21-puzzle-repetition.md` | Records the normal-Puzzle Repetition dashboard, Practice Sessions, the Mastered view and practice, and migration V13. | dated report |
| `2026-11-22-method-puzzle-performance.md` | Records guided-method Exercises as full Puzzle attempts that update the shared Training List state, and the new course exercise endpoints. | dated report |
| `2026-11-23-own-puzzle-creation.md` | Records Own Puzzle creation from completed My Moves analyses, migration V14, and the rule that a completed side is never analyzed again. | dated report |
| `2026-11-23-own-puzzle-training.md` | Records Own Puzzle attempts, Training Lists, New, Discard, and Restore, repetition and Mastered practice by group, the shared attempt engine, migration V15, and the Own Puzzle endpoints. | dated report |
| `2026-11-24-dashboard-layout.md` | Records the Dashboard and shell layout refresh. | dated report |
| `2026-11-23-own-puzzles-interface.md` | Records the Own Puzzles screens, the site filter and random order for New Own Puzzles, the shared puzzle player, and Game Review links to one move. | dated report |
| `2026-11-23-more-named-tactics.md` | Records the Stage 8B named motifs, their proof rules, `lineIndex`, the coach wording, and the startup refresh of Own Puzzle tactic labels. | dated report |
| `2026-11-24-refresh-on-rules-change.md` | Records the tactic rules version, migration V16, and the Own Puzzle label refresh that runs once after each rules change. | dated report |
| `2026-11-24-positional-observations.md` | Records the Stage 8B measured positional observations, their matched-position rules and thresholds, the only-move signal, and the coach wording. | dated report |
