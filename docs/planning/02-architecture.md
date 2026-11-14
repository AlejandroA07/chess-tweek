# Chess Tweek architecture decisions

This document records the boundaries that are expensive to reverse. Stage 1 must translate them into project structure and executable architecture tests.

## Architecture summary

| Area | Responsibility | What the boundary protects |
|---|---|---|
| `identity` | Lichess PKCE, sealed session, current account, and authorization context. | Tokens and account identity from feature-specific persistence or accidental client exposure. |
| `game-review` | Lichess and chess.com game catalog, Stockfish jobs and findings, individual reviews, and (maybe later) Overall Games Analysis and report-owned packages. | Versioned evidence and report immutability from unrelated training workflows. |
| `training` | Spaced-repetition scheduler and attempts, My Mistakes drills (built from `game-review`'s public findings query), the pattern library, the opening explorer cache, and repertoires (re-scope of 2026-11-14). | Independent practice history from report-owned evidence and deletion lifecycles. |
| `shared` | Configuration, database connection, safe HTTP errors, correlation IDs, clocks, and narrow primitives. | Cross-cutting mechanics without becoming a dumping ground for feature rules. |
| composition | Spring MVC controllers, application wiring, and delivery of the built React interface. | Keeps feature modules independent while allowing the application to assemble them. |

Dependency rule:

> Controllers and application composition may import a module's public package; module internals may import `shared`, but never another module's internal package.

Stage 1 encodes Java package boundaries with ArchUnit and keeps the frontend in a separate build boundary that cannot import backend internals.

## ADR-001 — Local modular monolith

- **Status:** Accepted
- **Context:** The application has one owner, one Mac, one process, and several real capability boundaries. The expensive qualities are privacy and evidence integrity, not horizontal scale.
- **Decision:** Use one Spring Boot modular monolith. Spring MVC exposes the local API and serves the built React interface from the same process and origin. Bind the embedded server to `127.0.0.1`.
- **Rejected:** A layerless application would let OAuth, engine, reports, and puzzles couple freely. Separate frontend/API services or microservices would add operations without an isolation need. A ceremonial clean-architecture folder tree would create empty abstractions before domain behavior exists.
- **Reopen when:** A capability needs a separate process for measured reliability or resource isolation and can own its data and deployment independently.

## ADR-002 — Module data ownership in one SQLite database

- **Status:** Accepted
- **Context:** SQLite is the simplest durable store for a local single-user application, but a shared database can erase module boundaries if tables are joined casually.
- **Decision:** Use one SQLite file with module-owned tables and Flyway migrations. Feature code uses parameterized Spring JDBC access through its module's repositories. Cross-module information flows through public query/command contracts assembled at the composition root, never by importing another module's repository.
- **Ownership:**
  - `identity` owns local non-secret account/session metadata; OAuth tokens are not database data.
  - `game-review` owns games, analysis jobs, analysis versions/findings, reports, report-game references, and both report-owned package/attempt tables.
  - `training` owns normal puzzle payloads and attempts.
- **Cross-boundary IDs:** External Lichess IDs and account IDs may be copied as scoped values. Do not create a database foreign key across module ownership.
- **Rejected:** PostgreSQL/Docker adds a service without a v1 need. A single generic repository would make data ownership unenforceable. Separate SQLite files would complicate backup and transactional storage caps.
- **Reopen when:** Concurrent writers or measured query volume exceed SQLite's local single-process operating envelope.

## ADR-003 — Native Stockfish behind a game-review adapter

- **Status:** Accepted
- **Context:** Analysis must be free, local, strong, resumable, and efficient on an Apple Silicon Mac.
- **Decision:** Communicate with native Stockfish using UCI from a Java `ProcessBuilder` adapter owned by `game-review`. Discover the official Homebrew Apple Silicon binary or an explicitly configured local binary. Domain calculations consume normalized engine results rather than process output.
- **Rules:** Store engine version and configuration with every analysis. Run one game at a time. Use fixed-node budgets derived from a measured benchmark. Never expose the engine adapter to client components or live-play code.
- **Rejected:** Browser WASM weakens background queue control. Lichess cloud analysis is incomplete across games and makes local reproducibility dependent on upstream data. A paid model is not a chess engine and violates the free-first goal.
- **Reopen when:** Native execution becomes unavailable on macOS or measured requirements justify a dedicated local worker process.

## ADR-004 — Sealed cookie for the Lichess token

- **Status:** Accepted
- **Context:** The app needs a long-lived Lichess token but the database and backups should never contain it.
- **Decision:** Keep the token in a sealed Secure, HttpOnly, SameSite cookie. Store the sealing key under ignored `var/` with `0600` permissions. Bind to `127.0.0.1`, validate OAuth state and Origin/CSRF, and scope all resource queries to the session's account ID.
- **Rejected:** Plain browser storage exposes the token to client JavaScript. SQLite would copy it into backups. A committed environment file is a secret leak. A personal access token is unsuitable for a normal OAuth product flow.
- **Reopen when:** The application becomes multi-device or remotely hosted and can use an operating-system or platform secret store.

## ADR-005 — Immutable evidence and reports

- **Status:** Accepted
- **Context:** Stockfish versions and analysis profiles change. A saved report must not silently change because a game is reanalyzed later.
- **Decision:** Reanalysis creates a new analysis version. A report references exactly 10 owner analysis versions with restrictive foreign keys and stores an aggregate snapshot. Report packages belong to the report and are deleted with it.
- **Rejected:** Overwriting analysis in place destroys reproducibility. Dynamically recomputing saved reports makes historical results unstable. Copying all evidence into disconnected JSON makes deletion dependencies and drill-down unreliable.
- **Reopen when:** A deliberate report-migration feature is designed with visible versioning and owner approval.

## ADR-006 — Report packages share ownership, normal puzzles do not

- **Status:** Accepted
- **Context:** A report produces two forms of targeted practice, while Daily/Mixed puzzles exist independently.
- **Decision:** Mistake/Blunder Puzzles and Related Lichess Puzzles are `game-review` report-owned data and cascade with report deletion. Daily/Mixed puzzles and their attempts are owned by `training` and never cascade with a report.
- **Rejected:** Putting every puzzle in one lifecycle would make report deletion erase unrelated practice or leave orphaned targeted packages.
- **Reopen when:** A user-visible global puzzle library is designed with explicit provenance and retention rules.

## ADR-007 — No live play or automatic work

- **Status:** Accepted
- **Context:** The product should help after games without creating fair-play risk or surprise CPU/storage work.
- **Decision:** Do not implement Board API, live-game streams, in-app play, automatic retrieval, automatic analysis, or automatic reports. Every such action begins with an explicit owner control.
- **Rejected:** Live play expands scope and creates a hard engine-isolation obligation. Background polling and analysis can consume battery, heat, disk, and upstream capacity unexpectedly.
- **Reopen when:** The owner explicitly starts a separately planned feature and its fair-play, permission, lifecycle, and resource decisions are grilled from scratch.

## Architecture test obligations for Stage 1

- Reject Java imports from one module's internal package into another module.
- Keep database, filesystem, OAuth-token, and Stockfish code in backend-only packages that the frontend build cannot import.
- Reject feature modules importing Spring MVC controllers or frontend composition code.
- Require all database access to remain under the owning module.
- Require composition code to depend on module public entry points.
- Include the architecture checks in `scripts/verify.mjs` and CI from the first application branch.
