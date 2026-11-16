# Grandmaster Thinking Method guided course

**Date:** 2026-11-16

**Branch:** `feature/guided-training-method`

**Stage:** 6

## Delivered outcome

Chess Tweek now has a first-class Training destination and a Dashboard shortcut into the standalone **Grandmaster Thinking Method** course. The first module, **Repeatable Move Routine**, is complete: four browseable lessons teach reading the last move, generating serious candidates, testing the strongest reply, and making a final safety check. Each lesson contains original explanation, a recall checklist, and three original board exercises.

The course is independent Chess Tweek material inspired by GM Igor Smirnov's publicly described principles. No paid lesson, branded curriculum, prose, image, video, or exercise was copied. Six future modules are visible only as Planned roadmap entries.

## Backend and persistence

- `GuidedCourses` is the public training-module interface. Controllers depend on that interface rather than internal repositories.
- Authored content is a validated, versioned JSON resource. Application startup rejects invalid keys, incomplete FENs, illegal accepted UCI moves, missing hints, and malformed lesson structure.
- Sanitized course reads are public so the lesson material stays available without an active connection. Accepted moves, solution moves, and answer explanations are omitted.
- Progress, attempts, hints, reveals, and completion are scoped to the sealed-session account. Mutations require same-origin validation and never accept an account ID from the client.
- Flyway V7 adds training-owned lesson progress, exercise progress, and immutable attempt rows with foreign keys, checks, and indexes.
- Submitted moves are syntax-checked and validated against the exercise FEN with the approved `chessgame` library before comparison with server-held accepted moves.

## Interface

- Training sits after Dashboard in the desktop rail and compact navigation.
- The Dashboard shortcut distinguishes preview, start, and continue states without blocking the rest of Dashboard when course progress is unavailable.
- The course dashboard shows durable progress, the four-step routine, available lessons, planned modules, offline behavior, and source/independence attribution.
- Lessons remain readable when disconnected. Exercise actions explain that connection is required.
- The project chessboard now has a reusable interactive variant supporting click, keyboard, and drag input while leaving the existing read-only review board unchanged.
- Exercise states include retry feedback, two progressive hints, reveal, correct solution, persisted solved/revealed status, and Review answer on return.

## Intentional limits

- This slice is guided, not personalized. It does not read game-review findings or recommend a lesson from player evidence.
- It does not add spaced-repetition grading, mastery, streaks, points, or artificial lesson locks. Those repetition mechanics remain Stage 7 work.
- Course definitions are application resources rather than user-editable database content.

## Verification

The implementation has focused backend integration/content/persistence coverage, frontend board/course/shell coverage, and the repository's full verification gate. Exact final command results are recorded in the feature handoff and commit.
