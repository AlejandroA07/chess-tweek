# Refresh Own Puzzle labels only after a rules change

Stage 8B Part 1 refreshed every Own Puzzle's tactic labels at each application start. The owner found that unnecessary. By owner decision on 2026-11-24, the refresh now runs once after each change to the tactic rules, and is skipped on every other start.

## Why a refresh is needed at all

A tactic label is the labelling rules applied to the stored analysis.

- The analysis never changes.
- The rules are code, and they change when a new motif is added or an existing rule is broadened.
- The Game Review computes labels every time it opens, so it always uses the current rules.
- An Own Puzzle saves its labels when it is created, so they reflect the rules of that day.

Without a refresh, an older Own Puzzle's "By tactic" group could disagree with its Game Review.

## How it works

- `GameReviews.TACTIC_RULES_VERSION` is the version of the tactic rules: 1 is the Stage 4 rules, and 2 adds the Stage 8B named motifs. Any change to `TacticDetector` or `LineMotifs` that can change a label must raise it. The `TacticDetector` documentation says so.
- Migration V16 adds `training_own_puzzle_label_rules`. It is a single row holding the rules version the stored labels were last refreshed with. No row means never.
- At startup, `OwnPuzzleTacticRefresh` compares the two versions:
  - Equal: it does nothing; no game is read.
  - Older: it recomputes every Own Puzzle's labels as before, then records the current version.
- If any game fails, the version is not recorded, so the next start tries again. No failure stops the application starting.
- New Own Puzzles always get labels from the current rules when they are created.

## Tests

- `OwnPuzzleCreationTest`:
  - a fresh database records the current version at startup
  - a matching version reads nothing and changes nothing
  - an older version refreshes once, records the current version, and skips the next start
  - a failing game keeps the old version so the next start retries
- `OwnPuzzleServiceTest`: no version recorded reads as 0, recording twice keeps one row, and a version below 1 is refused.
- `OwnPuzzleLabelRulesMigrationTest`: V16 allows one row and only a positive version. `DatabaseFoundationTest` now counts 16 migrations.
