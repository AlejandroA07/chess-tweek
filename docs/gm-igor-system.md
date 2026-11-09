# GM Igor Smirnov training system — research and planning handoff

**Status:** Research-backed product proposal, not yet canonical product scope
**Prepared:** 2026-11-09
**Intended reader:** The next planning chat for Chess Tweek

## Handoff objective

Design a separate Chess Tweek module and first-class view that adapts the useful structure of GM Igor Smirnov's publicly described training system without copying his paid course content, branded curriculum, prose, videos, screenshots, or exercises.

The owner direction is:

- Treat this as a distinct product capability rather than adding scattered rules to the existing report and puzzle screens.
- Give it its own view, likely a new **Training** destination in the primary navigation.
- Integrate existing Chess Tweek evidence and exercise capabilities through public module interfaces.
- Use the player's own analyzed games and legitimate Lichess exercise data as the training material.
- Continue the detailed product and architecture design in a separate chat.

This document preserves the research, identifies verified facts versus deductions, proposes a module seam, and lists decisions the next chat must close before changing the canonical planning documents.

## Executive conclusion

The public material does not reveal a secret exercise database or a unique grading algorithm. The strongest reusable idea is a **focus-first training architecture**:

1. Give the player a small thinking framework.
2. Teach one decision rule at a time.
3. Demonstrate it with positions and games.
4. Require practical tasks immediately after the explanation.
5. Apply the idea consistently in real games.
6. Progress from general decision-making to middlegame plans and then specialized skills.

Remote Chess Academy states the central principle directly: the goal is not to finish its full library, but to study a smaller amount carefully enough to use it in games. Its recommended core order is Quick Start resources, *Level Up Your Chess*, and then *3 Steps to 2000 Elo*.

Chess Tweek can improve on this model because it has direct evidence from the owner's games. The app can select a narrow priority from an immutable ten-game report, train it first on the owner's error positions, add related Lichess positions for transfer, set one focus for upcoming games, and measure whether the same problem recurs in a later report.

## Research boundaries

This research used only publicly accessible first-party Remote Chess Academy pages, its public roadmap PDF, and official Lichess sources.

It did not:

- access purchased lessons or private student groups;
- bypass authentication, payment, or access controls;
- download or reproduce paid exercises;
- assume marketing claims are independently proven outcomes;
- treat coaching slogans as universal chess laws.

Course titles and public lesson titles appear below only to document the source system. Chess Tweek should use original product names and original explanations.

## Verified public system

### Published sequence

Remote Chess Academy's public two-page Super Pack roadmap gives this order:

1. **Quick Start resources**
   - Busy Adult Study Plan
   - How to Study Chess Efficiently
2. **Phase 1 — Core Thinking System**
   - Level Up Your Chess
   - 3 Steps to 2000 Elo
3. **Phase 2 — Essential Middlegame Training**
   - Top 25 Middlegame Concepts
   - 7 Keys to Victory
4. **Phase 3 — Advanced Chess Mastery**
   - Attack and Win
   - Grandmaster's Positional Understanding
   - Calculate Till Mate
   - Self-Taught Grandmaster
   - Your Winning Plan
5. **Optional archive and supporting material**
   - Openings
   - Endgames
   - Defense
   - Beating stronger opponents
   - Converting advantages
   - Model-player and premium masterclass recordings

The public roadmap describes Phase 1 as foundation, thinking process, and structured improvement; Phase 2 as practical middlegame understanding and stronger decisions; and Phase 3 as positional understanding, calculation, attack, and advanced training.

### Published study guidance

The public Start Here page says:

- students do not need to study everything at once;
- the recommended order is Quick Start resources, Phase 1, Phase 2, then Phase 3;
- the quick-start resources should not delay beginning Phase 1;
- a busy adult can use a simple 30-minute study plan;
- the preferred core path is *Level Up Your Chess* followed by *3 Steps to 2000 Elo*;
- consistency and application matter more than consuming the complete library.

The masterclass landing page frames the problems it is trying to solve as:

- random videos without a coherent improvement plan;
- repeated blitz games without reflection;
- tactics practice that does not transfer into real games;
- switching openings instead of building a reliable thinking process.

Its stated coverage includes blunder avoidance, calculation, practical opening play, plans, and converting advantages.

## Public curriculum details

### Level Up Your Chess

The public product page describes 26 theory lessons divided into six sections and six practical task sets containing more than 50 exercises or games.

#### Improvement fundamentals

- Why You Are Bad at Chess
- Use It! Don't Lose It!
- Look Before You Leap
- Practice Makes Perfect. Here's How You Do It
- How to Save a Losing Game?
- How to Avoid Blunders?

#### Opening

- The Main Guide to Chess Openings
- My Favorite Rule
- How Do You Attack in the Opening?

#### Middlegame

- Principles of Attack
- Zero Tolerance Policy
- Calculation Training

#### Endgame

- Endgame Strategy
- What You Should Do in the Endgame
- The Main Endgame Rule
- Complete Guide to Chess Endgames

#### Study method

- 3 Reasons Why Your Chess Game Is NOT Improving
- Your Chess Study Plan
- How to Analyze Your Chess Games?

#### Training and model players

- How to Play like Capablanca?
- How to Beat 1600 Rated Players?
- How Do Grandmasters Think?
- Why Carlsen Is so Good at Chess
- How to Beat 1300 Rated Players?
- How to Play like Morphy?

The important structural fact is the pairing of short theory lessons with practical task sets. The product page says the tasks are required to understand and remember the ideas.

### 3 Steps to 2000 Elo

The public page describes:

- 28 theory videos;
- 10 practice videos;
- more than nine hours of material;
- five practical task groups;
- more than 50 analyzed games;
- practice covering planning, attack, defense, tactics, calculation, and endgames.

#### Step 1 — Foundation and progress roadblocks

1. How Do You Progress in Chess?
2. This Is My BIGGEST Secret to Success
3. The Perfect Chess Style
4. Wishful Thinking
5. When and What to Calculate?
6. Pattern Interruption
7. Most Common Mistakes for Under 1800 Elo
8. The 2 Things You Should Stop Doing in Chess
9. Summary

#### Step 2 — Calculation, openings, structures, and middlegame play

10. Intro
11. Tactics vs Calculation
12. Calculation in Positional Games
13. How to Calculate in Difficult Positions
14. Common Mistakes in the Opening
15. Example of Pawn Structure I
16. Example of Pawn Structure II
17. The Importance of the Classic Players in Our Understanding
18. Middlegame Patterns
19. Middlegame Patterns II
20. The Calm Play even with Hanging Pawns
21. Improving Your Position Step by Step
22. When the Slow Moves Win the Game

#### Step 3 — Advanced practical decisions

23. Intro
24. Understand Your Chess Personality
25. The Key Positional Topic that Makes the Difference
26. Fundamental Ideas on the Attack
27. How to Create Better Plans from Your Opponent
28. How to Defend Successfully

### Other publicly described course roles

- **Top 25 Middlegame Concepts:** a compact collection of recurring practical middlegame plans.
- **7 Keys to Victory:** reduces play across all phases to seven main guidelines, with practice intended to teach application rather than recognition alone.
- **Attack and Win:** covers recognizing attack preconditions, finding attacking moves, opening lines around an uncastled or castled king, weak squares, central play, time, and bringing enough pieces into the attack.
- **Your Winning Plan:** organizes planning around the nature of the center and recurring pawn structures.
- **Calculate Till Mate:** calculation, tactics, and visualization.
- **Self-Taught Grandmaster:** training methods and building a longer-term study program.
- **An Endgame Expert:** an endgame thinking process rather than opening-style memorization.
- **Boleslavsky Pawn Structure:** demonstrates the broader principle of studying recurring structures and plans instead of memorizing isolated move sequences.

## Recurring public heuristics

These rules appear repeatedly in Remote Chess Academy's public articles and course descriptions. They are useful as attention prompts, not deterministic grading rules.

| Public heuristic | Useful interpretation | Required caution |
|---|---|---|
| Look before you leap | Run an opponent-threat and anti-blunder check before committing a move. | The exact checklist and timing must be defined by Chess Tweek. |
| To take is a mistake | Avoid automatic, unmotivated exchanges that improve the opponent's position. | Many exchanges are correct or forced; never grade all captures negatively. |
| Accomplish opening tasks | Develop minor pieces, secure the king, and connect rooks before launching an unjustified attack. | Concrete tactics can override general opening priorities. |
| Go forward and attack | Prefer active candidate moves that enter the opponent's territory or create a threat. | Retreat, prophylaxis, and consolidation may be best. |
| Offense is the best defense | Consider a stronger counter-threat before accepting a purely passive defense. | First verify that the counter-threat actually answers the opponent's threat. |
| Improve the least-active piece | When forcing play is absent, improve the piece contributing least. | Activity must be evaluated in the context of the position and plan. |
| Occupy the center | Use central control to improve mobility and switching capacity. | Closed centers and flank operations can change the priority. |
| Restrict the opponent | Reduce opposing piece mobility while improving one's own coordination. | Avoid a rule that rewards restriction without checking tactical soundness. |
| Checks, captures, threats | Begin candidate generation with forcing moves. | Candidate generation is not move selection; candidates still require validation. |
| Outnumber the defenders | Bring enough attackers to the target before committing. | Counting pieces alone ignores value, geometry, tempo, and tactical details. |

One public middlegame mnemonic is **IGOR**:

- Improve the least-active piece.
- Go forward.
- Occupy the center.
- Restrict the opponent's pieces.

Chess Tweek should not use the branded mnemonic. It can express the underlying concepts in original language where they are supported by the player's evidence.

## Deduced training loop

The following is an inference from the public roadmap, public curricula, and task descriptions rather than a published proprietary algorithm:

1. **Focus:** choose a small subject instead of consuming unrelated material.
2. **Recall:** state one compact decision rule or checklist.
3. **Demonstrate:** show annotated examples where the rule changes move selection.
4. **Retrieve:** ask the player to solve positions without seeing the answer.
5. **Compare:** reveal the reference line and explain the decision difference.
6. **Transfer:** use positions that look different but exercise the same concept.
7. **Apply:** set one focus for upcoming real games.
8. **Reassess:** inspect later games for recurrence and choose the next focus.

This loop is the main idea worth adapting. Chess Tweek can make it more rigorous by selecting focus areas from immutable report evidence and measuring later recurrence per analyzed owner move.

## Legitimate exercise data

### Owner-game exercises

Chess Tweek's planned Mistake/Blunder Puzzles are the strongest personalized material:

- each exercise begins immediately before an owner mistake or blunder;
- the player replaces the error with a five-ply line;
- up to three prepared lines can support close alternatives;
- attempts, hints, give-up, solved state, and local difficulty are already planned;
- the exercise retains exact provenance to the immutable report and analysis version.

These positions can teach the player's actual failure patterns rather than generic motifs.

### Lichess puzzles

The official Lichess open database publishes rated, tagged chess puzzles under Creative Commons CC0. At the time of research it listed 6,193,530 puzzles.

Each puzzle row contains:

- puzzle ID;
- FEN;
- solution moves in UCI format;
- rating and rating deviation;
- popularity and play count;
- themes;
- source-game URL;
- opening tags;
- daily-puzzle date when applicable.

Important format detail: the stored FEN is the position before the opponent's setup move. The presentation position is obtained by applying the first move in the row; the second move begins the player's solution.

Useful official Lichess categories include:

- phases: opening, middlegame, and endgame;
- tactical motifs: fork, pin, skewer, hanging piece, discovered attack, trapped piece, capture the defender;
- attack: exposed king, kingside attack, queenside attack, attacking f2 or f7, sacrifice;
- advanced decisions: defensive move, quiet move, attraction, clearance, deflection, interference, intermezzo, zugzwang;
- endgames: rook, bishop, pawn, knight, queen, and queen-and-rook endgames;
- goals: equality, advantage, crushing, and checkmate;
- length and opening tags.

Lichess puzzles are strongest for tactics and forcing decisions. They are not sufficient by themselves for positional subjects such as improving a passive piece, choosing a pawn break, or making a long-term plan. Owner-game positions and original instructional examples must fill that gap.

The existing Chess Tweek plan already requires safe mappings to supported official Lichess themes, deduplication, honest shortfalls, server-side solutions, local attempt storage, and no Lichess puzzle-result submission. Preserve those rules.

## Proposed Chess Tweek capability

### Working product name

Use **Training Roadmap** as the working user-facing name and `training-roadmap` as the working module name. Both are proposals for the next planning chat to confirm.

Avoid Remote Chess Academy course names and branded phrases in the product.

### Purpose

The Training Roadmap turns evidence from analyzed games into a narrow, repeatable training block. It should answer:

- What is the most important thing for me to train now?
- What evidence supports that choice?
- What should I do in this session?
- What should I remember in my next games?
- Did this problem recur in the next comparable report?

### Module seam

The module should be deep: a small interface hides focus selection, block construction, progress tracking, evidence explanations, and recurrence comparison.

It must integrate through public interfaces at the composition root:

```text
game-review public interface ──┐
                              ├── composition ──> training-roadmap public interface
training public interface ────┘                         │
                                                       └── Training Roadmap view
```

The module must not import `game-review` or `training` internal paths or repositories.

### What the module owns

- roadmap identity and lifecycle;
- the selected baseline report ID and copied evidence summary;
- one or more focus blocks;
- original Chess Tweek recall cards and decision checklists;
- task references and their presentation order;
- block progress and completion;
- next-game focus cards;
- later comparison snapshots and recurrence results;
- module-owned tables and migrations.

### What the module does not own

- Lichess authentication or tokens;
- games, analysis findings, or immutable reports;
- report-owned Mistake/Blunder exercises;
- report-owned Related Lichess Puzzles;
- normal Daily/Mixed Lichess puzzles;
- Stockfish execution;
- validation rules or attempt histories owned by another module;
- copied Remote Chess Academy content.

### Candidate public interface

Keep the final interface smaller than the implementation. A useful starting shape is:

- create a roadmap from an authorized, immutable evidence snapshot;
- load the current roadmap view for the active account;
- record roadmap-owned actions such as accepting a focus, completing a recall step, or saving a next-game focus;
- compare a completed roadmap with a later authorized report snapshot;
- archive or delete a roadmap with an explicit dependency result.

Do not expose internal scoring, task-allocation, or storage mechanics to callers. The next chat should design the interface at the behavior level before choosing Java interfaces or frontend TypeScript types.

## Proposed standalone view

Add a first-class **Training** destination to the main navigation, subject to design review. It should remain visually consistent with `docs/app-material/DESIGN.md` and avoid a gamified course-platform aesthetic.

### View sections

1. **Current focus**
   - one training lane;
   - plain-language reason;
   - supporting report evidence;
   - sample size and report date;
   - link back to exact errors.
2. **Today's block**
   - estimated duration;
   - recall step;
   - owner-game positions;
   - transfer positions;
   - review step;
   - next-game focus.
3. **Roadmap progress**
   - completed blocks;
   - current block;
   - future blocks without implying a fixed universal curriculum;
4. **Evidence and history**
   - baseline report;
   - attempt summaries from the owning modules;
   - later comparison reports;
   - recurrence trend with exact sample sizes.
5. **Controls**
   - Build Roadmap from Report;
   - Start or Continue Block;
   - Open Source Report;
   - Change Focus when allowed;
   - Set Next-Game Focus;
   - Compare with Later Report;
   - Archive Roadmap;
   - Delete Roadmap.

### Required states

- no eligible report;
- eligible report available but no roadmap created;
- roadmap creation preview;
- active block;
- interrupted block that can resume;
- owner exercises unavailable or deleted;
- related Lichess package pending or failed;
- fewer transfer positions than requested;
- completed block;
- waiting for later games and a later report;
- comparison ready;
- no comparable evidence in the later sample;
- archived roadmap;
- account or source-integrity mismatch;
- offline state with already stored content still readable.

## Proposed 30-minute focus block

The exact numbers require owner approval and testing. A sensible initial proposal is:

1. **Recall — 5 minutes**
   - one original decision rule;
   - one short checklist;
   - one previously failed position when available.
2. **Personal practice — 15 minutes**
   - three to five positions from the owner's report-owned Mistake/Blunder package;
   - prioritize unsolved, failed, hinted, or repeated-theme positions;
   - validation remains with `game-review`.
3. **Transfer practice — 5 minutes**
   - three to five related Lichess positions;
   - use supported themes or opening tags only;
   - validation and solution protection remain with the owning module.
4. **Review — 5 minutes**
   - summarize errors without inventing a free-form AI coach;
   - save one next-game focus;
   - identify what evidence will be checked later.

The time split should be configurable only if a real user need is established. Avoid creating a complex study-plan builder in the first slice.

## Proposed original training lanes

| Chess Tweek lane | Primary evidence | Suitable Lichess transfer themes | Notes |
|---|---|---|---|
| Blunder Prevention | Hanging pieces, missed opponent threats, severe one-move losses | `hangingPiece`, `defensiveMove`, `intermezzo`, `capturingDefender` | The recall card can use an original anti-blunder checklist. |
| Opening Readiness | Owner opening errors and incomplete development | `opening`, `castling`, `attackingF2F7`, verified opening tags | Do not turn general principles into rigid opening grading. |
| Calculation and Tactics | Tactical mistakes, missed forcing sequences, visualization failures | `fork`, `pin`, `skewer`, `discoveredAttack`, `deflection`, `clearance`, `interference` | Candidate generation and line validation are distinct steps. |
| Middlegame Planning | Repeated positional errors and passive-piece problems | `middlegame`, `quietMove`, `advancedPawn`, `trappedPiece` | Lichess coverage is incomplete; owner positions should dominate. |
| Attack and Defense | Missed attacks, exposed kings, passive responses, failed defense | `kingsideAttack`, `queensideAttack`, `exposedKing`, `sacrifice`, `defensiveMove` | Count attackers and defenders only as one cue among several. |
| Endgame Technique | Endgame mistakes and missed conversions or drawing resources | `endgame`, piece-specific endgames, `promotion`, `zugzwang`, `equality` | Distinguish saving a draw from converting a win. |
| Converting an Advantage | Loss of a clearly favorable expected score | `advantage`, `crushing`, `quietMove` | Owner positions are more useful than generic tactics for this lane. |
| Game Review Habits | Repeated unexamined error classes and review completion | Primarily owner-game evidence | This may be a workflow lane rather than a chess-theme lane. |

Lane names, eligibility, and theme mappings must be verified against the final classifier and supported Lichess interfaces. Never fall back to unrelated `mix` puzzles.

## Candidate deterministic selection process

This is a planning starting point, not an accepted algorithm:

1. The owner explicitly selects an immutable Overall Games Analysis report or clicks **Build Training Roadmap** from it.
2. Composition obtains an authorized evidence snapshot through the `game-review` public interface.
3. The roadmap module considers only owner findings and the report's existing deterministic training priorities.
4. The module proposes one focus lane with severity, frequency, sample size, and exact supporting errors.
5. The owner reviews and accepts the proposed focus before a roadmap is stored.
6. The module creates task references to eligible report-owned exercises and related Lichess puzzles without copying their solutions or attempt data.
7. Each block presents a recall step, personal practice, transfer practice, and a next-game focus.
8. A later comparison is manual and requires a second eligible immutable report.
9. Comparison reports raw counts, rates per game, rates per 100 analyzed owner moves, and sample sizes. It does not claim causal improvement from a small sample.

Questions such as minimum evidence, tie-breaking, focus duration, when a focus is mastered, and whether the owner can override the proposed focus remain open.

## Data ownership and lifecycle risks

The most important architecture issue is that a roadmap may reference data owned by two existing modules.

### Report deletion

A report currently owns both of its puzzle packages and their attempts. Deleting the report cascades those records. A roadmap that references them therefore needs an explicit policy.

Options for the next chat to evaluate:

1. **Block report deletion while an active roadmap depends on it.**
   - Strong integrity.
   - Adds a cross-module dependency that Admin must display and resolve through composition.
2. **Archive or invalidate dependent roadmap tasks when the report is deleted.**
   - Preserves current report deletion semantics.
   - A roadmap can lose its personalized exercises.
3. **Copy complete exercises into the roadmap module.**
   - Keeps the roadmap self-contained.
   - Duplicates protected solutions, provenance, validation, and attempt behavior; not recommended.

The current recommendation is option 1 or an explicit owner-confirmed combination of 1 and 2. Avoid copying exercise payloads unless a later design proves the duplication necessary.

### Cross-module identifiers

Follow the existing SQLite ownership decision:

- store scoped external or module IDs as copied values;
- do not create cross-module database foreign keys;
- perform dependency checks through public interfaces assembled at composition;
- include roadmap dependencies in Admin before shipping the capability;
- keep every query account-scoped.

### Deletion closure

Deleting a roadmap should delete only roadmap-owned plans, focus blocks, comparisons, and local roadmap actions. It must not delete source games, reports, puzzle packages, or attempts owned elsewhere.

Deleting a report with dependent roadmaps must show the exact closure and result before confirmation. No dangling active task may silently remain.

## Security, privacy, and fair-play requirements

- Roadmap creation, puzzle retrieval, and comparison are explicit owner actions.
- No live-game state, Board API, in-app play, or real-time assistance is introduced.
- The module consumes completed-game evidence only.
- Every command and query is scoped to the active authorized account.
- Untrusted Lichess fields and copied identifiers are validated at the public interface.
- Puzzle solutions remain server-side until validation, an authorized hint, or resolution.
- OAuth tokens remain outside roadmap storage, logs, and client JavaScript.
- Errors use safe summaries and never expose process output, tokens, or hidden solutions.
- Attempt results remain local and are not submitted to Lichess.
- No paid AI or free-text imitation of GM Smirnov is introduced.
- Coaching text is deterministic, original, and tied to verified evidence.

## Product and architecture decisions still open

The next planning chat should close these before editing canonical scope:

1. Is `training-roadmap` the right module name and **Training** the right navigation label?
2. Can a roadmap be created only from an Overall Games Analysis report, or also from a single game later?
3. Is there exactly one active roadmap per account, or can several coexist?
4. Does the owner accept the proposed focus, select from ranked options, or freely choose a lane?
5. What evidence threshold makes a focus eligible?
6. How many blocks belong to one focus, and what completes or retires it?
7. Are 30-minute blocks fixed in v1 of the module?
8. Which recall cards and checklists are authored for the first slice?
9. Which positional lanes can be supported honestly by existing classifier evidence?
10. Does the module reuse only report-owned related puzzles, or may it request an additional roadmap-owned transfer set?
11. What happens to an active roadmap when its source report is deleted?
12. How are roadmap dependencies represented in Admin?
13. What constitutes a comparable later report when games, speeds, openings, or rating bands differ?
14. Which recurrence metrics are shown without overstating improvement?
15. Should the feature be inserted before Admin and final hardening, shifting the existing stage numbers?
16. What new view prompt and canonical design acceptance checks are required?

## Suggested delivery placement

If accepted for the planned v1, the cleanest dependency position is after the report-owned and normal puzzle infrastructure and before Admin dependency management:

- needs Overall Games Analysis;
- needs Mistake/Blunder Puzzles;
- needs Related Lichess Puzzles;
- likely needs normal puzzle infrastructure if it reuses shared player behavior;
- must land before Admin so its dependencies and deletion behavior are covered;
- must land before final hardening so accessibility, security, performance, and real-data verification include it.

That likely means inserting a new planning slice after current Stage 9 and renumbering current Stages 10–12. This is a proposal, not an authorized plan edit.

## Draft acceptance contract

The next chat can refine these observable checks:

- The owner can manually build a roadmap from one authorized immutable report.
- Roadmap creation proposes one focus and shows its exact evidence, severity, frequency, and sample size.
- Opponent findings never influence focus selection or recurrence measurement.
- The owner approves the focus before persistence.
- A training block contains an original recall step, personalized owner positions, related transfer positions when available, and one next-game focus.
- Exercise content and validation remain owned by their source modules.
- Fewer related puzzles produce an honest short block rather than unrelated filler.
- A failed Lichess retrieval does not hide or destroy the roadmap or source report.
- Roadmap progress survives restart and remains account-scoped.
- A later comparison requires an explicit owner action and an immutable later report.
- Comparison shows raw counts, normalized rates, and sample sizes without claiming guaranteed rating gain or causation.
- Report and roadmap deletion expose their exact dependency closure and leave unrelated data untouched.
- No live-play, automatic analysis, paid AI, or Lichess write permission is added.
- All user-facing copy and instructional material are original to Chess Tweek.
- The standalone view follows the canonical visual system and passes keyboard, contrast, long-content, empty, loading, failure, and offline-state checks.

## Required planning updates after approval

Do not edit these merely because this handoff exists. After the owner closes the open decisions, update them together so they remain consistent:

- `docs/planning/01-chess-tweek.md`
  - product scope;
  - Training Roadmap behavior;
  - lifecycle and acceptance checks;
  - ordered slice list.
- `docs/planning/02-architecture.md`
  - module responsibility and interface;
  - data ownership;
  - dependency direction;
  - report/roadmap deletion behavior;
  - architecture-test obligations.
- `docs/planning/03-delivery-plan.md`
  - dedicated branch;
  - prerequisites;
  - deliverables;
  - acceptance gate;
  - renumbered later stages if applicable.
- `docs/app-material/view-prompts.md`
  - standalone Training view prompt and all required states.
- `docs/app-material/DESIGN.md`
  - only after an owner-approved design is selected; keep it the sole visual source of truth.
- `AGENTS.md`
  - only when the accepted architecture or verified commands actually change.

## Next-chat starting instructions

Use this as the opening prompt in the new chat:

> Read `AGENTS.md`, `docs/gm-igor-system.md`, and the three files in `docs/planning/` completely. We are planning a standalone Training Roadmap module and first-class Training view based on the research handoff. First grill the unresolved product and lifecycle decisions in `docs/gm-igor-system.md`. Do not add application code or dependencies. Once the decisions are explicit, update the canonical product specification, architecture decisions, delivery plan, and view prompts together. Preserve existing work, stay on `feature/project-planning`, do not commit or push, and do not claim application verification because Stage 1 has not begun.

The next chat is complete only when:

- every open decision required for scope, data ownership, lifecycle, and acceptance has an explicit answer;
- the standalone module has a small public interface and no internal cross-module imports;
- canonical planning documents agree on behavior, ownership, dependencies, and delivery order;
- the new view prompt covers normal, empty, loading, failure, offline, resume, completion, and deletion-impact states;
- no implementation code or dependency has been added.

## Sources

All sources were accessed on 2026-11-09.

### Remote Chess Academy first-party sources

- [GM Igor Smirnov's Masterclass landing page](https://chess-teacher.net/gm-igor-smirnov-masterclass/)
- [Super Pack Improvement System — Start Here](https://chess-teacher.net/super-pack-improvement-system/)
- [Super Pack Roadmap PDF](https://chess-teacher.net/wp-content/uploads/2026/04/Chess-Curriculum-Roadmap-Compressed-Version.pdf)
- [Level Up Your Chess public product page and curriculum](https://online.chess-teacher.com/courses/level-up-your-chess/)
- [3 Steps to 2000 Elo public product page and curriculum](https://chess-teacher.net/3-steps-to-2000-elo-rca/)
- [Recommended course sequence](https://chess-teacher.com/knowledge-base-rca/in-what-sequence-do-you-recommend-the-learning-of-your-courses/)
- [Best Chess Courses by GM Igor Smirnov](https://chess-teacher.com/best-chess-courses-by-gm-igor-smirnov/)
- [Three public practical rules](https://chess-teacher.com/reach-2000-elo/)
- [Two public thinking rules](https://chess-teacher.com/key-chess-rules/)
- [IGOR middlegame strategy](https://chess-teacher.com/chess-middlegame-strategy/)
- [Vital chess principles](https://chess-teacher.com/chess-principles/)
- [Three-step grandmaster thinking process](https://chess-teacher.com/grandmaster-thinking/)
- [Attack and Win public curriculum](https://chess-teacher.net/attack-and-win-2/)
- [Boleslavsky Pawn Structure public curriculum](https://chess-teacher.net/boleslavsky-pawn-structure/)

### Official Lichess sources

- [Lichess open database and puzzle schema](https://database.lichess.org/)
- [Lichess Puzzle Themes](https://lichess.org/training/themes)
- [Lichess puzzle-theme source definitions](https://github.com/lichess-org/lila/blob/master/modules/puzzle/src/main/PuzzleTheme.scala)
