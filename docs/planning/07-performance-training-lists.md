# Performance-based Puzzle training lists

This document records the owner's decisions for classifying and repeating Puzzles. It replaces the proposed calendar-based spaced-repetition schedule with user-chosen training lists: the application measures performance and maintains the lists, while the owner decides when to practice each list.

## Single-copy Puzzle access

- Chess Tweek imports the complete official Lichess Puzzle snapshot into the one training-owned Puzzle Library. A provider and external Puzzle ID identify one canonical stored Puzzle; importing the same snapshot or Puzzle again never creates a duplicate.
- Themes, Opening Tags, methods, course Exercises, Training Lists, attempts, and dashboard categories store references to the canonical Puzzle ID. They never copy its FEN, solution, provenance, or source metadata into another feature-owned library.
- The browser never receives database access or unrestricted Puzzle records. The training module exposes only purpose-specific queries for Random, By Category, an exact referenced Exercise, and a selected Training List.
- Solutions remain server-side. A practice view receives the position and presentation metadata it needs, while submitted moves are validated through the training interface before protected solution data is revealed.
- Random access over the full corpus must use indexed sampling rather than scanning or `ORDER BY RANDOM()` across millions of rows. Category lookup, account classification exclusion, and exact Puzzle references must also be indexed.
- Own Puzzles are not stored in the Lichess Puzzle Library and are never deduplicated against it. They occupy separate tables in the same SQLite database, keep game-owned provenance, and are available only through the Own Puzzles interface. Their source game and ply prevent duplicate Own Puzzle records for the same analyzed position.

The reference shape is:

| Concern | Stored value |
|---|---|
| Canonical Lichess Puzzle | One `training_puzzle` row with its internal Puzzle ID |
| Theme or opening category | A tag row containing the Puzzle ID and classification key |
| Course or method Exercise | An Exercise-to-Puzzle reference containing the Puzzle ID |
| Account Training List | One account-and-Puzzle state row containing the Puzzle ID, current list, counter, and strikes |
| Attempt history | Attempt rows containing the account ID and Puzzle ID |
| Own Puzzle | A separate Own Puzzle row plus its source game and ply; never a `training_puzzle` row |

No row outside the canonical Lichess Puzzle record repeats its FEN, solution line, source URL, or provenance. Own Puzzles deliberately have their own position and solution storage because they are a separate game-derived kind.

## Resolved decisions

- Every Puzzle attempt records elapsed solving time, Wrong Moves, hints used, and whether the complete solution was played correctly.
- Normal Lichess Puzzles and Own Puzzles use the same performance categories, counters, strikes, movement rules, and session behavior. They apply those rules independently because Own Puzzles are a separate game-derived kind with separate storage, progress, and dashboards; they retain their source-game relationship and never mix into the normal-Puzzle dashboard.
- A rejected solution move is called a **Wrong Move** in both dashboards. Training does not classify attempted Puzzle moves as misses, mistakes, or blunders.
- One Wrong Move places a Puzzle in the Wrong Moves Training List. Two or more Wrong Moves during any single attempt move it immediately to the Hard-to-Solve List.
- Training Lists are mutually exclusive. Moving a Puzzle to Hard to Solve removes it from its previous list; the Hard-to-Solve List is the only active list for that Puzzle.
- The Hard-to-Solve rule applies wherever the attempt starts. It therefore handles a Puzzle encountered in random practice and a Puzzle already being repeated from the Wrong Moves list in the same way.
- Solve Time begins when the board becomes interactive and ends only when the complete solution line has been played correctly. Timing only the first move is not sufficient.
- The fast-solve time limit scales with the number of required owner moves in the solution: 6 seconds for the first move plus 5 seconds for each additional move. Forced opponent replies do not add time. A Solve Time at or below the calculated limit qualifies.
- Using any hint moves the Puzzle directly to the Hard-to-Solve List regardless of its Solve Time or Wrong Move count. This rule is final.
- Worth Repeating is reserved for a complete solution with no Wrong Moves and no hints whose Solve Time exceeds the calculated fast-solve limit.
- Hard to Solve, Wrong Moves, and Worth Repeating each require ten consecutive Successful Repetitions to advance. Every advancement starts the next list's counter at zero. The progression order is Hard to Solve → Wrong Moves → Worth Repeating → Retiring → Mastered, and Mastered is the final list of Puzzles that no longer require active repetition.
- A new Puzzle completed with no Wrong Moves, no hints, and a Solve Time at or below its fast-solve limit goes directly to Retiring with zero successes and zero strikes.
- In Wrong Moves, a clean solution adds one Successful Repetition. One Wrong Move keeps the Puzzle in Wrong Moves but resets its consecutive-success counter to zero; two Wrong Moves move it immediately to Hard to Solve.
- In Worth Repeating, only a solution completed within the fast-solve limit adds one Successful Repetition. Any failed repetition resets its consecutive-success counter to zero, and the Puzzle does not advance to Retiring until it reaches ten consecutive successes.
- Exactly one Wrong Move during a Worth Repeating attempt moves the Puzzle immediately to Wrong Moves with a zero counter. Two Wrong Moves or any hint move it to Hard to Solve with a zero counter.
- In Hard to Solve, completing the solution with zero or one Wrong Move adds one Successful Repetition. Reaching two Wrong Moves keeps it in Hard to Solve and resets its consecutive-success counter to zero.
- Every repetition attempt belongs to the Puzzle's current Training List. Using a hint from any other list moves the Puzzle immediately to Hard to Solve with a zero counter; using a hint while already in Hard to Solve keeps it there and resets its counter to zero.
- In Retiring, only a complete solution with no Wrong Moves, no hints, and a Solve Time at or below the fast-solve limit adds one Successful Repetition. A slower otherwise-correct solve records one time strike instead of moving the Puzzle immediately. The second time strike moves it back to Worth Repeating.
- Retiring successes are cumulative. One time strike does not remove successes already earned; reaching five successful completions moves the Puzzle to Mastered even when one strike remains.
- Exactly one Wrong Move during a Retiring attempt moves the Puzzle immediately to Wrong Moves with a zero counter. Two Wrong Moves or any hint move it to Hard to Solve with a zero counter.
- Mastered is terminal. A Mastered Puzzle stays there with no further automatic rules, list movement, or performance-training tag.
- The Normal Puzzles Mastered view groups Puzzles by Theme and Opening Tag. The Own Puzzles Mastered view groups them by opening, game phase, and tactic label. The owner may choose a Mastered category and practice its Puzzles, but those optional attempts never change Mastered status or create repetition progress.
- Opening a Training List starts a new Practice Session with a freshly randomized Puzzle order; the previous session's order is not reused.
- A Practice Session presents every Puzzle in its selected Training List once before reshuffling or repeating any Puzzle.

- A normal Puzzle offers one hint level, which highlights the piece that makes the next correct owner move. (Owner decision, 2026-11-20.)
- Show solution counts as a hint. It reveals the remaining solution, completes the attempt, and applies the hint rule, so a first attempt goes to Hard to Solve and a repetition attempt moves to or stays in Hard to Solve with a zero counter. (Owner decision, 2026-11-20.)

- Any mating move solves a Puzzle, as on Lichess, even when it differs from the stored solution move. (Owner decision, 2026-11-20.)
- Solve Time starts when the server creates the attempt. The Puzzle interface starts the attempt only once the board is interactive. (Owner decision, 2026-11-20.)

### First completed attempt

An unfinished Puzzle remains unclassified. The first completed attempt applies the highest-priority matching result below and starts the destination list with zero successes and zero strikes:

| Priority | First-attempt result | Destination |
|---:|---|---|
| 1 | Any hint | Hard to Solve |
| 2 | Two or more Wrong Moves | Hard to Solve |
| 3 | Exactly one Wrong Move | Wrong Moves |
| 4 | No Wrong Moves or hints, over the fast-solve limit | Worth Repeating |
| 5 | No Wrong Moves or hints, at or under the fast-solve limit | Retiring |

The first completed attempt classifies the Puzzle; it does not count as one of the repetitions required to advance from its destination list.

## Dashboard structure

- The main Puzzle dashboard has Random, By Category, Own Puzzles, and Repetition modules. It does not have a general New Puzzles module.
- The Own Puzzles module contains every game-derived Puzzle and a New Own Puzzles shortcut. New Own Puzzles presents only Own Puzzles that have never been attempted, orders them by newest source game first, and does not repeat them.
- New Own Puzzles from Lichess and chess.com games are shown together. The owner can filter them by site and choose to solve them newest first or in random order; the list itself stays newest first (owner decision, 2026-11-23).
- Completing My Moves analysis automatically creates an Own Puzzle for every owner inaccuracy, mistake, and blunder; no manual Add action is required.
- New Own Puzzles offers a Discard action before classification. Discarding excludes that Own Puzzle from first-attempt training and prevents it from entering any repetition list.
- Discarded Own Puzzles remain in a recoverable Discarded section. Restore returns a Puzzle to New Own Puzzles with no category or repetition progress.
- A first attempt removes an Own Puzzle from New Own Puzzles and classifies it through the shared performance rules. Its category then appears in the Own Puzzles module summary and in the matching list on the full Own Puzzles dashboard.
- Every Own Puzzle provides an Open source game action that returns to the exact Game Review position from which the Puzzle was created.
- Repetition offers two choices: Normal Puzzles and Own Puzzles. Each choice opens its own repetition dashboard.
- The two repetition dashboards contain only Puzzles already placed in a performance Training List. They do not serve unclassified Puzzles.
- Normal Puzzles and Own Puzzles remain separate in storage, interface, classification history, lifecycle, and selection results while applying the same performance rules. Own Puzzles retain their relationships to the owner's games.
- Own Puzzles are categorized by opening, game phase, and tactic label in addition to retaining their source-game relationship.
- Random draws only unclassified normal Puzzles. After the first Random attempt classifies a Puzzle, Random never selects it again.
- Random presents exactly one Puzzle at a time. Completing it creates its first classification before the next Random Puzzle is requested.
- Opening a new Puzzle does not classify it. Leaving, closing, or interrupting an unfinished first attempt keeps the Puzzle unclassified and eligible for Random or By Category later.
- Reopening an unfinished Puzzle starts a fresh Solve Time from zero. Because no earlier completion exists, the next completed solution remains its first classifying attempt.
- By Category can select any normal Puzzle except a Mastered Puzzle, including unclassified Puzzles and Puzzles already in an active repetition list. It never mixes in game-derived Own Puzzles.
- Completing an already-classified Puzzle through By Category applies the rules of its current Training List. The entry point does not create a separate counter or classification history.
- Completing a shared Puzzle through a Training Method such as Grandmaster Thinking Method updates the same normal-Puzzle performance state. Wrong Moves, hints, Solve Time, counters, and list movement use the global rules, while authored course and lesson completion remain separate.

## Analysis and Own Puzzle creation

- Game retrieval remains idempotent and never creates a duplicate stored game.
- My Moves and Opponent Moves are independent analysis perspectives, and each perspective may be analyzed only once per game. Reanalysis of an already completed perspective is not allowed; when one perspective exists, only the missing perspective remains available.
- Own Puzzles are created only from My Moves findings. Opponent Moves analysis never creates Own Puzzles from the opponent's errors.
