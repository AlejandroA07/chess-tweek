# Guided-course exercise sourcing

**Research date:** 2026-11-17

## Decision

Replace the 12 placeholder positions in the first Grandmaster Thinking Method module with a manually curated, versioned slice of the official Lichess puzzle database. Keep the course explanations and coaching prompts original to Chess Tweek, but describe the exercise positions accurately as adapted from the Lichess CC0 puzzle database.

The first module must be a ladder, not a 1300-only ceiling. Each lesson should contain:

1. one **Foundation** exercise selected around Lichess puzzle rating 1500–1700;
2. one **Development** exercise selected around 1750–1950; and
3. one **Stretch** exercise selected around 2000–2200.

These are source-puzzle bands, not promises that a Lichess puzzle rating equals a chess.com rapid rating. The bands provide an initial progression toward 2000-strength calculation habits and should later be tuned from local solve rate, hint use, retries, and time-to-solve.

Do not fetch random exercises at lesson runtime. The authored course must remain deterministic, reviewable, and available offline.

## Why the Lichess database is the right source

The official [Lichess open database](https://database.lichess.org/#puzzles) currently publishes more than 6.19 million rated and tagged puzzles as `lichess_db_puzzle.csv.zst`. Each record includes the puzzle ID, FEN, UCI move line, rating, rating deviation, popularity, play count, themes, source-game URL, opening tags, and optional daily-puzzle date. Lichess explains that the rating is produced from puzzle attempts, while votes refine the tags and popularity.

The database page states that its exports are released under [CC0](https://database.lichess.org/). The [Creative Commons CC0 deed](https://creativecommons.org/publicdomain/zero/1.0/) permits copying, modification, and distribution, including commercial use, without asking permission. There is no attribution or share-alike condition. Chess Tweek should nevertheless credit Lichess, retain the puzzle ID and game URL, and avoid implying that Lichess endorses the course.

The data also fits the existing local-first design:

- the compressed source can stay outside the repository;
- a one-time streaming selection step can emit only the reviewed records;
- the 12 selected records can ship with the application and work offline;
- no runtime network call or new application dependency is required; and
- later pattern-library work can reuse the same database and `zstd` prerequisite already approved in `04-training-goals.md`.

## Why the puzzle API is not the course source

Lichess also provides authenticated puzzle endpoints. The official [`GET /api/puzzle/batch/{angle}` specification](https://github.com/lichess-org/api/blob/master/doc/specs/tags/puzzles/api-puzzle-batch-angle.yaml) supports a theme or opening, a difficulty relative to the user's puzzle rating, and batches of 1–50 puzzles under the `puzzle:read` scope. The official [`GET /api/puzzle/next` specification](https://github.com/lichess-org/api/blob/master/doc/specs/tags/puzzles/api-puzzle-next.yaml) similarly returns an unseen random puzzle for an authenticated user.

Those endpoints are useful for a future live-practice mode, but they are a poor fit for a fixed course because the result is random, relative rather than an exact rating band, account-dependent, and unavailable offline. Both specifications also explicitly say not to enumerate puzzles through the API and direct bulk users to the public database.

## Selection contract for the first 12 replacements

Run a one-time candidate-selection pass over the official compressed CSV, then review the candidates manually. Do not commit the full database or a large intermediate extract.

### Initial objective filters

- Use the three rating bands in the decision above, with one exercise from each band per lesson.
- Prefer `RatingDeviation <= 90`, `Popularity >= 80`, and `NbPlays >= 200` as initial stability and quality gates. These are curation heuristics, not claims made by Lichess; relax them only when a lesson has too few good candidates.
- Exclude the `oneMove` theme. Prefer `short` only for Foundation exercises, `long` for Development, and `veryLong` for Stretch. The official [Lichess theme definitions](https://github.com/lichess-org/lila/blob/master/translation/source/puzzleTheme.xml) define those lengths as two, three, and four-or-more player moves respectively.
- Prefer ordinary middlegame and endgame positions over artificial-looking sparse boards.
- Balance White and Black to move, game phases, and tactical motifs across the module.

### Lesson-specific candidate themes

The official theme list describes `defensiveMove` as a precise sequence needed to avoid losing, `quietMove` as preparation of a hidden threat, and `sacrifice` as giving material temporarily to gain through a forced sequence. These are especially useful because they prevent the course from becoming a collection of obvious checks and captures.

| Lesson | Prefer | Avoid |
|---|---|---|
| Read the last move | `defensiveMove`, `equality`, `intermezzo`; the database's first UCI move provides the opponent's last move | hanging queens, legal-move-only checks, prompts that reveal the answer |
| Generate serious candidates | `quietMove`, `sacrifice`, `interference`, `attraction`, `clearance` | a single unprotected high-value piece with no calculation |
| Test the strongest reply | `long` or `veryLong` combined with `deflection`, `intermezzo`, `sacrifice`, or `xRayAttack` | one-ply wins and lines where the opponent has no meaningful resource |
| Make the final safety check | `defensiveMove`, `equality`, `trappedPiece`, or difficult endgame saves | simply moving an attacked queen or rook to a named square |

Themes narrow the search; they do not replace manual instructional review.

### Manual acceptance checklist

Every selected exercise must pass all of these checks:

- Apply the first UCI move to the database FEN before presenting the position. Lichess documents that the stored FEN is before the opponent's setup move, and that the second UCI move begins the player's solution.
- Replay the entire line through the project's chess rules and reject any malformed or illegal record.
- Require at least two player decisions; Development and Stretch exercises should normally require three and four or more respectively.
- Ensure the line demonstrates the lesson's thinking step, not merely the tagged tactic.
- Write a prompt that asks for a decision without naming the moving piece, origin square, destination square, or motif.
- Write hints in layers: first the thinking process, then the tactical area, and only the final hint may become concrete.
- Write an original explanation that compares the tempting candidate with the strongest reply and the correct line.
- Check that no alternative legal move also satisfies the authored answer. Lichess says solution moves are normally “only moves,” with a documented exception for positions having multiple mates in one; the course should avoid that exception.
- Record the source snapshot date and all source metadata needed to audit or replace the exercise later.

## Required course-data shape

The fixed course should store enough information to replay a line instead of validating only one move:

```json
{
  "difficultyTier": "development",
  "fen": "FEN after the opponent's setup move",
  "acceptedMoves": ["player UCI"],
  "solutionLine": ["player UCI", "opponent UCI", "player UCI"],
  "source": {
    "provider": "lichess",
    "puzzleId": "abc12",
    "gameUrl": "https://lichess.org/...",
    "license": "CC0-1.0",
    "ratingAtSelection": 1840,
    "ratingDeviationAtSelection": 76,
    "popularityAtSelection": 92,
    "playsAtSelection": 1240,
    "themes": ["defensiveMove", "long"],
    "snapshotDate": "2026-11-11"
  }
}
```

The first course version asks the learner to calculate the full line and submit its critical first move. The frontend should move that submitted piece immediately and roll it back if the server rejects the attempt, while the validated full line supports the authored explanation. A correct answer must not draw a solution arrow. Board arrows should be a separate user annotation gesture: right-drag is the pointer shortcut and a labeled annotation mode provides keyboard access. An annotation must not reveal or validate the solution. Interactive forced-reply playback can be added as a later course refinement without changing the sourced line data.

Replacing the exercise set changes authored content and should increment the course content version. The current “all exercises are original” attribution must also change so it distinguishes original Chess Tweek instruction from CC0-sourced positions.

## Acquisition workflow

1. Download `lichess_db_puzzle.csv.zst` from the official database page into an ignored local input directory.
2. Stream it through the installed `zstd` executable; do not decompress the complete file onto the project tree.
3. Apply the objective filters and lesson-theme shortlist in a small repository script using only existing runtimes.
4. Emit a bounded candidate file, for example 20–40 candidates per lesson and tier.
5. Manually inspect the board and full line, then choose the final 12.
6. Copy only the chosen records and authored teaching material into the versioned course resource.
7. Add validation and tests for source metadata, setup-move application, full-line legality, tier coverage, and minimum line length.

The implemented selector is `scripts/select-course-puzzles.mjs`. It reads the official `.csv.zst` as a stream through the system `zstd`, rejects malformed records and non-Lichess source URLs, keeps only a bounded shortlist in memory, and never writes the source data into the repository. Example:

```sh
node scripts/select-course-puzzles.mjs /path/to/lichess_db_puzzle.csv.zst \
  --snapshot-date=2026-11-01 --per-bucket=8 --output=/tmp/course-candidates.json
```

This same workflow can be rerun when a future content version needs stronger exercises. Existing shipped exercises remain reproducible because their metadata and solution lines are stored with the application.

## Alternatives considered

| Source | Decision |
|---|---|
| Lichess puzzle batch/next API | Keep for future personalized live practice. It is random, authenticated, online-only, and explicitly not a bulk-download mechanism. |
| Puzzles generated from the owner's analyzed games | Use later for personalized repetition. They are highly relevant but cannot provide a consistent first-run authored course and need separate quality/rating calibration. |
| Positions selected from master-game exports and re-analysed locally | Reject for this slice. It adds substantial curation and engine work without the Lichess database's puzzle ratings, themes, popularity, and established solution lines. |
| Third-party puzzle sites or paid courses | Reject. They offer no advantage over the official CC0 source and introduce unclear reuse rights, service dependencies, or cost. |

## Primary sources

- [Lichess open puzzle database, format, generation notes, and CC0 statement](https://database.lichess.org/#puzzles)
- [Lichess puzzle theme names and definitions](https://github.com/lichess-org/lila/blob/master/translation/source/puzzleTheme.xml)
- [Lichess batch puzzle API specification](https://github.com/lichess-org/api/blob/master/doc/specs/tags/puzzles/api-puzzle-batch-angle.yaml)
- [Lichess next-puzzle API specification](https://github.com/lichess-org/api/blob/master/doc/specs/tags/puzzles/api-puzzle-next.yaml)
- [Creative Commons CC0 1.0 deed](https://creativecommons.org/publicdomain/zero/1.0/)
