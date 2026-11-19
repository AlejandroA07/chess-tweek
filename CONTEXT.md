# Chess Tweek Training

Chess Tweek organizes reusable chess material separately from the authored methods and sessions that use it. These terms keep course content, pattern practice, and future training modes interoperable.

## Language

**Puzzle Library**:
The single canonical local catalog of reusable puzzles available through controlled training interfaces to every method and practice mode. A source Puzzle is stored once and referenced everywhere else.
_Avoid_: Course puzzle database, method library

**Puzzle**:
An immutable chess position and validated solution line with provenance, rating, and classification metadata. It contains no method-specific teaching text.
_Avoid_: Exercise, lesson position

**Normal Puzzle**:
A Puzzle imported from the canonical Lichess Puzzle Library and trained through Random, By Category, a Training Method, or the Normal Puzzles repetition dashboard.
_Avoid_: Course puzzle, copied puzzle

**Own Puzzle**:
A game-derived Puzzle created from the owner's play that retains its relationship to the source game and remains separate from normal Puzzle Library practice.
_Avoid_: My Puzzle, report puzzle

**Theme**:
A canonical Lichess classification describing a puzzle's motif, phase, goal, or solution characteristic. One puzzle may have several themes.
_Avoid_: Category, pattern name

**Opening Tag**:
A canonical Lichess opening family or variation associated with a puzzle's source position. It is distinct from a tactical Theme.
_Avoid_: Opening Theme

**Puzzle Collection**:
A user-visible selection of Puzzle references organized by a Theme, Opening Tag, or another reusable training purpose.
_Avoid_: Puzzle copy, method library

**Exercise**:
A training presentation that references one Puzzle and supplies context-specific instructions, hints, and explanation.
_Avoid_: Puzzle copy

**Practice Set**:
A named, ordered collection of Exercises assembled for a teaching purpose.
_Avoid_: Puzzle Library, Puzzle Collection, random list

**Training Method**:
Authored instruction that uses one or more Practice Sets but does not own or duplicate their referenced Puzzles.
_Avoid_: Puzzle course

**Practice Session**:
An account-scoped run through a selected Practice Set, category, or Training List, with its own order and attempt progress.
_Avoid_: Lesson, deck

**Wrong Move**:
An attempted move that is not accepted as part of a Puzzle's solution. It is a training result shared by normal and game-derived Puzzles, not a Stockfish severity label.
_Avoid_: Miss, mistake, blunder

**Unclassified Puzzle**:
A Normal Puzzle with no completed attempt and therefore no Training List or Mastered state.
_Avoid_: New list, due puzzle

**Training List**:
A mutually exclusive performance group that the owner can choose to practice. A Puzzle has at most one active Training List within its normal or game-derived dashboard.
_Avoid_: Due queue, schedule, deck

**Hard-to-Solve List**:
The exclusive Training List for a Puzzle that receives multiple Wrong Moves in one attempt, regardless of where that attempt started.
_Avoid_: Difficult deck, blunder list

**Wrong-Moves List**:
The Training List for a Puzzle that has not yet been repeated cleanly after a one-Wrong-Move result.
_Avoid_: Mistake list, missed list

**Worth-Repeating List**:
The Training List for a correctly completed Puzzle that still requires consistently fast recognition.
_Avoid_: Slow list, review queue

**Retiring List**:
The final active repetition stage before a Puzzle becomes Mastered.
_Avoid_: Final practice deck

**Mastered List**:
The final category-performance summary for Puzzles demonstrated well enough that they no longer require active repetition, automatic movement, or a performance-training tag. The owner may optionally practice a selected Mastered category without changing those Puzzles' Mastered status.
_Avoid_: Retired list, completed deck

**Successful Repetition**:
A completion that satisfies the current Training List's advancement rule and adds one to its success counter.
_Avoid_: Attempt, lifetime solve

**Time Strike**:
A slower-than-limit, otherwise perfect Retiring attempt that counts toward demotion without erasing completed Retiring successes.
_Avoid_: Wrong Move, failure

**Solve Time**:
The elapsed time from when a Puzzle board becomes interactive until the complete solution line has been played correctly.
_Avoid_: First-move time, session duration
