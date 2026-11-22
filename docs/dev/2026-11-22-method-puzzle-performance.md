# Guided-method puzzle performance

Stage 7 Part 3d links the Grandmaster Thinking Method to normal-Puzzle performance training. Each course Exercise references a shared Lichess Puzzle. Solving the Exercise is now a full Puzzle attempt on that Puzzle, so it updates the same Training List state as Random, By Category, and Repetition. Lesson and Exercise progress stay separate (`docs/planning/07-performance-training-lists.md`: "Wrong Moves, hints, Solve Time, counters, and list movement use the global rules, while authored course and lesson completion remain separate").

## Owner decision (2026-11-22)

Exercises play the Puzzle's whole line against the opponent's replies, with Solve Time running to the final move. The owner chose this over keeping one-move exercises, because the rules count only a complete line ("Timing only the first move is not sufficient"). The two written course hints count as hints.

## Delivered boundary

- `PuzzleTraining` gains two methods:
  - `startForMethod` starts an attempt with entry `method`. Migration V13 already allows that entry, so no migration is needed.
  - `methodAttemptPuzzle` returns the Puzzle of one of the account's method attempts.
- `GuidedCourses` replaces the one-move `attempt`, `hint`, and `reveal` with five methods: `startExercise`, `exerciseMove`, `exerciseHint`, `exerciseReveal`, and `review`.
  - Every action on an attempt first checks that the attempt is the account's own method attempt on that Exercise's Puzzle. Any other attempt, another account's attempt, or another Exercise's attempt is treated as not found.
  - The Puzzle move and the course progress write share one transaction.
- Endpoints under `/api/training/courses/grandmaster-thinking-method/exercises/{key}`:
  - `POST /attempts` starts the attempt.
  - `POST /attempts/{id}/moves`, `/hint` (levels 1 and 2), and `/reveal` act on it.
  - `GET /answer` returns the full answer of an Exercise that is already solved or revealed, and changes nothing. It returns `409 exercise_not_resolved` before then, without the line.
  - The `POST` endpoints check, in order, a same-origin request, validated input, and the session. The `GET` requires the session. Errors are fixed codes, and responses are `no-store`.
  - The start response is the Puzzle attempt shape and never contains the solution.
- The previous one-move endpoints `POST .../exercises/{key}/attempts` (with a move body), `.../hint`, and `.../reveal` are replaced.
- Shared web pieces (follow-up cleanup):
  - `PuzzleAttemptResponses` owns the attempt and completion response shapes that both the Puzzle and the guided-course controllers return.
  - `TrainingErrorCodes` names every error code of both controllers. `frontend/src/trainingErrorCodes.ts` names the codes the interface reads, and `TrainingErrorCodesTest` fails if the interface names a code the server does not define.
  - Every guided-course endpoint runs through one of two helpers: writes check origin, input, and session in that order; reads check the session. Both turn a storage or content failure into `503 training_unavailable`.

## Course progress rules

- A Wrong Move records an incorrect course attempt. Completing the line records a correct one, which marks the Exercise Solved. Show solution marks it Revealed, and a written hint records its hint level. The course store never moves an Exercise backwards, so Solve again cannot undo Solved.
- Leaving or replacing an unfinished attempt, for example by starting a Random puzzle, leaves both the Puzzle and the Exercise unchanged.
- Exercises solved before this change keep their course state and were never attempts on their Puzzle. Each Puzzle is first classified by its next Solve again or by another entry point.

## Interface

- The exercise board is read-only until **Start solving**, so reading the lesson does not count toward Solve Time. While solving, the card shows the moves to find, the fast-solve limit, and Wrong Moves. A correct move shows the opponent's reply, and a Wrong Move leaves the position unchanged with the authored feedback.
- Completion shows the authored explanation and the Puzzle result ("Puzzle training: placed in Hard to Solve. Solve Time 7.4 s of 11 s."). After Show solution it says Solve Time is not counted. The board stays open for exploration that is not saved.
- A resolved Exercise offers **Review answer** (the full line, stated not to change the Training Lists) and **Solve again**.

## Verification contract

- `GuidedCoursePersistenceTest` covers the following:
  - The full line with an illegal move, a Wrong Move, a written hint, the opponent's reply, and completion. The Puzzle is placed in Hard to Solve because of the hint, method attempts are stored as `method`, and course progress is unchanged across restart.
  - Solve again applies the Puzzle's current list rules (one Wrong Move moves it to Wrong Moves at once) without changing course progress.
  - Review is allowed only once an Exercise is resolved.
  - Attempts of another account, another Exercise, and a Random attempt are refused, an attempt replaced by a Random start is reported as finished, and an invalid hint level is refused.
- `GuidedCourseControllerTest` covers the new endpoints and outcome codes, cross-origin refusal for every action, input validation before authorization, an unresolved answer that does not contain the line, and the session requirement for the answer.
- `TrainingCourse.test.tsx` covers the read-only board before Start solving, a full line with a Wrong Move, a written hint, a reply, and completion, Show solution, review and Solve again, and an attempt that ended elsewhere.

`node scripts/verify.mjs` remains the only complete definition of done.
