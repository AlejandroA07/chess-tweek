# Training goals and scope review

This is the owner's refresher: why Chess Tweek exists, what the owner wants to train, what the current plan delivers, what free tools already do, and which parts of the plan are worth building. It was written on 2026-11-14 after Stage 4 Part 5a. It records findings and options; it does not change the product specification by itself. Decisions taken from it must be copied into `01-chess-tweek.md` and `03-delivery-plan.md`.

## 1. The goal

- Today: about 1300 rapid on chess.com.
- Goal: 2000 rapid on chess.com.
- Openings known: London System with White, Caro-Kann Defense with Black.
- Belief: to improve I need to see more on the board, know more patterns, and understand chess better.
- Constraint: free and preferably open source. Write as little code as possible; do not spend tokens on features I will not use.

## 2. What I said I want (owner wishes, 2026-11-14)

| ID | Wish |
|---|---|
| W1 | Analyze my own games, see what I missed, my mistakes and blunders, and understand why each move was wrong. |
| W2 | Turn my own mistakes into puzzles and repeat them until I recognize the position and stop making the same mistake. |
| W3 | Themed practice like Lichess Learn/Practice: checkmates, fundamental tactics, advanced tactics, pawn endgames, rook endgames, and more. |
| W4 | Patterns like the ones in the Duolingo chess course (example: section 5, unit 23, interference and intermezzo; attacks on the castled king; rook and knight checkmates), repeated many times until I spot them easily. |
| W5 | Mark patterns or puzzles I solve quickly as mastered, so practice focuses on the ones that are hard or that I do not know yet. |
| W6 | An opening trainer for my openings that shows the moves and the objectives (plans, ideas) of the opening. |
| W7 | See the most common move played in a position (in the opening or a whole game) so I can prepare for what opponents actually play. |
| W8 | Keep the Lichess connection and analyze games from there. |
| W9 | Have this file so I can refresh my memory of the goal and ideas. |

## 3. What the original plan delivers when it is finished

When all twelve stages in `03-delivery-plan.md` are done, Chess Tweek lets the owner:

1. Log in with Lichess (read-only) and download up to 50 finished Lichess games at a time. Done.
2. Browse and filter those games, and see Lichess ratings history and the record against bots. Done.
3. Run Stockfish on one game (my moves, opponent moves, or both) and store the inaccuracies, mistakes, and blunders with best moves and engine lines. Done (data only).
4. Open a game review page: board, move list, evaluation graph, statistics, every error with the best move and line, and fixed "coach cards" (Why?, What was the threat?, What should I train?). Next: Stage 4 Part 5b and the tactic-label/coach-card part.
5. Queue 2 to 20 games for analysis with pause/resume/cancel, restart recovery, thermal pausing, and a low-heat mode. Stage 5.
6. Build an "Overall Games Analysis" report from exactly 10 analyzed games: error rates per phase and opening, a few tactic labels (fork, hanging piece, missed/allowed mate, back rank), and a ranked list of what to train. Stage 6.
7. Solve one five-move exercise for every mistake and blunder in a report, with hints and Easy/Medium/Hard/Failed grading. Stage 7.
8. Get up to 20 Lichess puzzles related to the themes found in a report. Stage 8.
9. Do Lichess daily and mixed puzzles inside the app with local history. Stage 9.
10. Manage and delete reports, games, and analysis versions safely, and back up, restore, and compact the database. Stages 10 and 11.

What it does not do, by explicit earlier decision: chess.com games, opening training, an opening explorer, a themed practice library, spaced repetition, a "mastered" state, or custom positions.

## 4. Wishes versus the plan

| Wish | Covered by the plan? | Notes |
|---|---|---|
| W1 Analyze my games | Yes, but Lichess games only | Chess.com is out of scope in `01-chess-tweek.md`, yet the rating goal is on chess.com. This is the biggest mismatch. |
| W2 My mistakes as puzzles, repeated | Partly | Stage 7 makes the puzzles, but only inside a 10-game report, and there is no repetition schedule: once solved, nothing brings it back. |
| W3 Themed practice library | No | Stage 9 only offers Lichess Daily and Mixed. |
| W4 Duolingo-style patterns | No | See section 6. |
| W5 Mastered / focus on weak ones | No | The plan grades each attempt, but never tracks mastery over time or chooses what to show next. |
| W6 Opening trainer with objectives | No | Out of the plan entirely. |
| W7 Most common moves | No | Out of the plan entirely. |
| W8 Lichess connection | Yes | Done. |

Conclusion: about half of the remaining plan (stages 5, 6, 8, 10, 11) builds infrastructure for multi-game reports and data management, while most of what the owner actually wants to practice (W3 to W7) is not in the plan at all.

## 5. What existing tools already do

### Sites the owner shared

| Tool | Free? | Open source? | What it offers for these wishes |
|---|---|---|---|
| **En Croissant** (encroissant.org) | Yes | Yes, GPL-3.0, Rust/Tauri + React/TypeScript, about 2k GitHub stars, v0.16.1 released 10 Nov 2026, runs on macOS | Imports games from Lichess **and chess.com**; Stockfish game reports with phases; opening **repertoire builder with spaced-repetition training** (W6, partly W5); puzzles from the Lichess puzzle database with a **themes filter** (W3, W4); a practice mode; local game databases with position search (useful for W7); browser extension sends finished games to the app. |
| **Chessify** (chessify.me) | Mostly paid | No | Cloud engines (very strong Stockfish/Leela on servers), online game database, tablebases. Its strength is engine power, which a 1300 player does not need; a local Stockfish is far more than enough. |
| **ChessMonitor** (chessmonitor.com) | Profiles free, Studio paid | No | Stats and profiles across chess.com and Lichess, an opening explorer with win/draw/loss per move (W7), repertoire files. Mostly a preparation and statistics tool. |
| **ChessBase** (chessbase.com) | Paid (Fritz 21 69.90 EUR, ChessBase for Mac 99.95 EUR/year) | No | Professional databases, opening explorer, repertoire and variation trainer. The industry standard, but paid. |
| **Chessis** (Android) | Free with ads | No | Stockfish reports for games, Lichess game browsing, blunder/mistake detection. No puzzles from your mistakes, no repetition. Similar to what Chess Tweek already has. |
| **DroidFish** (Android, F-Droid) | Yes | Yes, GPL-3.0 | Android analysis board with engines, opening books, tablebases, PGN editing. A board and engine tool, not a trainer. |

### Other free tools found during research

| Tool | What it adds |
|---|---|
| **Lichess Learn and Practice** (lichess.org/practice) | Free lessons: Checkmate Patterns I to IV, Piece Checkmates, Knight and Bishop Mate; Pin, Skewer, Fork, Discovered Attacks, Double Check, Overloaded Pieces, Zwischenzug, X-Ray; Zugzwang, Interference, Greek Gift, Deflection, Attraction, Underpromotion, Desperado, Counter Check, Undermining, Clearance; Key Squares, Opposition; Lucena and Philidor and practical rook endings. |
| **Lichess puzzle themes and dashboard** (lichess.org/training/themes) | Free themed puzzles and a dashboard that shows which themes you are weakest at. |
| **Lichess puzzle database** (database.lichess.org) | About 6.19 million rated puzzles as a CSV file, license **CC0** (free to copy and use without permission). Each puzzle has a rating, themes, and opening tags. Themes include every pattern the owner mentioned: interference, intermezzo, arabianMate, hookMate, anastasiaMate, backRankMate, smotheredMate, deflection, attraction, clearance, xRayAttack, zugzwang, pawnEndgame, rookEndgame, kingsideAttack, exposedKing, and more. |
| **Lichess opening explorer** | Free most-common-move statistics from Masters games or Lichess games filtered by rating band and speed, and a "Player" tab for one person's games (W7). Lichess now requires a login token for API use of the explorer; Chess Tweek already has a Lichess login. |
| **Lichess "Learn from your mistakes"** | On any analyzed Lichess game, replays each of your errors as a quiz. Free, but no repetition and no chess.com games. |
| **Listudy, Chessdriller, Tabia** | Free, open-source opening trainers with spaced repetition (load a repertoire, drill it until it sticks). |
| **Pawn Appetit** | Free GPL-3.0 desktop app similar to En Croissant: chess.com and Lichess import, repertoire spaced repetition, puzzles. Less established. |
| **Chess.com Published-Data API** | Public, read-only, no login: monthly archives of any player's games as PGN. Importing chess.com games into Chess Tweek is a small feature. |

### Duolingo chess

Duolingo has no export or API for its chess lessons, and copying its content would likely break its terms. The same patterns are free elsewhere: the Lichess puzzle database has thousands of puzzles for each of interference, intermezzo, kingside attack, exposed king, and the named rook-and-knight mates. The rook-and-knight mate the owner described is most likely the **hook mate** (rook next to the king, protected by the knight, knight protected by a pawn, king blocked by its own piece) or the **Arabian mate** (rook and knight in the corner, knight protecting the rook). Both are puzzle themes. A small "add my own position" feature would let the owner set up any position seen in Duolingo and drill it.

## 6. Training methods worth knowing (findings)

These are widely used methods for players between 1200 and 2000. They are coaching consensus, not guarantees.

1. **Tactics first, by volume and repetition.** At this level most games are decided by tactics. The **Woodpecker Method** (Smith and Tikkanen) repeats the same set of puzzles in faster and faster cycles until the patterns are automatic. This is exactly what the owner liked about Duolingo's repetition (W4, W5).
2. **Spaced repetition.** Show a puzzle or opening line again just before you would forget it: soon if you failed, much later if you solved it quickly. "Mastered" (W5) is simply an item whose next review is far in the future. Anki uses this; Chessable sells it; Listudy, Chessdriller, and En Croissant use it for openings for free.
3. **Analyze before the engine.** After a game, write down what you were thinking at the critical moments, then check with Stockfish. The engine says what was wrong; your own notes reveal why you missed it (did not see the threat, calculated wrong, time trouble). This makes W1 far more useful.
4. **Your own mistakes are the best puzzles.** They come from your openings and your typical positions, so fixing them pays off directly. Repeat them with spaced repetition (W2).
5. **A thinking routine every move.** Before moving: what does the opponent threaten? Then checks, captures, and threats for both sides. Then a blunder check: is the piece I move, or a piece it was protecting, now loose?
6. **Openings: plans over memorization.** At 1300 to 2000 a short repertoire (London and Caro-Kann are good, stable choices) with the main ideas and pawn structures matters more than deep lines. Learn the first 6 to 10 moves against the most common replies at your rating (W7), then the plan for each structure (W6). Lichess studies, YouTube courses, and free Chessable "short and sweet" courses cover the objectives of both openings.
7. **Endgame basics.** Opposition, key squares, Lucena, Philidor, and basic mates are finite and pay off forever (Lichess Practice covers them).
8. **Play slower games and review every loss.** Rapid 15+10 or 30-minute games give time to use the thinking routine.

## 7. Options

| Option | What happens | Cost |
|---|---|---|
| A. Stop building; use free tools | En Croissant for chess.com/Lichess game reports, openings repertoire with spaced repetition, and themed puzzles; Lichess for Practice, themed puzzles, explorer. | Zero code. Missing: mastery tracking for your own mistakes across games. |
| B. Fork En Croissant | Add the missing features (mistake puzzles with spaced repetition, mastery by theme) to its codebase. | Throws away about 19,000 lines of Chess Tweek code; a large Rust/Tauri codebase to learn; merges with upstream. |
| C. Re-scope Chess Tweek (recommended) | Use En Croissant and Lichess today for openings and explorer. Keep Chess Tweek only for what no free tool combines: chess.com and Lichess games, your own mistakes as drills, a themed pattern library from the CC0 Lichess puzzle database, and one spaced-repetition "mastery" engine across all of them. | Small to medium. Reuses everything built so far. Drops most of stages 5, 6, 8, 10, and 11. |

### Option C in detail (original proposal; section 8 records the owner's changes: openings and the explorer are built in Chess Tweek, not taken from free tools)

Keep (already built or next):

- Lichess login, game catalog, filters, ratings: done.
- Single-game Stockfish analysis: done.
- Game review page (Stage 4 Part 5b): keep, add a short "what was I thinking" note per error (method 3).

Add (new, small):

1. **Chess.com import** through the public API (no login): the rating goal lives there.
2. **My Mistakes deck**: every mistake and blunder from any analyzed game becomes a drill immediately, without the exact-10-game report.
3. **Pattern library**: import a filtered slice of the CC0 Lichess puzzle database (for example, only the themes the owner picks, rated about 1000 to 1800), organized as Checkmates, Fundamental Tactics, Advanced Tactics, Pawn Endgames, Rook Endgames, plus the owner's own collections.
4. **Mastery and spaced repetition**: one scheduler for all drills. Each item is New, Learning, Mastered, or Lapsed. Fast first-try solves push the next review further away; failures bring it back tomorrow. Each theme shows its mastery percentage, so practice targets the weakest themes.
5. **Add my own position** (optional): set up a position (for example from Duolingo) and its solution to drill it.

Use free tools instead of building:

- Opening trainer with spaced repetition: En Croissant repertoire, Listudy, or Chessdriller.
- Most common moves: Lichess opening explorer (or En Croissant's database panel).
- Lichess Practice lessons: use them on Lichess.

Drop or defer from the current plan:

- Stage 5 bulk queue with thermal pausing and low-heat mode (single-game analysis is enough to start; a simple "analyze these N games one after another" can come later).
- Stage 6 exact-10-game reports and Stage 8 related-puzzle mapping (replaced by the theme mastery dashboard).
- Stage 10 dependency-aware deletion and Stage 11 storage cap and compaction (a simple backup is enough for one user).
- Opponent-only analysis has limited training value; keep what exists, build nothing more around it.

## 8. Owner decisions (2026-11-14)

1. **Both sites.** The owner plays on chess.com and Lichess and wants both. Chess.com game import enters the plan.
2. **Openings inside Chess Tweek.** Build what En Croissant does for openings in our own app instead of using En Croissant: an opening explorer, a repertoire builder, and a spaced-repetition repertoire trainer.
3. **Postpone, not delete.** Old stages 5, 6, 8, 9, 10, and 11 move to a "Maybe later" list.
4. **Puzzle level.** Pattern puzzles rated about 1200 to 1700. The owner asked for an explanation of the possible patterns before choosing; see section 9.

Decisions in this section are copied into `01-chess-tweek.md` and `03-delivery-plan.md`.

### How En Croissant does openings (the model we copy, without copying its code)

En Croissant is GPL-3.0 licensed, so we reimplement its ideas in our own stack and never copy its code.

- **Build mode.** Set a start position (for example 1.d4 ... 2.Bf4 for the London). For each position the app lists the most common moves from a reference database with their win rates and whether your repertoire already covers them. Click a move to add it. "Go to next gap" and "Go to biggest gap" jump to positions where an opponent reply has no prepared answer. A line counts as covered once it reaches a position played fewer times than a target you set. Transpositions (same position, different move order) count as covered.
- **Train mode.** The app shows positions from the repertoire and you play your move on the board. You then rate how easily you remembered it (1 Again, 2 Hard, 3 Good, 4 Easy). A spaced-repetition scheduler called FSRS shows difficult moves more often. A session shows moves practiced, correct, incorrect, and accuracy, and files with moves due show a marker.
- **Storage.** A repertoire is a normal PGN file, so annotated lines and comments can be imported and exported.

### What we add on top for "objectives of the opening"

Chess Tweek never calls an AI, so objectives come from text a person wrote:

- A **note** on any position in the repertoire, written by the owner, shown during training (for example "Plan: Ne5, f4, kingside attack").
- **PGN import**: bring in an annotated PGN (for example an exported Lichess study about the London or Caro-Kann) and keep its comments as notes.
- Optional **starter notes** for the main London and Caro-Kann structures, written once into the project as plain text and editable by the owner.

## 9. The patterns, explained (for choosing the first library)

A "theme" is a label Lichess puts on each puzzle saying which idea solves it. Training by theme means solving many puzzles with the same idea until you spot it instantly. These are all the themes that exist, in plain words. Recommended starter themes are marked **(start)**.

### Fundamental tactics (Lichess Practice: Fundamental Tactics)

| Theme | What it means |
|---|---|
| fork **(start)** | One piece attacks two or more pieces at once. |
| pin **(start)** | A piece cannot move without exposing a more valuable piece (or the king) behind it. |
| skewer **(start)** | Attack a valuable piece; when it moves, take the piece behind it. The reverse of a pin. |
| discoveredAttack **(start)** | Move one piece out of the way so the piece behind it attacks. |
| discoveredCheck | A discovered attack where the uncovered piece gives check. |
| doubleCheck **(start)** | Two pieces give check at once; only a king move helps. |
| hangingPiece **(start)** | Win a piece that is undefended. The most common error at 1300. |
| trappedPiece **(start)** | A piece has no safe square and can be won. |
| capturingDefender **(start)** | Take the piece that defends something, then win what it defended. |
| xRayAttack | A piece attacks or defends through another piece. |
| intermezzo **(start)** | Instead of the expected recapture, first play a stronger in-between move (also called zwischenzug). Duolingo unit 23. |

### Advanced tactics (Lichess Practice: Advanced Tactics)

| Theme | What it means |
|---|---|
| deflection **(start)** | Force a defender away from the square or piece it guards. |
| attraction **(start)** | Force an enemy piece (often the king) onto a bad square, usually with a sacrifice. |
| interference **(start)** | Put a piece between an enemy piece and the square it defends, cutting the line. Duolingo unit 23. |
| clearance | Move your own piece away to open a line or square for another piece. |
| sacrifice | Give up material for a bigger gain. |
| quietMove | A strong move that is not a check or capture, which is easy to overlook. |
| defensiveMove | The only move that avoids losing. Trains defense. |
| zugzwang | Every move the opponent has makes their position worse. |
| exposedKing **(start)** | Attack a king with few defenders. |
| kingsideAttack **(start)** | Attack the castled king on the kingside. Duolingo "attack on a castled king". |
| queensideAttack | Attack a king castled on the queenside. |
| attackingF2F7 | Attack the weak f2 or f7 pawn next to the uncastled king. |
| advancedPawn, promotion, underPromotion | Pawn races, promoting, and promoting to a knight, rook, or bishop. |

### Checkmate patterns (Lichess Practice: Checkmates)

| Theme | What it means |
|---|---|
| mateIn1, mateIn2 **(start)**, mateIn3 | Find a forced mate in that many moves. |
| backRankMate **(start)** | The king is trapped behind its own pawns and a rook or queen mates on the back rank. |
| smotheredMate **(start)** | A knight mates a king that is surrounded by its own pieces. |
| arabianMate **(start)** | Rook and knight in the corner: the knight protects the rook and covers the escape square. |
| hookMate **(start)** | Rook next to the king, protected by a knight, which is protected by a pawn. Probably the Duolingo rook-and-knight mate. |
| anastasiaMate **(start)** | Knight and rook (or queen) trap the king on the edge of the board. |
| bodenMate | Two bishops on crossing diagonals mate a king blocked by its own pieces. |
| doubleBishopMate | Two bishops on neighboring diagonals mate. |
| dovetailMate | The queen mates next to the king, whose escape squares are blocked by its own pieces. |
| epauletteMate | The king's own pieces on both sides block it while it is checked from the front. |
| operaMate | A rook mates on the back rank protected by a bishop. |
| cornerMate, killBoxMate, triangleMate, vukovicMate, swallowstailMate, balestraMate, blindSwineMate, morphysMate, pillsburysMate | Rarer named mates; worth adding after the start set. |

### Endgames (Lichess Practice: Pawn and Rook Endgames)

| Theme | What it means |
|---|---|
| pawnEndgame **(start)** | Only kings and pawns. Opposition and key squares decide. |
| rookEndgame **(start)** | Rooks and pawns. The most common endgame in real games. |
| bishopEndgame, knightEndgame, queenEndgame, queenRookEndgame | Other endgame types. |

### Your own openings

Every Lichess puzzle that starts before move 20 also carries an opening tag. Puzzles tagged with the Caro-Kann Defense or the London System are tactics that arise in **your own openings**. These are recommended as two extra starter collections.

### Recommended first library

The 22 themes marked **(start)** plus the two opening collections, at puzzle ratings 1200 to 1700. Each theme becomes a deck. The owner can add more themes at any time.

### How "mastered" works (proposal)

- Each puzzle is New, Learning, Mastered, or Lapsed.
- The attempt sets the review rating automatically: wrong move or gave up means Again; solved with a hint means Hard; solved first try means Good; solved first try quickly means Easy.
- The scheduler sets the next review date: Again brings it back today, Easy pushes it weeks away.
- A puzzle is **Mastered** when its next review is at least 21 days away. A failed mastered puzzle becomes **Lapsed** and returns soon.
- Each theme shows its mastery percentage, and "Practice weakest" picks due puzzles from the weakest themes first. Woodpecker-style "repeat this set faster" sessions are also possible.

## 10. Open questions (remaining)

1. Which first library: the recommended start set from section 9, or a different list?
2. **Getting the Lichess puzzles.** Recommended: download the free Lichess puzzle file once and keep only the chosen themes and ratings (a few tens of thousands of puzzles). The file is compressed with zstd, so this needs either the Homebrew `zstd` tool installed like Stockfish, or a Java decompression dependency. The alternative is fetching puzzles one at a time from the Lichess API by theme, which cannot filter by rating and is slow.
3. Do you want starter notes for London and Caro-Kann written into the project, or only your own notes and imported PGNs?
4. Order of the next stages: puzzles first (as proposed in `03-delivery-plan.md`) or openings first?

## Sources

- En Croissant: https://encroissant.org/ and https://github.com/franciscoBSalgueiro/en-croissant (releases page, 2026-11)
- Chessify: https://chessify.me/
- ChessMonitor: https://www.chessmonitor.com/
- ChessBase: https://en.chessbase.com/
- Chessis: https://play.google.com/store/apps/details?id=com.chessimprovement.chessis
- DroidFish: https://f-droid.org/packages/org.petero.droidfish/
- Lichess Practice: https://lichess.org/practice
- Lichess puzzle database and license: https://database.lichess.org/
- Lichess puzzle themes: https://github.com/lichess-org/lila/blob/master/translation/source/puzzleTheme.xml
- Lichess explorer authentication: https://lichess.org/@/thibault/blog/the-opening-explorer-now-requires-authentication/FSWh9Zg3
- Listudy: https://listudy.org/ ; Chessdriller: https://github.com/gtim/chessdriller
- Pawn Appetit: https://www.linuxlinks.com/pawn-appetit-modern-chess-analysis-training-application/
- Chess.com Published-Data API: https://www.chess.com/news/view/published-data-api
- Duolingo chess review: https://davidwilliamrosales.com/2025/08/18/duolingo-chess-review/
