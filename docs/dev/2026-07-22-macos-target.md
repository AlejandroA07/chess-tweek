# macOS product target — feature/project-planning

- **Date:** 2026-07-22
- **Type:** documentation

## Why

The public README must distinguish repository visibility from the product's supported platform. Chess Tweek is a public repository for a local-first macOS application.

## What changed

- `README.md` now describes a local-first macOS application without calling the repository private.
- Product, design, and architecture documents consistently name macOS as the only supported platform.
- Stockfish discovery targets the official Homebrew Apple Silicon installation and remains isolated from domain calculations.

## Decisions

macOS on Apple Silicon is the only supported target. Implementation and verification may rely on macOS process, filesystem, security, and Homebrew behavior.

## Verification

- Staged diff whitespace check passed.
- The pre-commit secret scan passed.
- The public README contains no development-tool attribution.
