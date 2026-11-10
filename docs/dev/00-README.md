# Development documentation

**Current state:** Stage 0 is complete. Stage 1 checkpoints 1 and 2 establish an executable Spring Boot and React foundation with lightweight SQLite persistence and enforced module boundaries. Checkpoints 3A and 3B add the responsive shared shell, allowlisted navigation, and intentional empty screens for Dashboard, Games, Reports, Puzzles, and Admin.

The database is a local `var/chess-tweek.db` file with Flyway migrations, WAL journaling, foreign keys, normal synchronization, a five-second busy timeout, and at most two pooled connections. Run `node scripts/verify.mjs` for the complete gate.

The interface now uses the canonical design tokens and shared shell at desktop and compact widths. Each destination has its own empty first-use explanation without exposing actions that are not implemented yet.

| File | What it contains | Kind |
|---|---|---|
| `00-README.md` | Current implementation-documentation state and index. | living topic doc |
| `2026-07-22-macos-target.md` | Records the macOS-only product target and Stockfish integration decision. | dated report |
| `2026-07-22-canonical-design.md` | Records the canonical visual direction and design-system gate. | dated report |
| `2026-11-09-database-foundation.md` | Records the SQLite, migration, architecture-test, and resource decisions. | dated report |
