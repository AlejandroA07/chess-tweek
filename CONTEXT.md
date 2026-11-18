# Chess Tweek Training

Chess Tweek organizes reusable chess material separately from the authored methods and sessions that use it. These terms keep course content, pattern practice, and future training modes interoperable.

## Language

**Puzzle Library**:
The single curated catalog of reusable puzzles available to every training method and practice mode.
_Avoid_: Course puzzle database, method library

**Puzzle**:
An immutable chess position and validated solution line with provenance, rating, and classification metadata. It contains no method-specific teaching text.
_Avoid_: Exercise, lesson position

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
An account-scoped run through a selected portion of a Practice Set, with its own order and attempt progress.
_Avoid_: Lesson, deck
