# Lichess Puzzle dataset sizing

Investigated on 2026-11-19 against the official Lichess open database and the implemented Chess Tweek Puzzle Library schema.

## Official dataset facts

The official [Lichess open puzzle database](https://database.lichess.org/#puzzles) reports **6,193,530 rated and tagged puzzles** and says the current export was updated on **2026-11-11**. The export is CC0 and contains Puzzle ID, FEN, complete UCI line, rating, deviation, popularity, play count, themes, source game URL, opening tags, and daily date.

The official [`lichess_db_puzzle.csv.zst`](https://database.lichess.org/lichess_db_puzzle.csv.zst) snapshot measured for this investigation is:

| Artifact | Exact bytes | Binary size |
|---|---:|---:|
| Compressed `.csv.zst` download | 309,751,173 | 295.40 MiB |
| Decompressed CSV | 1,177,486,537 | 1.097 GiB |
| CSV records | 6,193,531 lines | 6,193,530 puzzles plus header |

The compressed and decompressed sizes were measured directly from the official download. The line count agrees exactly with the official published puzzle count.

## Estimated Chess Tweek storage

The current measured schema model in [`06-puzzle-library-taxonomy-and-sizing.md`](06-puzzle-library-taxonomy-and-sizing.md) uses approximately **425.66 bytes per stored Puzzle**, including normalized metadata, representative classification links, collection membership, and indexes. An attempt adds approximately **92.03 bytes** including its index.

Applying those measured rates to all 6,193,530 puzzles gives:

| Stored scope | Estimated SQLite size |
|---|---:|
| All puzzles, no attempts | 2.46 GiB |
| All puzzles, one attempt each | 2.99 GiB |
| All puzzles, ten attempts each | 7.76 GiB |
| All puzzles, twenty attempts each | 13.07 GiB |

These are schema-based estimates, not an exact full-import measurement. Actual tag counts, indexes, SQLite page utilization, and the repetition schema can move the result. A full import should reserve at least **5–6 GiB of working headroom** for the compressed source, optional expanded input, SQLite WAL/journal growth, and index construction before attempt history grows.

The existing curated plan is much smaller:

| Curated scope | Library only | With twenty attempts per Puzzle |
|---|---:|---:|
| 74 themes × up to 100 | 3.14 MiB | 16.13 MiB |
| 74 themes plus up to 1,000 opening keys × up to 100 | 43.73 MiB | 232.25 MiB |

Those curated figures are deliberately conservative because they assume no Puzzle overlap between categories; real multi-tag Puzzles reduce unique rows.

## Consequence for the product decision

Importing every Lichess puzzle is technically feasible on the target Mac, but it changes Chess Tweek from a small curated local library into a multi-gigabyte dataset product. Random access to every puzzle also requires importing/indexing the full corpus or maintaining another random-access index; a compressed CSV alone cannot efficiently serve the requested Random and By Category flows.

Keeping the 100-per-category plan makes the application hundreds of times smaller and easier to curate, back up, migrate, and validate. Storage does not make a full import impossible, but full-corpus import should be an explicit owner choice with a disk-space check, progress reporting, cancellation, and measured import performance rather than an invisible default.

## Owner decision

The owner chose the full official Puzzle corpus on 2026-11-19. Chess Tweek will store each Lichess Puzzle once in the central Puzzle Library and give Random, By Category, methods, and repetition controlled access through Puzzle references and purpose-specific queries. No feature may create a private copy of a Lichess Puzzle.

The full import therefore requires a preflight disk-space check, visible progress, cancellation, resumability, checksum/snapshot verification, indexed full-corpus queries, and a measured import benchmark before it becomes the default path. The earlier 20–100 counts may still describe curated named collections, but they no longer cap the underlying library or the owner's Random and By Category access.

After the imported snapshot, checksum, row count, and database transaction are verified, Chess Tweek deletes the 295 MiB compressed source file. It retains the source URL, snapshot date, checksum, importer version, imported row count, and import time so the library remains auditable without keeping a duplicate archive. A failed or interrupted import keeps the source file for safe resume or retry.

Dataset update checks are manual-only Admin operations. Chess Tweek makes no startup, scheduled, or background request to Lichess for Puzzle updates. Admin shows the stored snapshot metadata and a **Check for update** action; only that action sends the lightweight metadata request. A separate explicit owner action starts the download and import when an update is available. Chess Tweek never downloads a Puzzle snapshot automatically.

During a verified refresh, a Lichess Puzzle missing from the new snapshot is retained when it has any account attempt, Training List state, Mastered result, or course/method reference. A missing Puzzle with no progress and no authored reference is removed. This preserves user work and authored content without accumulating unused source records forever.

An existing Lichess Puzzle ID may receive new rating, popularity, play-count, Theme, Opening Tag, or daily-date observations in a later snapshot. Its position and solution are immutable. If a refresh supplies a different FEN or solution line for an existing ID, Chess Tweek preserves the stored Puzzle, rejects that conflicting source record, and reports the conflict in Admin; it never silently replaces training evidence or authored references.

## Sources and calculations

- [Official Lichess open puzzle database](https://database.lichess.org/#puzzles): current count, update date, fields, setup-move rule, generation notes, and CC0 availability.
- [Official current puzzle export](https://database.lichess.org/lichess_db_puzzle.csv.zst): exact compressed file and decompressed record measurements.
- [`06-puzzle-library-taxonomy-and-sizing.md`](06-puzzle-library-taxonomy-and-sizing.md): measured SQLite page counts and per-Puzzle/per-attempt rates for the Chess Tweek schema design.

Calculation:

```text
library_bytes = 143,360 + puzzle_count × 425.66
attempt_bytes = puzzle_count × attempts_per_puzzle × 92.03
```
