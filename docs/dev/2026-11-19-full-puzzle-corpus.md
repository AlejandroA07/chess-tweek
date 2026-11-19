# Full Puzzle corpus and controlled access

Stage 7 Part 2 stores the complete official Lichess Puzzle snapshot once in the `training` Puzzle Library, behind a manual Admin lifecycle, and gives Random, By Category, and exact references indexed access. The storage and sampling decision is ADR-010.

## Delivered boundary

- Flyway migration V10 adds the auditable import record (`training_puzzle_corpus_import`), bounded issue samples, compact integer categories, one current observation per Puzzle, and Puzzle-ID-ordered category membership. Existing built-in and bounded-import Puzzles are backfilled from their latest pinned observation.
- `PuzzleCorpus` is the public Admin lifecycle: status, Check for update, start or resume, and cancel. `PuzzleLibrary` gains exact lookup by provider and external ID, Random, and Random within a Theme, opening family, or opening variation.
- `GET /api/admin/puzzle-corpus`, `POST .../update-checks`, `POST .../imports`, and `POST .../imports/cancel` require the connected owner session; every `POST` also requires a same-origin loopback request. Errors are fixed codes.
- Admin shows the stored snapshot, Puzzle count, provenance and SHA-256, database size, free disk, the zstd prerequisite, the last check, live progress with Cancel, Resume after a pause, the reason for a failed snapshot, and up to 20 rejected or conflicting records.

## Lifecycle rules

- Startup never contacts Lichess. It only pauses an import that was active when the application stopped and deletes the source of a finished import.
- Check for update sends one `HEAD` request to the single configured file URL. Redirects are never followed. ETag, `Last-Modified`, and `Content-Length` are validated. The snapshot date is the UTC date of `Last-Modified`. A check never downloads.
- Download and import is a separate action and requires an available update and zstd, plus free space for the remaining download and 6 GiB of working headroom. The file is downloaded into the owner-only `var/puzzle-corpus` folder. An interrupted download resumes with `Range` and `If-Range`. A changed ETag fails the import as `source_changed` and deletes the partial file. A connection that delivers no bytes for 60 seconds is closed and the import pauses.
- Lichess publishes no checksum for this file. Verification is the exact byte count, `zstd -t` (which checks every frame's content checksum), and a SHA-256 recorded after download and re-checked before records are applied.
- Records are applied in chunks of 5,000. Each chunk commits together with its resume cursor, so a cancellation, crash, or shutdown keeps either the whole chunk or none of it. Records are idempotent by provider and external ID. A duplicate ID in one snapshot is rejected. A different position or solution for a stored ID is a conflict: the stored Puzzle is kept and the record is reported. More than 1,000 rejected records fail the snapshot.
- Finalization removes Puzzles that the new snapshot no longer contains, unless a method exercise, a collection, or a pinned observation references them. Stage 7 Part 3 must add account attempts and list state to `JdbcPuzzleCorpusStore.RETAINED`. It then verifies that every accepted record is stored exactly once, records the snapshot metadata, and only then deletes the compressed source.

## Record rules corrected by the real snapshot

The first full run rejected 3.6 percent of the official records. Four Part 1 rules were wrong for the real export and are fixed in the shared `LichessPuzzleRecords` parser, with regression tests built from the real records:

| Observed in the export | Count | Correction |
|---|---:|---|
| Hyphenated opening tags such as `Caro-Kann_Defense` | about 220,000 tags | Opening tags allow `-`. |
| A move line that repeats a move, such as record `006E1` | 25,818 records | Only tags must be distinct; moves are a sequence. |
| Ratings of 399, the rating floor | 5,562 records | Ratings are accepted from 100 to 4000. |
| `DailyDate` written as epoch milliseconds | 1,812 records | Epoch milliseconds or an ISO date, from 2015 to tomorrow. |

After the corrections, the complete snapshot imports with zero rejected records.

## Measured on the target Mac (2026-11-19)

Official snapshot of 2026-11-01: 309,751,173 compressed bytes, SHA-256 `7b0d235fa40cfd7fdb7df1fec56a96b3b6ee1496b32ed2eb05be90d0ebf94253`. Most runs served the official file over loopback into a separate scratch database; the last row is the real network run. The owner's `var/chess-tweek.db` was not touched.

| Measurement | Result |
|---|---:|
| Download, `zstd -t`, and SHA-256 (loopback) | 1.3 s |
| Apply 6,193,530 records, including a cancel and resume | 69 s |
| Finalize and verify | 2.1 s |
| Whole import, from start to verified | 134 s |
| Stored Puzzles / category memberships / opening categories | 6,193,530 / 30,498,144 / 1,593 |
| Rejected / conflicting records | 0 / 0 |
| Database file after import | 2,758,156,288 bytes (2.57 GiB) |
| Peak process memory | about 500 MB |
| Cancel at 3,000,000 records until paused | 25 ms |
| Resume until the next chunk committed (re-reads 3,000,000 records) | 5.3 s |
| Random / Theme `fork` / rare Theme / opening family / exact ID | 0.27 / 0.22 / 0.11 / 0.21 / 0.02 ms per Puzzle |
| Admin status | 0.26 ms |
| Refresh with a new ETag over the stored corpus (6,193,530 unchanged) | 60 s; database grew to 2,823,151,616 bytes |
| End-to-end against `database.lichess.org` into a fresh database: download and verify / apply / total | 17.5 s / 116 s / 134 s |

Storage by object: `training_puzzle` 1.49 GiB, its provider and external-ID index 258 MiB, `training_puzzle_current` 425 MiB, category membership 330 MiB, and the import index 88 MiB. The real total is close to the 2.46 GiB estimate in `08-lichess-puzzle-dataset-sizing.md`.

The first design gave every Puzzle a random sampling key. Inserts then landed at random positions in large indexes, and throughput fell from about 20,000 to about 5,000 records per second by two million rows. Sampling a uniform point in the Puzzle ID range turns those inserts into appends and keeps the import near 90,000 records per second.

## Verification contract

- `PuzzleCorpusLifecycleTest` runs against a loopback stand-in for the official file. It covers no requests at startup, a check without a download, the full import with an immutable conflict, an illegal line, and a duplicate ID, and a refresh with an update and a category change. It also covers removal of unreferenced Puzzles, retention of a method-referenced Puzzle, Range resume after a stalled connection, resume after shutdown without duplicates, a changed source, insufficient disk, missing zstd, and index-only query plans.
- `PuzzleCorpusControllerTest` covers authorization, same-origin refusal before any action, every refused-start code, and that a check never starts an import.
- `LichessPuzzleRecordsTest` pins the corrected record rules to real records.
- `AdminPuzzleCorpus.test.tsx` covers the manual flow, progress and cancel, the zstd prerequisite, provenance and issues, refused actions, failed snapshots, session failure, and strict response parsing.

`node scripts/verify.mjs` remains the only complete definition of done.
