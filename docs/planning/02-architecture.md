# Chess Tweek architecture decisions

This document records the boundaries that are expensive to reverse. Stage 1 must translate them into project structure and executable architecture tests.

## Architecture summary

| Area | Responsibility | What the boundary protects |
|---|---|---|
| `identity` | Lichess PKCE, sealed session, current account, and authorization context. | Tokens and account identity from feature-specific persistence or accidental client exposure. |
| `game-review` | Lichess and chess.com game catalog, Stockfish jobs and findings, individual reviews, and (maybe later) Overall Games Analysis and report-owned packages. | Versioned evidence and report immutability from unrelated training workflows. |
| `training` | The canonical Lichess Puzzle Library and collections, versioned guided-method content and progress, performance-list classification and attempts, separate Own Puzzles built from `game-review`'s public findings query, the opening explorer cache, and repertoires. | Reusable Puzzle material, independent instruction, account practice history, and game-derived training state from one another's ownership and deletion lifecycles. |
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
  - `training` owns guided-course lesson/exercise progress and attempts, plus normal puzzle payloads and attempts.
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
- **Resolution:** The reopen condition was met on 2026-11-18. ADR-009 defines the global library while this decision continues to govern report-owned packages.

## ADR-007 — No live play or automatic work

- **Status:** Accepted
- **Context:** The product should help after games without creating fair-play risk or surprise CPU/storage work.
- **Decision:** Do not implement Board API, live-game streams, in-app play, automatic retrieval, automatic analysis, or automatic reports. Every such action begins with an explicit owner control.
- **Rejected:** Live play expands scope and creates a hard engine-isolation obligation. Background polling and analysis can consume battery, heat, disk, and upstream capacity unexpectedly.
- **Reopen when:** The owner explicitly starts a separately planned feature and its fair-play, permission, lifecycle, and resource decisions are grilled from scratch.

## ADR-008 — Guided course is a deep training-module capability

- **Status:** Accepted 2026-11-16
- **Context:** The thinking-method course must work before personalized game-driven recommendations exist, while preserving a clean seam for later evidence-based training.
- **Decision:** Keep authored course definitions as validated, versioned application resources owned by `training`. Expose sanitized course content and authorized account-scoped progress through the public `GuidedCourses` interface. Persist only progress and attempts in SQLite; never send accepted moves or explanations before a correct attempt or explicit reveal. The first module has no dependency on `game-review`.
- **Content rule:** Course prose, positions, hints, and feedback are original Chess Tweek material. Attribution describes inspiration from publicly stated GM Igor Smirnov principles without presenting the course as official or copying paid material.
- **Future seam:** Personalized lesson recommendations may consume an explicit public evidence query from `game-review`; they must not import its repositories, couple course progress to analysis deletion, or claim causation from small samples.
- **Rejected:** Storing authored lessons as mutable database rows adds editing and migration complexity with no v1 need. Building directly on analysis tables would make the standalone course unavailable before a player has games and would entangle deletion lifecycles.
- **Reopen when:** The owner needs user-authored courses, downloadable course packs, or personalized focus selection with an approved evidence and lifecycle contract.

## ADR-009 — Training methods reference one shared Puzzle Library

- **Status:** Accepted 2026-11-18
- **Context:** The first guided method currently carries its own puzzle positions, while future methods, named-pattern practice, and opening practice need to reuse the same curated Lichess material without copying positions or attempt history.
- **Decision:** `training` owns one user-visible Puzzle Library. A Puzzle is stored once with a stable internal ID, immutable source snapshot data, classification metadata, and provenance. Puzzle Collections and authored Exercises reference that ID. A Training Method owns its instruction, exercise order, hints, explanations, and completion rules, but never a private puzzle copy. Removing a method removes its references and authored material, not the Puzzle or account attempt history. Snapshot replacement is an explicit local operation and never silently rewrites a versioned exercise's provenance.
- **Taxonomy:** Import the complete current Lichess theme catalog as metadata. Treat opening families and variations as a separate, snapshot-derived hierarchy. Collections normally contain 50 curated puzzles, may use 20 for scarce patterns, and may grow to 100 for high-value, high-volume patterns.
- **Report seam:** Report-owned Mistake/Blunder and Related Lichess Puzzle packages remain under `game-review` and ADR-006. They do not gain cross-module foreign keys into the Puzzle Library.
- **Rejected:** A library per method duplicates positions, classifications, and attempts. One physical lifecycle for global and report-owned puzzles would let report deletion affect unrelated practice. Embedding mutable source positions directly in every method makes provenance refreshes invisible and future reuse brittle.
- **Reopen when:** A downloadable pack or user-authored puzzle must override the global provenance/retention rules, or measured scale makes the local SQLite catalog impractical.

## ADR-010 — The full Puzzle corpus keeps one current observation per Puzzle

- **Status:** Accepted 2026-11-19
- **Context:** The owner chose the complete official Lichess snapshot (about 6.2 million Puzzles). ADR-009's per-snapshot observation and tag rows would duplicate the whole corpus on every refresh, and Random and By Category must not sort or scan millions of rows.
- **Decision:** A full-corpus Admin import is its own auditable record (`training_puzzle_corpus_import`) that also carries the resumable job state. Each canonical Puzzle has exactly one current observation (`training_puzzle_current`) and integer category memberships (`training_puzzle_category_member`) keyed by category and Puzzle ID. A refresh updates observations in place and records which import last confirmed each Puzzle. Built-in and bounded-import Puzzles keep their pinned per-snapshot observations, so versioned exercise provenance never changes. Random and category selection choose a uniform point in the stored Puzzle ID range and seek the first Puzzle at or after it on a primary key.
- **Lifecycle:** Startup never contacts Lichess. Check for update sends one `HEAD` request; a separate explicit action downloads with byte-range resume, verifies size, `zstd -t` integrity, and a recorded SHA-256, then imports in chunks whose resume cursor commits with the chunk. A Puzzle missing from a newer snapshot is removed only when nothing references it; an immutable position or solution conflict keeps the stored Puzzle and is reported. The compressed source is deleted only after verification.
- **Rejected:** Random keys per Puzzle scatter inserts across large indexes; on the target Mac the import fell from about 20,000 to about 5,000 records per second by two million rows. `ORDER BY RANDOM()` scans the corpus. Keeping every snapshot's observations multiplies storage with each refresh.
- **Reopen when:** Part 3 needs history of rating or Theme changes across snapshots, or a category becomes too irregularly spaced in ID order for acceptable sampling.

## Architecture test obligations for Stage 1

- Reject Java imports from one module's internal package into another module.
- Keep database, filesystem, OAuth-token, and Stockfish code in backend-only packages that the frontend build cannot import.
- Reject feature modules importing Spring MVC controllers or frontend composition code.
- Require all database access to remain under the owning module.
- Require composition code to depend on module public entry points.
- Include the architecture checks in `scripts/verify.mjs` and CI from the first application branch.
