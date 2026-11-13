# Development documentation

**Current state:** Stages 0, 1, and 2 are complete, including the owner's live Lichess connection check. Stage 3 Parts 1 through 9 add protected manual retrieval, an account-scoped SQLite catalog, exact new/updated/unchanged retrieval summaries, the owner's per-game Lichess rating change, Dashboard rating history and bot record built from stored games, the Games library and retrieval panel, the complete approved server-backed filter set, and accessible selection for one eligible game or an exact eligible filtered group. Filter values are bounded or allowlisted before authorization and translated into parameterized account-scoped SQL. Stage 3 is complete, including the owner's live check. Stage 4 Part 1 locates Stockfish, runs a bounded UCI health check, and shows engine status on the Admin page.

The database is a local `var/chess-tweek.db` file with Flyway migrations, WAL journaling, foreign keys, normal synchronization, a five-second busy timeout, and at most two pooled connections. Run `node scripts/verify.mjs` for the complete gate.

The interface now uses the canonical design tokens and shared shell at desktop and compact widths. Each destination has its own empty first-use explanation without exposing actions that are not implemented yet.

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
