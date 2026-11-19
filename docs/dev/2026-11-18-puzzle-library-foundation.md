# Puzzle Library foundation

Stage 7 Part 1 creates one `training`-owned Puzzle Library for guided methods and future named practice. It deliberately does not add the catalog interface or spaced-repetition state.

## Delivered boundary

- Flyway migration V9 stores immutable provider/external-ID puzzle material, snapshot observations, the 74 current user-visible Lichess theme names, opening family/variation classifications, bounded collections, and method exercise references.
- The 12 Grandmaster Thinking Method exercises are installed as CC0 library records. The course JSON now retains only authored teaching content and a provider/external-ID/snapshot reference; it no longer duplicates FENs, solutions, ratings, themes, or source URLs.
- `PuzzleLibrary` is the public training interface for stable-ID lookup, exact method-exercise lookup, catalog/storage totals, and an explicit snapshot import.
- The local Lichess CSV/zstd importer validates the approved root and basename, rejects symbolic links, verifies SHA-256 when supplied, bounds compressed size, expanded size, record length, tag cardinality, opening cardinality, rating range, and collection size, and validates selected move lines with `chessgame` before one database transaction begins.
- Selection is deterministic from popularity, play count, rating deviation, and external ID. A collection is available at 20 members; an import can target 20 through 100. Provider/external ID remains stable between snapshots, while rating and classification observations retain their snapshot.
- Repeating the same provider/date/checksum import is safe and reports `ALREADY_IMPORTED`. A conflicting checksum for the same snapshot date is rejected.

## Explicit local import

The operation is absent from normal startup. After producing the verified application jar, run it as a non-web process with an approved import directory and a file basename:

```sh
/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin/java \
  -jar target/chess-tweek-0.1.0-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --chess-tweek.puzzle-import.root=/absolute/path/to/imports \
  --chess-tweek.puzzle-import.file=lichess_db_puzzle.csv.zst \
  --chess-tweek.puzzle-import.snapshot-date=2026-11-01 \
  --chess-tweek.puzzle-import.minimum-rating=1200 \
  --chess-tweek.puzzle-import.maximum-rating=1700 \
  --chess-tweek.puzzle-import.target-per-collection=50
```

An optional lowercase SHA-256 is accepted as `--chess-tweek.puzzle-import.checksum=...` and must match the selected file. The completion log reports imported puzzles, collection counts, available collections, and SQLite bytes. The source download remains outside the repository and database.

## Verification contract

Integration coverage proves built-in installation, 74 theme names, exact course resolution, stable IDs across restart, 20-member availability, deterministic/idempotent re-import, atomic rejection of illegal move material, and rejection of traversal and symbolic-link inputs. `node scripts/verify.mjs` remains the only complete definition of done.

## Deferred

- The Stage 9 catalog and practice interface will browse these classifications and collections.
- Stage 7 Part 3 will add account-scoped attempts, mastery, and scheduling.
- Stage 7 Part 2 (`2026-11-19-full-puzzle-corpus.md`) added the manual full-corpus lifecycle and corrected four record rules this bounded importer shares with it: hyphenated opening tags, repeated moves in a line, the 399 rating floor, and epoch-millisecond daily dates.
