# Chess Tweek — Agent Guide

Chess Tweek has completed its planning and design-selection gate. It will become a local-first chess training web app for macOS. The next implementation slice is the application foundation, but it must begin on its own reviewed feature branch.

This file is the source of truth for coding agents. Keep it aligned with the repository's current, verified state.

## Current architecture

| Path | Responsibility |
|---|---|
| `docs/planning/` | Product decisions, architecture, acceptance checks, and ordered slices. |
| `docs/app-material/DESIGN.md` | Canonical visual system and Stage 1 design acceptance contract. |
| `docs/app-material/` | Local prompts, owner-approved references, and supporting design exports. |
| `docs/dev/` | Implementation truth after application work begins. |

There is no application architecture yet. The approved future architecture is documented in `docs/planning/02-architecture.md`; Stage 1 will encode it in code and architecture tests. Every user-facing view must use `docs/app-material/DESIGN.md` as its only visual source of truth.

The approved foundation stack is Java 25, Spring Boot 4.1, Maven, SQLite/JDBC/Flyway, and a React 19/TypeScript/Vite 8 interface. Spring Boot serves the built interface as one local macOS application bound to `127.0.0.1`.

## Commands

No build, test, format, or application commands exist in Stage 0. Do not invent them. Stage 1 must scaffold the application, run every command, and only then add verified commands here and create `scripts/verify.mjs`.

## Secrets and security

- There are no project secrets in Stage 0.
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

## Stage 0 completion

Stage 0 is complete when the planning indexes, product specification, architecture decisions, all view prompts, and this project guide are versioned; machine-local harness implementation remains invisible to Git; and the repository has no application code or dependencies.
