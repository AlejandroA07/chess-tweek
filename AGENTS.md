# Chess Tweek — Agent Guide

Chess Tweek is a local-first chess training web app for macOS. Stage 0 planning, the Stage 1 application foundation, and the Stage 2 Lichess connection implementation are complete. Stage 3 is being delivered in independently verified parts.

This file is the source of truth for coding agents. Keep it aligned with the repository's current, verified state.

## Current architecture

| Path | Responsibility |
|---|---|
| `docs/planning/` | Product decisions, architecture, acceptance checks, and ordered slices. |
| `docs/app-material/DESIGN.md` | Canonical visual system and Stage 1 design acceptance contract. |
| `docs/app-material/` | Local prompts, owner-approved references, and supporting design exports. |
| `docs/dev/` | Implementation truth after application work begins. |
| `src/` | Spring Boot application and backend tests. |
| `frontend/` | React interface and frontend tests. |
| `scripts/verify.mjs` | Complete repository verification gate. |

The application foundation is a Spring Boot process that serves the built React interface and a local API on `127.0.0.1`. SQLite persistence, Flyway migrations, and executable module boundaries are implemented under `shared.database` and `ArchitectureTest`. Every user-facing view must use `docs/app-material/DESIGN.md` as its only visual source of truth.

The approved foundation stack is Java 25, Spring Boot 4.1, Maven, SQLite/JDBC/Flyway, and a React 19/TypeScript/Vite 8 interface. Spring Boot serves the built interface as one local macOS application bound to `127.0.0.1`. The responsive shell uses self-hosted project fonts, a keyboard-operable compact navigation disclosure, and the five allowlisted routes.

## Commands

- Start the packaged macOS application: `./scripts/start.sh`
- Full verification gate: `node scripts/verify.mjs`
- Frontend development server: `pnpm frontend:dev`
- Frontend tests: `pnpm frontend:test`
- Frontend production build: `pnpm frontend:build`
- Backend tests and package: `./mvnw clean verify`

Use Java 25, Node 24, and pnpm. `scripts/verify.mjs` enforces the required runtime versions and is the only definition of done.

## Secrets and security

- There are no project secrets.
- Never read, print, or commit future runtime secrets or OAuth tokens.
- Local harness paths and future `var/` runtime data are excluded through `.git/info/exclude`.
- All future endpoints require explicit authorization, boundary validation, safe errors, and parameterized data access.

## Laws

- Work only on branches named `feature/<topic>`, never directly on `main`.
- After verification, commit coherent work on the current `feature/<topic>` branch. When the task calls for remote publication, push only that explicit branch to `origin`. The owner alone merges.
- Never add application code on a planning, documentation, or wording branch; application work starts on the reviewed Stage 1 branch.
- Never introduce a second visual system or copy generated UI code; implement `docs/app-material/DESIGN.md` with project tokens and CSS Modules.
- Never add a dependency without first proposing it to the owner.
- Never weaken or remove a test to make a gate pass.
- Do not claim application verification before Stage 1 creates and proves `node scripts/verify.mjs`.

## Current stage

Stages 0, 1, and 2 are complete, including the owner's live Lichess connection check. Stage 3 Parts 1 and 2 implement authenticated, bounded manual retrieval plus an account-scoped, idempotent SQLite game catalog. Standard-game SAN is legally validated, non-standard variants remain cataloged but analysis-ineligible, and individual bad exports are rejected without losing valid games. No retrieval starts analysis. The next part adds the Games catalog API and interface.
