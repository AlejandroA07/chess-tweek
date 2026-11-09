# Database foundation

Stage 1 checkpoint 2 implements the local persistence and architecture-test foundation.

## Runtime

- One ignored SQLite file: `var/chess-tweek.db`.
- Flyway applies ordered migrations at startup.
- Feature-owned tables arrive with their owning feature; the baseline migration creates no generic domain table.
- WAL journaling, foreign keys, `synchronous=NORMAL`, and a 5-second busy timeout are mandatory.
- Hikari uses at most two connections, keeps no minimum idle connection, and releases idle connections after 30 seconds.
- A new data directory is owner-only, the database file is owner-readable/writable only, and symbolic-link or unsafe existing directories are rejected without modifying them.
- No database server, container, ORM, or background database process exists.

## Enforced boundaries

- `shared` cannot depend on a feature or composition package.
- Feature packages cannot depend on each other or composition.
- Composition cannot import feature internals.
- JDBC, SQLite, Flyway, and pooling types stay in shared database code or a feature-owned persistence package.
- Controllers stay in composition.

## Verification

- The integration tests start the application twice against the same temporary database, check migration history and every required SQLite setting, verify owner-only permissions, and exercise both unsafe-path denial cases.
- Seven ArchUnit rules enforce the package contract and were observed rejecting a deliberate violation before the production code passed.
- The packaged app created and reopened the real local database, remained bound to `127.0.0.1`, and used about 141 MB of idle resident memory during the manual sample.
