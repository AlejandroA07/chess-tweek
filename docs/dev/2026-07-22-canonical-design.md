# Canonical design system — local design gate

- **Date:** 2026-07-22
- **Type:** design

## Why

The supplied visual references contained a strong hero direction alongside inconsistent navigation, authentication, subscription, bot-play, and live-assistance concepts. Implementation needed one explicit visual source of truth before the application foundation could begin.

## What changed

- `docs/app-material/DESIGN.md` records the approved Hero / Grandmaster Studio language.
- The light checkerboard workspace background is required across every route.
- Semantic colors, typography, spacing, layout, component states, accessibility, motion, responsive behavior, and Stage 1 visual checks are defined.
- Supplied screens are mapped into approved visual qualities and rejected out-of-scope functionality.
- The local project guide now prevents implementation from introducing a parallel visual system.

## Decisions

The generated visual references are supporting material only. Product scope and `DESIGN.md` take precedence. Generated HTML, Tailwind classes, Material Symbols, remote assets, and out-of-scope functionality are not implementation inputs.

## Verification

- Core text, primary, success, warning, and error color pairings were measured against WCAG contrast thresholds.
- The exact approved checkerboard construction was recovered from the hero export and documented with safe layering instructions.
- Raw local design exports and machine-local harness implementation were confirmed ignored by Git.

## Follow-ups

Stage 1 must encode these decisions as CSS custom properties and visually verify the shell at desktop and responsive smoke-test widths.
