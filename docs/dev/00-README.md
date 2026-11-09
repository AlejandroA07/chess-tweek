# Development documentation

**Current state:** Stage 0 is complete. Stage 1 checkpoint 1 establishes an executable Spring Boot and React foundation for macOS, served as one loopback-only application with an automated verification gate.

The current foundation includes Java 25, Spring Boot 4.1, Maven, React 19, strict TypeScript, Vite 8, and Node 24. Run `node scripts/verify.mjs` for the complete gate. Persistence and architecture-boundary enforcement belong to the next checkpoint.

| File | What it contains | Kind |
|---|---|---|
| `00-README.md` | Current implementation-documentation state and index. | living topic doc |
| `2026-07-22-macos-target.md` | Records the macOS-only product target and Stockfish integration decision. | dated report |
| `2026-07-22-canonical-design.md` | Records the canonical visual direction and design-system gate. | dated report |
