# Puzzle repetition

Stage 7 Part 3c adds the normal-Puzzle Repetition dashboard, Practice Sessions, and the Mastered view with optional Mastered practice. These are built over the Part 3a performance core and the Part 3b player. The rules are in `docs/planning/07-performance-training-lists.md`. The visual rules are the "Puzzle training view" section of `docs/app-material/DESIGN.md`. Part 3d (the guided-method link) remains.

## Delivered boundary

- Migration V13 does three things:
  - It adds the `(account_id, list, puzzle_id)` index on `training_puzzle_performance`.
  - It rebuilds `training_puzzle_attempt` so `entry` also allows `repetition`, `mastered_practice`, and `method`. `method` is reserved for Part 3d, so 3d needs no further table rebuild. A new check requires a `mastered_practice` attempt to start in Mastered and, if it completes, to end in Mastered.
  - It adds `training_puzzle_session` and `training_puzzle_session_item`. Existing attempts keep their IDs and values.
- `PuzzleTraining` gains five methods: `listCounts`, `mastered`, `startSession`, `startMasteredSession`, and `nextInSession`. Its `Entry` enum gains `REPETITION` and `MASTERED_PRACTICE`.
- Endpoints:
  - `GET /api/training/puzzles/repetition` returns the account's count for every list, in progression order.
  - `GET /api/training/puzzles/repetition/mastered` returns the Mastered total and the Mastered count per category.
  - `POST .../repetition/{hard_to_solve|wrong_moves|worth_repeating|retiring}/sessions` starts a session over one list.
  - `POST .../repetition/mastered/{kind}/{key}/sessions` starts Mastered practice for one category.
  - `POST .../sessions/{id}/attempts` presents the next puzzle.
- The `GET` endpoints require the connected owner session. The `POST` endpoints check, in order, a same-origin loopback request, validated input, and the session, as in Part 3a. Mastered is refused as a whole-list session. A finished session returns `409 session_complete`, and an unknown or replaced one returns `404 session_not_found`. Every response is `no-store` and never contains a solution.
- `JdbcPuzzleCorpusStore.RETAINED` also keeps a Puzzle that a session item references.

## Practice Sessions

- Opening a list, or starting a new session after one ends, creates a session. The session snapshots every Puzzle currently in that list (or the account's Mastered Puzzles in one category) and shuffles them with `SecureRandom`. Creating a session deletes the account's previous session, so no order is ever reused.
- `nextInSession` claims the next unpresented item with a guarded update, so two concurrent requests never present the same Puzzle. It passes over a Puzzle that has left the session's list since the snapshot, for example one moved through By Category in another tab. A Puzzle that enters the list during a session waits for the next session. When every item has been presented, the session is complete. The interface then offers Start a new session, which reshuffles the list.
- A repetition attempt is an ordinary attempt with entry `repetition`. It applies the current list's rules, including the immediate demotions from Part 3a.

## Mastered view and practice

- The Mastered summary reads the stored Themes and opening tags of the account's Mastered Puzzles that are in the current corpus. It maps them to categories with the same derivation the category index uses. The work is bounded by the account's Mastered Puzzles, and no index over the 30-million-row membership table is needed. A Mastered Puzzle that is no longer in the current corpus counts in the total but appears in no category, because category practice uses current membership.
- A Mastered-practice attempt never changes list state. Wrong Moves and hints apply no immediate movement, and completion records `list_after = mastered` without writing `training_puzzle_performance`, so even its `updated_at_ms` is unchanged.

## Interface

- Routes:
  - `#/puzzles/repetition`: the Repetition dashboard.
  - `#/puzzles/repetition/{hard-to-solve|wrong-moves|worth-repeating|retiring}`: a Practice Session.
  - `#/puzzles/repetition/mastered`: the Mastered view.
  - `#/puzzles/repetition/mastered/{kind}/{key}`: Mastered practice.
- The side-bar "Repetition: normal puzzles" entry and the dashboard Repetition card are now links. Own Puzzles stays Planned.
- The Practice Session and Mastered practice reuse the Part 3b player. The player adds a "Session" fact ("3 of 12") and a back link to Repetition or Mastered. It explains an empty list, a completed session, and a session replaced by another one. In Mastered practice, the Hard to Solve warnings become "practice only" statements and completion says "Practice only: stays Mastered."

## Verification contract

- `PuzzleTrainingServiceTest` covers the following:
  - A session presents every Puzzle in its list once and is then complete.
  - A new session reshuffles and makes the previous one unknown. Sessions are scoped to their account.
  - A whole-list Mastered session and an empty list are refused.
  - Hard to Solve's rule is applied inside a session, attempts are stored as `repetition`, and a Puzzle that has left the list is passed over.
  - Mastered is grouped by category, and Mastered practice with Wrong Moves, a hint, Show solution, and a fast solve leaves every list row unchanged.
- `PuzzleTrainingControllerTest` covers the new endpoints, their outcome codes, invalid lists and categories refused before authorization, cross-origin refusal, and the disconnected state for the reads.
- `PuzzlePracticeSessionMigrationTest` covers V13 keeping an existing attempt, accepting the new entries, refusing an unknown entry and a Mastered-practice attempt outside Mastered, and recreating the indexes.
- `PuzzleTraining.test.tsx` covers the new routes, the Repetition dashboard (including an empty list), a full two-puzzle session followed by a new session, an empty list and a replaced session, the Mastered grouping and its empty state, and Mastered practice wording. `App.test.tsx` covers the Repetition side-bar link.

`node scripts/verify.mjs` remains the only complete definition of done.
