# Thinking-habit trainer: owner ideas

Recorded on 2026-11-23 from an owner request, to be decided later. Nothing here is scheduled. Before any of it is built, the chosen parts must be copied into `01-chess-tweek.md` and `03-delivery-plan.md`, and the open questions below must be closed.

## The owner's ideas

1. **Three weaknesses as course themes.** Extend the Grandmaster Thinking Method with:
   - **Overlooking threats:** missing what the opponent threatens, or what a move of mine allows.
   - **Misjudging positions:** thinking a position is better or worse than it is, and choosing the wrong kind of move because of it.
   - **Struggling to find plans:** not knowing what to do when there is no tactic.

   Each should have play-through positions and puzzles that train it.
2. **A new question: "What's wrong with my opponent's last move?"** Before looking for my own move, ask what the opponent's move weakened, left undefended, or failed to address. The aim is to punish mistakes, not only to defend against threats.
3. **A question drill to build the habit.** Show a puzzle and present the thinking questions one at a time, in order. I think about each one and answer it before I move. The purpose is to repeat the questions until asking them becomes automatic.
4. **The drill over my Own Puzzles.** The same drill, opened from the Grandmaster Thinking Method, takes its positions from my Own Puzzles, so I practice the habit on positions where I actually went wrong.

## What exists today (checked 2026-11-23)

| Idea | State | Where |
|---|---|---|
| Overlooking threats | **Partly covered.** Module 1, Repeatable Move Routine, has four lessons. "Read the last move" asks what changed and what is threatened. "Test the strongest reply" and "Make the final safety check" ask what my move allows. Each lesson has three Lichess exercises. | `src/main/resources/training/grandmaster-thinking-method.json`, `docs/dev/2026-11-16-guided-training-method.md` |
| Misjudging positions | **Not covered.** No lesson trains evaluation: material, king safety, activity, and structure, then which side is better and why. No planned module names it directly. "Active Middlegame Play" and "Calculation Discipline" come closest. | Planned modules in the course JSON |
| Struggling to find plans | **Planned, not built.** "Plans and Pawn Structures" is a Planned module. The research notes that Lichess puzzles are weak for positional subjects such as plans, pawn breaks, and improving a passive piece. Own-game positions and original examples must fill that gap. | Course JSON; `docs/gm-igor-system.md` (sections on plans and puzzle limits) |
| "What's wrong with my opponent's last move?" | **Close, but not the same.** "Read the last move" asks "What did the opponent's move attack or uncover?", which is a defensive question. Nothing asks what the move weakened or which opportunity it gave me. | Lesson `read-the-last-move` checklist |
| Question drill before moving | **Not built.** Lessons show the checklist as text to read, and exercises ask only for a move. Nothing presents the questions one at a time during a puzzle or asks for an answer. | `TrainingCourse.tsx` |
| Drill over Own Puzzles | **Not built, but the data is ready.** Own Puzzles (Stage 8) store the position before my move and the opponent's previous move. That previous move is exactly what "What's wrong with my opponent's last move?" needs. They also store the engine's best line, the severity, the phase, the opening, and proven tactic labels. An `allowed` tactic label marks a threat I overlooked, and a `missed` label marks an opportunity I missed. | `docs/dev/2026-11-23-own-puzzle-creation.md`, `docs/dev/2026-11-23-own-puzzle-training.md` |

The closest earlier idea is in "Maybe later — Game Review explanation extras" in `03-delivery-plan.md`: an optional one-tap cause per mistake (threat not seen, move not considered, calculation, judgement) and an optional "find the better move first" step.

## A possible shape (to discuss, not decided)

- **The question sequence.** The routine becomes a fixed, ordered list of questions, for example:
  1. What's wrong with my opponent's last move?
  2. What do they threaten?
  3. Which checks, captures, and threats do I have?
  4. What is their strongest reply to my best candidate?
  5. Is my move safe?

  For quiet positions, two more questions could follow: "Who is better, and why?" and "What is my plan?"
- **The drill view.** It would live inside the Grandmaster Thinking Method, separate from normal puzzle solving.
  - The board is shown with the first question only.
  - I answer, then the next question appears. After the last question, I play my move.
  - The move is then checked as usual and counts as a normal attempt in my Training Lists, as guided exercises already do.
- **How an answer is checked.** Without a language model (none is planned), free-text answers cannot be graded. There are three options, and they can be mixed:
  - **Self-check:** after I answer, the app shows what the stored evidence says, such as the threat the engine line proves or the piece left undefended, and I mark whether I saw it. This is the most flexible, and it trains the habit.
  - **Board answers:** some questions are answered on the board. For example, I select the square or piece the opponent threatens, and the app verifies it from the engine lines. This is objective but covers fewer questions.
  - **Choices built from evidence:** a short list of options where the right one comes from the stored analysis, for example "Your queen is attacked" or "Their knight is undefended".
- **Content per weakness.**
  - Overlooking threats can use Lichess puzzles and Own Puzzles with an `allowed` tactic label.
  - Misjudging positions and plans need original, authored positions with explanations, or positions from my own games. Puzzles alone are not enough there.
- **Own Puzzles as the source.** A drill session draws from my Own Puzzles. It could be filtered by the same opening, phase, and tactic groups, or by severity. Whether a drill attempt also moves the puzzle between Own Puzzle Training Lists is an open question below.

## Open questions

1. Should the three weaknesses become new course modules, new lessons in Module 1, or tags on existing exercises?
2. Should the question list be fixed, or can I edit it? Should quiet positions get a different list from tactical ones?
3. Which way of checking answers do I want: self-check, board answers, choices from evidence, or a mix?
4. Should a drill attempt on an Own Puzzle count toward its Training List, as guided exercises do, or be practice only, like Mastered practice?
5. Should the drill also run over Lichess puzzles, or only over Own Puzzles?
6. Who writes the misjudgement and planning positions: original authored material (as Module 1 was), or my own games only?
7. Should the drill record which question I answered wrongly, to show over time which habit is weakest? This would overlap with the "one-tap cause per mistake" idea.

## When it could fit

- The habit drill over Own Puzzles needs Stage 8 (done with Part 8c). It benefits from Stage 8B, Deeper Game Review, because more proven tactic labels and positional observations give the self-check more to show.
- A sensible order: after Stage 8B, a small first slice of the question drill with self-check over Own Puzzles. Then misjudgement and planning content as later course modules, when the authored material is decided.
