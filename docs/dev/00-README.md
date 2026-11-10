# Development documentation

**Current state:** Stages 0 and 1 are complete. Stage 2 now has isolated Lichess authorization, sealed-session status, selected account metadata, visible connection states, and logout. The packaged application reaches the real Lichess sign-in screen; owner approval remains the final acceptance check. The verified Spring Boot and React foundation includes lightweight SQLite persistence, enforced module boundaries, a responsive shared shell, allowlisted navigation, and intentional empty screens for Dashboard, Games, Reports, Puzzles, and Admin.

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
