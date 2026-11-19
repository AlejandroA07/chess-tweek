# Puzzle library taxonomy, opening scope, and storage sizing

> **Owner update, 2026-11-19:** the complete official Lichess Puzzle snapshot will be imported into the central library. The 20–100 targets below remain useful for curated named collections, but they no longer cap the underlying library or Random and By Category access. See [`08-lichess-puzzle-dataset-sizing.md`](08-lichess-puzzle-dataset-sizing.md).

**Decision date:** 2026-11-18
**Primary data snapshot checked:** Lichess puzzle export updated 2026-11-11

## Recommendation

Build one user-visible puzzle library inside the `training` module. Store every puzzle once, attach any number of theme and opening tags, and let courses and future thinking methods reference the library puzzle ID. The Grandmaster Thinking Method should therefore own its instruction, exercise order, and completion rules, but not a private copy of the puzzle position.

Import the complete Lichess theme catalog as metadata, but do not confuse that small, stable catalog with openings. The current Lichess source defines **75 visible theme placements representing 74 unique keys** (`mate` appears in two groups). Opening practice is a separate, much larger, snapshot-dependent hierarchy. Lichess builds that hierarchy from the most common opening tag keys in its current puzzle corpus and caps the aggregation at **1,000 keys**. Chess Tweek should follow the same distinction:

- make all 74 current visible theme names discoverable;
- import the opening family/variation keys that actually occur in the chosen puzzle snapshot;
- initially expose only opening buckets with at least 20 qualifying puzzles, ordered by frequency;
- retain the full tag catalog so more buckets can be enabled without changing the schema;
- use 50 puzzles as the normal deck size, 20 for scarce or narrow patterns, and 100 for high-value, high-volume patterns.

This design satisfies the condition for reopening [ADR-006](02-architecture.md#adr-006--report-packages-share-ownership-normal-puzzles-do-not): a global library now has explicit ownership, provenance, reference, and retention rules.

## What “all Lichess names” means

There are two different name systems.

### 1. Puzzle themes: a finite catalog

Lichess's current [`PuzzleTheme.scala`](https://github.com/lichess-org/lila/blob/master/modules/puzzle/src/main/PuzzleTheme.scala) is the authoritative user-visible grouping. It flattens the categories into `visible`; `checkFirst` is explicitly hidden. The English display names and descriptions come from the official [`puzzleTheme.xml`](https://github.com/lichess-org/lila/blob/master/translation/source/puzzleTheme.xml). There are 75 category placements but 74 unique keys because `mate` appears under both **Mates** and **Goals**.

The current complete visible catalog is:

| Lichess group | User-facing name (`key`) |
|---|---|
| Recommended | Healthy mix (`mix`) |
| Phases | Opening (`opening`); Middlegame (`middlegame`); Endgame (`endgame`); Rook endgame (`rookEndgame`); Bishop endgame (`bishopEndgame`); Pawn endgame (`pawnEndgame`); Knight endgame (`knightEndgame`); Queen endgame (`queenEndgame`); Queen and rook endgame (`queenRookEndgame`) |
| Motifs | Advanced pawn (`advancedPawn`); Attacking f2 or f7 (`attackingF2F7`); Capture the defender (`capturingDefender`); Discovered attack (`discoveredAttack`); Double check (`doubleCheck`); Exposed king (`exposedKing`); Fork (`fork`); Hanging piece (`hangingPiece`); Kingside attack (`kingsideAttack`); Pin (`pin`); Queenside attack (`queensideAttack`); Sacrifice (`sacrifice`); Skewer (`skewer`); Trapped piece (`trappedPiece`) |
| Advanced | Attraction (`attraction`); Clearance (`clearance`); Collinear move (`collinearMove`); Discovered check (`discoveredCheck`); Defensive move (`defensiveMove`); Deflection (`deflection`); Interference (`interference`); Intermezzo (`intermezzo`); Quiet move (`quietMove`); X-Ray attack (`xRayAttack`); Zugzwang (`zugzwang`) |
| Mates | Checkmate (`mate`); Mate in 1 (`mateIn1`); Mate in 2 (`mateIn2`); Mate in 3 (`mateIn3`); Mate in 4 (`mateIn4`); Mate in 5 (`mateIn5`) |
| Mate themes | Anastasia's mate (`anastasiaMate`); Arabian mate (`arabianMate`); Back rank mate (`backRankMate`); Balestra mate (`balestraMate`); Blind Swine mate (`blindSwineMate`); Boden's mate (`bodenMate`); Corner mate (`cornerMate`); Double bishop mate (`doubleBishopMate`); Dovetail mate (`dovetailMate`); Epaulette mate (`epauletteMate`); Hook mate (`hookMate`); Kill box mate (`killBoxMate`); Pillsbury's mate (`pillsburysMate`); Morphy's mate (`morphysMate`); Opera mate (`operaMate`); Swallow tail mate (`swallowstailMate`); Triangle mate (`triangleMate`); Vukovic mate (`vukovicMate`); Smothered mate (`smotheredMate`) |
| Special moves | Castling (`castling`); En passant (`enPassant`); Promotion (`promotion`); Underpromotion (`underPromotion`) |
| Goals | Equality (`equality`); Advantage (`advantage`); Crushing (`crushing`); Checkmate (`mate`, already counted) |
| Lengths | One-move puzzle (`oneMove`); Short puzzle (`short`); Long puzzle (`long`); Very long puzzle (`veryLong`) |
| Origin | Master games (`master`); Master vs Master games (`masterVsMaster`); Super GM games (`superGM`) |

Not every visible key should become an independently curated deck. `mix` is a selector, while phases, goals, lengths, and origins are useful facets. They should still appear as filters and may generate a practice queue, but making separate copies of puzzles for them would create artificial duplication. The drill-oriented motifs, advanced patterns, named mates, special moves, and endgames are the primary decks.

This list updates and supersedes the informal list in [the training goals investigation](04-training-goals.md#9-which-patterns-should-the-library-contain). That document remains directionally correct, but the Lichess source now includes additional named mate and endgame categories and is the source of truth for exact keys.

### 2. Openings: a large, changing hierarchy

The puzzle export's `OpeningTags` column is not another finite theme enum. The official [puzzle database documentation](https://database.lichess.org/#puzzles) says that it is populated only for puzzles beginning before move 20. Values are space-separated underscore keys. Official sample rows demonstrate the two-level form:

```text
Italian_Game Italian_Game_Classical_Variation
French_Defense French_Defense_Exchange_Variation
Sicilian_Defense Sicilian_Defense_Dragon_Variation
```

Lichess's current [`PuzzleOpening.scala`](https://github.com/lichess-org/lila/blob/master/modules/puzzle/src/main/PuzzleOpening.scala) confirms the semantics: it stores the opening family key and the matched opening/variation key, groups variations under families, orders them by popularity or alphabetically, and limits its corpus aggregation to the 1,000 most common tag keys. The `/training/openings` route is separate from `/training/themes`, confirming that openings are a separate browsing dimension rather than part of the 74-theme taxonomy.

The names originate in the official [`lichess-org/chess-openings`](https://github.com/lichess-org/chess-openings) dataset. Its convention is `Opening family: Variation, Subvariation, ...`; multiple rows may reach the same named opening through transpositions, while each name has one shortest line. As of this investigation, its five source TSVs contain **3,865 position rows** (823 + 781 + 1,250 + 644 + 367 data rows in [`a.tsv`](https://github.com/lichess-org/chess-openings/blob/master/a.tsv), [`b.tsv`](https://github.com/lichess-org/chess-openings/blob/master/b.tsv), [`c.tsv`](https://github.com/lichess-org/chess-openings/blob/master/c.tsv), [`d.tsv`](https://github.com/lichess-org/chess-openings/blob/master/d.tsv), and [`e.tsv`](https://github.com/lichess-org/chess-openings/blob/master/e.tsv)). Position rows, unique names, exported puzzle tags, and the popularity-limited Lichess puzzle-opening UI are therefore not the same count.

Consequently, “all names” should mean:

1. all 74 visible theme keys from a pinned Lichess source revision;
2. every opening family/variation key found in the selected puzzle snapshot, retained as catalog metadata;
3. a Lichess-like initial opening browser capped at the most common 1,000 valid keys;
4. a practice deck only when at least 20 puzzles pass Chess Tweek's rating and quality gates.

It should **not** mean promising 20–100 puzzles for every row in the opening-name repository. Some named positions never occur as a puzzle tag, and some opening variations will not have 20 strong puzzles near the target rating.

## Curated count policy

The count is a quality decision, not a storage limit.

| Target | Use | Selection rule |
|---:|---|---|
| 20 | Rare named mates, special moves, narrow opening variations | Minimum for publishing a named deck. If fewer than 20 pass the gates, keep the name searchable but label it unavailable rather than weakening quality. |
| 50 | Default for a normal motif, endgame, opening family, or sufficiently common variation | Enough repetition to learn the pattern without making the first release expensive to curate. This is the recommended starting target. |
| 100 | Fork, pin, discovered attack, deflection, interference, back-rank mate, other high-value/high-volume patterns, and the owner's core opening families | Expand only after solve data shows that the extra breadth is useful. |

For the 2000-strength goal, use the same source bands already accepted for the guided course where appropriate: Foundation 1500–1700, Development 1750–1950, and Stretch 2000–2200. A general pattern deck can interleave those tiers rather than silently reverting to the older 1200–1700 proposal. Retain the quality gates from [the guided-course sourcing plan](05-guided-course-exercise-sourcing.md): prefer rating deviation at most 90, popularity at least 80, and at least 200 plays, then manually review authored-course picks.

The current [`scripts/select-course-puzzles.mjs`](../../scripts/select-course-puzzles.mjs) already does the difficult safe-input work: it streams `.csv` or `.csv.zst`, validates the official columns and URLs, bounds decompression and record size, applies the opponent's setup move, and keeps deterministic candidates. A later implementation should extract its parser/normalizer into a shared importer and add catalog/deck selection modes. The current lesson-specific selection rules and prose should not become the global library schema.

## Proposed ownership and references

Keep these concepts inside `training`:

- `puzzle`: one normalized, immutable-at-snapshot source puzzle, unique on `(provider, external_id)`;
- `puzzle_tag`: many-to-many source themes and opening keys;
- `puzzle_collection`: a user-visible theme/opening/custom deck;
- `puzzle_collection_member`: ordered or ranked references from a deck to a puzzle;
- `method_exercise`: method/lesson teaching text and a reference to `puzzle.id`;
- `puzzle_attempt`: the account's result, time, hints, wrong moves, and review scheduling state for the puzzle.

The same puzzle can belong to several decks and several methods without duplicating its FEN or solution. Deleting a method removes its references and authored teaching text, not the library puzzle or its attempt history. Imported library puzzles remain until the user explicitly replaces/removes a source snapshot; do not cascade them with a course. Deduplicate across snapshot refreshes by provider and external puzzle ID, and retain the snapshot metadata used for each selection.

ADR-006 still applies to report-owned Mistake/Blunder and Related Lichess Puzzle packages. Those remain under `game-review` and must not gain a cross-module database foreign key to `training`. The central reference design is specifically for global training collections and methods such as the Grandmaster Thinking Method.

## Storage sizing

### Assumptions

The estimate uses SQLite 3.51.0 with 4 KiB pages and a normalized schema representative of the design above:

- one puzzle row containing provider, external ID, post-setup FEN, UCI solution, rating, deviation, popularity, play count, game URL, snapshot date, and license;
- a unique provider/external-ID index and a rating/popularity index;
- five tag links per puzzle as a conservative average, with indexes in both lookup directions;
- one curated collection membership per selected puzzle, with collection/ordinal and reverse indexes;
- 1,074 catalog rows for the conservative full scenario: 74 unique themes plus Lichess's maximum 1,000 opening keys;
- no deduplication between categories, even though real multi-tag puzzles will reduce the number of unique puzzle rows;
- an attempt row with puzzle ID, timestamp, outcome, elapsed time, hint count, and wrong-move count, plus a puzzle/time index;
- no board images or piece assets, because positions are FEN and moves are text.

This deliberately overestimates library rows by assuming that every category needs a distinct set.

### Measured SQLite result

The synthetic rows used a 68-character FEN, a three-ply UCI solution, a Lichess game URL, and the fields above. `page_count * page_size` produced:

| State | SQLite pages | Bytes | MiB |
|---|---:|---:|---:|
| Schema + 1,074 category rows | 35 | 143,360 | 0.14 |
| 21,480 puzzles (20 × 1,074), five tags and one membership each | 2,186 | 8,953,856 | 8.54 |
| 53,700 puzzles (50 × 1,074), five tags and one membership each | 5,520 | 22,609,920 | 21.56 |
| 107,400 puzzles (100 × 1,074), five tags and one membership each | 11,196 | 45,858,816 | 43.73 |
| Previous row plus one attempt for every puzzle | 13,609 | 55,742,464 | 53.16 |

At the largest run this is approximately **425.66 bytes per stored puzzle including indexes and links**, plus **92.03 bytes per attempt including its index**.

The reproducible estimate is:

```text
categories = 74 + opening_key_count       # opening_key_count <= 1000 for Lichess-like scope
N = categories * target_per_category      # conservative: no puzzle overlap
library_bytes ~= 143360 + N * 425.66
attempt_bytes ~= N * attempts_per_puzzle * 92.03
total_bytes ~= library_bytes + attempt_bytes
MiB = total_bytes / 1048576
```

Applying the formula gives:

| Scope | Per category | Conservative puzzle rows | Library only | With 10 attempts/puzzle | With 20 attempts/puzzle |
|---|---:|---:|---:|---:|---:|
| 74 themes | 20 | 1,480 | 0.74 MiB | 2.04 MiB | 3.34 MiB |
| 74 themes | 50 | 3,700 | 1.64 MiB | 4.89 MiB | 8.13 MiB |
| 74 themes | 100 | 7,400 | 3.14 MiB | 9.64 MiB | 16.13 MiB |
| 74 themes + up to 1,000 opening keys | 20 | 21,480 | 8.54 MiB measured | 27.39 MiB | 46.24 MiB |
| 74 themes + up to 1,000 opening keys | 50 | 53,700 | 21.56 MiB measured | 68.69 MiB | 115.82 MiB |
| 74 themes + up to 1,000 opening keys | 100 | 107,400 | 43.73 MiB measured | 137.99 MiB | 232.25 MiB |

Allow roughly twice the final database size during bulk import for WAL/journal, temporary indexes, and a safety margin. Even the deliberately conservative 1,074-category, 100-puzzle, 20-attempt scenario remains below 0.5 GiB with that operational headroom. Storage is not the reason to limit the first release; curation quality and navigation are.

The estimate excludes authored lesson prose, backups, SQLite WAL files left open between checkpoints, and the original multi-gigabyte compressed Lichess download. The source `.zst` remains an ignored import input and is not bundled with the app, consistent with the existing sourcing plan.

## Provenance and licensing requirements

The official [Lichess open database](https://database.lichess.org/#puzzles) releases its exports under CC0 and expressly permits research, commercial use, modification, and redistribution without permission. The official opening-name repository also declares the dataset public domain and uses the CC0 Public Domain Dedication. Attribution is therefore not a license condition.

Chess Tweek should still preserve provenance for reproducibility and user trust. For every imported puzzle retain:

- provider (`lichess-puzzle-database`), puzzle ID, and original game URL;
- source snapshot date and, when available, download checksum;
- original source FEN and UCI moves, plus the derived post-setup FEN shown to the player;
- rating, rating deviation, popularity, play count, themes, and opening tags as observed in that snapshot;
- importer/selector version and import time;
- license identifier `CC0-1.0`;
- a courtesy credit such as “Puzzle position adapted from the Lichess open puzzle database (CC0).”

Keep Chess Tweek's original teaching text, hints, labels, and method instructions separate from the CC0 source record. Do not use Lichess branding in a way that suggests endorsement. A future refresh may change ratings, tags, popularity, or opening names, so never overwrite the provenance of a versioned course exercise silently.

## Implementation consequence

Stage 7 Part 1 implements the central library schema and bounded local importer and migrates the 12 guided-course exercises to stable references. The 74 theme names are seeded up front; opening families and variations are derived from the selected snapshot without flattening them into themes. The full taxonomy catalog remains a later interface slice, and Stage 7 Part 2 adds repetition state. This prevents the Grandmaster Thinking Method from becoming the first of several duplicated method-specific puzzle stores and gives future methods a stable way to invoke a precise Puzzle by internal ID.

## Primary sources

- [Lichess open puzzle database: current count, schema, setup-move rule, opening tags, and CC0](https://database.lichess.org/#puzzles)
- [Lichess user-visible puzzle theme categories and hidden-theme declaration](https://github.com/lichess-org/lila/blob/master/modules/puzzle/src/main/PuzzleTheme.scala)
- [Lichess English puzzle theme names and descriptions](https://github.com/lichess-org/lila/blob/master/translation/source/puzzleTheme.xml)
- [Lichess puzzle opening aggregation, family/variation keys, ordering, and 1,000-key cap](https://github.com/lichess-org/lila/blob/master/modules/puzzle/src/main/PuzzleOpening.scala)
- [Lichess training routes separating themes from openings](https://github.com/lichess-org/lila/blob/master/conf/routes)
- [Official Lichess chess-opening names, structure, source files, and CC0 statement](https://github.com/lichess-org/chess-openings)
