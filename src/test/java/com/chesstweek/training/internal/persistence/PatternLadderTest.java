package com.chesstweek.training.internal.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.training.PatternLadders;
import com.chesstweek.training.PatternLadders.NextOutcome;
import com.chesstweek.training.PatternLadders.RatingPackage;
import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.PuzzleTraining;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pattern ladders over the built-in Puzzles, re-tagged and re-rated per test: the quality gates,
 * the pattern groups, rating order inside a package, resume and restart, the Mastered skip, and
 * that ladder attempts are ordinary Puzzle attempts.
 */
class PatternLadderTest {

    private static final String OWNER = "owner-id";
    private static final String OTHER = "other-id";

    @TempDir
    Path directory;

    @Test
    void onlyQualityGatedMembersOfPatternThemesFormLadders() {
        try (var application = start()) {
            List<Long> ids = fixture(application);
            PatternLadders ladders = ladders(application);

            assertThat(ladders.patterns()).containsExactly(
                    new PatternLadders.Pattern("fork", "Fork", "Motifs", 5));

            PatternLadders.Ladder fork = ladders.ladder(OWNER, "fork").orElseThrow();
            assertThat(fork.packages()).extracting(PatternLadders.PackageEntry::puzzles)
                    .containsExactly(1, 3, 1, 0, 0, 0);
            assertThat(fork.packages()).allMatch(entry -> entry.progress() == null);

            // mateIn1 is a Theme in the Mates group, which is not a pattern ladder.
            assertThat(ladders.ladder(OWNER, "mateIn1")).isEmpty();
            assertThat(ladders.next(OWNER, "mateIn1", RatingPackage.FROM_1000).outcome())
                    .isEqualTo(NextOutcome.NOT_FOUND);
            assertThat(ladders.ladder(OWNER, "no such pattern")).isEmpty();
            assertThat(ids).hasSize(7);
        }
    }

    @Test
    void aPackageRunsFromTheLowestRatingAndResumesWhereTheOwnerStopped() {
        try (var application = start()) {
            List<Long> ids = fixture(application);
            PatternLadders ladders = ladders(application);

            PatternLadders.Next first = ladders.next(OWNER, "fork", RatingPackage.FROM_1000);
            assertThat(first.outcome()).isEqualTo(NextOutcome.STARTED);
            assertThat(first.attempt().puzzleId()).isEqualTo(ids.get(1));
            assertThat(first.attempt().rating()).isEqualTo(1200);
            assertThat(first.progress()).isEqualTo(new PatternLadders.Progress(1, 1200));

            // Equal ratings run in Puzzle ID order.
            assertThat(ladders.next(OWNER, "fork", RatingPackage.FROM_1000).attempt().puzzleId())
                    .isEqualTo(ids.get(2));
            assertThat(ladders.ladder(OWNER, "fork").orElseThrow().packages().get(1).progress())
                    .isEqualTo(new PatternLadders.Progress(2, 1200));

            // Another package and another account keep their own place.
            assertThat(ladders.next(OWNER, "fork", RatingPackage.UP_TO_1000).attempt().puzzleId())
                    .isEqualTo(ids.get(0));
            assertThat(ladders.next(OTHER, "fork", RatingPackage.FROM_1000).attempt().puzzleId())
                    .isEqualTo(ids.get(1));

            PatternLadders.Next third = ladders.next(OWNER, "fork", RatingPackage.FROM_1000);
            assertThat(third.attempt().puzzleId()).isEqualTo(ids.get(3));
            assertThat(third.progress()).isEqualTo(new PatternLadders.Progress(3, 1350));
            assertThat(ladders.next(OWNER, "fork", RatingPackage.FROM_1000).outcome())
                    .isEqualTo(NextOutcome.COMPLETE);
            // Reaching the end leaves the place unchanged.
            assertThat(ladders.ladder(OWNER, "fork").orElseThrow().packages().get(1).progress())
                    .isEqualTo(new PatternLadders.Progress(3, 1350));

            assertThat(ladders.restart(OWNER, "fork", RatingPackage.FROM_1000)).isTrue();
            assertThat(ladders.ladder(OWNER, "fork").orElseThrow().packages().get(1).progress())
                    .isNull();
            PatternLadders.Next again = ladders.next(OWNER, "fork", RatingPackage.FROM_1000);
            assertThat(again.attempt().puzzleId()).isEqualTo(ids.get(1));
            assertThat(again.progress()).isEqualTo(new PatternLadders.Progress(1, 1200));
            assertThat(ladders.restart(OWNER, "mateIn1", RatingPackage.FROM_1000)).isFalse();
        }
    }

    @Test
    void masteredAndRemovedPuzzlesAreSkipped() {
        try (var application = start()) {
            List<Long> ids = fixture(application);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            jdbc.update("""
                    INSERT INTO training_puzzle_performance (
                        account_id, puzzle_id, list, successes, strikes, classified_at_ms, updated_at_ms
                    ) VALUES (?, ?, 'mastered', 0, 0, 0, 0)
                    """, OWNER, ids.get(1));
            // A Puzzle a later import removed is skipped even before the ladder index is rebuilt.
            jdbc.update("DELETE FROM training_puzzle_current WHERE puzzle_id = ?", ids.get(2));
            PatternLadders ladders = ladders(application);

            assertThat(ladders.next(OWNER, "fork", RatingPackage.FROM_1000).attempt().puzzleId())
                    .isEqualTo(ids.get(3));
            assertThat(ladders.next(OWNER, "fork", RatingPackage.FROM_1000).outcome())
                    .isEqualTo(NextOutcome.COMPLETE);
            // Mastered is per account.
            assertThat(ladders.next(OTHER, "fork", RatingPackage.FROM_1000).attempt().puzzleId())
                    .isEqualTo(ids.get(1));
        }
    }

    @Test
    void aLadderAttemptUpdatesTheSharedTrainingListState() {
        try (var application = start()) {
            fixture(application);
            PatternLadders ladders = ladders(application);
            PuzzleTraining training = application.getBean(PuzzleTraining.class);

            PuzzleTraining.Attempt attempt =
                    ladders.next(OWNER, "fork", RatingPackage.FROM_1400).attempt();
            List<String> solution = application.getBean(PuzzleLibrary.class)
                    .puzzle(attempt.puzzleId()).orElseThrow().solutionMoves();
            PuzzleTraining.MoveResult result = null;
            for (int index = 0; index < solution.size(); index += 2) {
                result = training.move(OWNER, attempt.attemptId(), solution.get(index));
            }
            assertThat(result.outcome()).isEqualTo(PuzzleTraining.MoveOutcome.SOLVED);
            assertThat(training.performance(OWNER, attempt.puzzleId())).contains(
                    new PuzzleTraining.Performance(PuzzleTraining.TrainingList.RETIRING, 0, 0));
        }
    }

    @Test
    void aJumpMovesThePlaceSoTheChosenPositionComesNext() {
        try (var application = start()) {
            List<Long> ids = fixture(application);
            PatternLadders ladders = ladders(application);

            assertThat(ladders.jump(OWNER, "fork", RatingPackage.FROM_1000, 3))
                    .isEqualTo(PatternLadders.JumpOutcome.MOVED);
            assertThat(ladders.ladder(OWNER, "fork").orElseThrow().packages().get(1).progress())
                    .isEqualTo(new PatternLadders.Progress(2, 1200));
            PatternLadders.Next third = ladders.next(OWNER, "fork", RatingPackage.FROM_1000);
            assertThat(third.attempt().puzzleId()).isEqualTo(ids.get(3));
            assertThat(third.progress()).isEqualTo(new PatternLadders.Progress(3, 1350));

            // Position 1 is the start of the package again.
            assertThat(ladders.jump(OWNER, "fork", RatingPackage.FROM_1000, 1))
                    .isEqualTo(PatternLadders.JumpOutcome.MOVED);
            assertThat(ladders.next(OWNER, "fork", RatingPackage.FROM_1000).attempt().puzzleId())
                    .isEqualTo(ids.get(1));

            for (int outside : new int[] {0, 4, -1}) {
                assertThat(ladders.jump(OWNER, "fork", RatingPackage.FROM_1000, outside))
                        .isEqualTo(PatternLadders.JumpOutcome.OUT_OF_RANGE);
            }
            assertThat(ladders.jump(OWNER, "fork", RatingPackage.FROM_2600, 1))
                    .isEqualTo(PatternLadders.JumpOutcome.OUT_OF_RANGE);
            assertThat(ladders.jump(OWNER, "mateIn1", RatingPackage.FROM_1000, 1))
                    .isEqualTo(PatternLadders.JumpOutcome.NOT_FOUND);
            // Another account's place is untouched.
            assertThat(ladders.next(OTHER, "fork", RatingPackage.FROM_1000).attempt().puzzleId())
                    .isEqualTo(ids.get(1));
        }
    }

    @Test
    void aFastLadderClimbsTheRatingStepsThenCombinesWithOtherPatterns() {
        try (var application = start()) {
            FastFixture fixture = fastFixture(application);
            PatternLadders ladders = ladders(application);

            PatternLadders.FastLadder fast = ladders.startFast(OWNER, "fork").orElseThrow();
            assertThat(fast.total()).isEqualTo(5);
            List<PatternLadders.FastNext> steps = new ArrayList<>();
            for (int position = 1; position <= 5; position++) {
                PatternLadders.FastNext next = ladders.nextFast(OWNER, fast.fastLadderId());
                assertThat(next.outcome()).isEqualTo(PatternLadders.FastOutcome.STARTED);
                assertThat(next.position()).isEqualTo(position);
                assertThat(next.total()).isEqualTo(5);
                steps.add(next);
            }
            assertThat(ladders.nextFast(OWNER, fast.fastLadderId()).outcome())
                    .isEqualTo(PatternLadders.FastOutcome.COMPLETE);

            // One Puzzle per rating step that has one, lowest step first.
            assertThat(steps.subList(0, 3)).extracting(next -> next.attempt().puzzleId())
                    .containsExactly(fixture.a(), fixture.b(), fixture.c());
            assertThat(steps.subList(0, 3)).extracting(next -> next.step().range())
                    .containsExactly(new PatternLadders.RatingRange(0, 800),
                            new PatternLadders.RatingRange(800, 1000),
                            new PatternLadders.RatingRange(2000, 2200));
            // Then one Puzzle per other pattern it combines with, nearest to 2000-2199 first.
            Map<String, Long> combinations = new java.util.HashMap<>();
            for (PatternLadders.FastNext next : steps.subList(3, 5)) {
                assertThat(next.step().range()).isNull();
                combinations.put(next.step().combinedWith().key(), next.attempt().puzzleId());
            }
            assertThat(combinations).containsOnlyKeys("pin", "skewer");
            assertThat(combinations.get("pin")).isIn(fixture.d(), fixture.f());
            assertThat(combinations.get("skewer")).isEqualTo(fixture.e());
            assertThat(steps.get(3).step().combinedWith().displayName()).isIn("Pin", "Skewer");

            // A new fast ladder replaces the previous one.
            PatternLadders.FastLadder again = ladders.startFast(OWNER, "fork").orElseThrow();
            assertThat(ladders.nextFast(OWNER, fast.fastLadderId()).outcome())
                    .isEqualTo(PatternLadders.FastOutcome.NOT_FOUND);
            assertThat(ladders.nextFast(OTHER, again.fastLadderId()).outcome())
                    .isEqualTo(PatternLadders.FastOutcome.NOT_FOUND);
            assertThat(ladders.nextFast(OWNER, again.fastLadderId()).outcome())
                    .isEqualTo(PatternLadders.FastOutcome.STARTED);
        }
    }

    @Test
    void aFastLadderSkipsMasteredPuzzlesAndUnknownPatterns() {
        try (var application = start()) {
            FastFixture fixture = fastFixture(application);
            application.getBean(JdbcTemplate.class).update("""
                    INSERT INTO training_puzzle_performance (
                        account_id, puzzle_id, list, successes, strikes, classified_at_ms, updated_at_ms
                    ) VALUES (?, ?, 'mastered', 0, 0, 0, 0)
                    """, OWNER, fixture.c());
            PatternLadders ladders = ladders(application);

            PatternLadders.FastLadder fast = ladders.startFast(OWNER, "fork").orElseThrow();
            // The 2000-2199 step has only C, which is Mastered; H is below the quality gates.
            assertThat(fast.total()).isEqualTo(4);
            List<Long> served = new ArrayList<>();
            for (int position = 1; position <= 4; position++) {
                served.add(ladders.nextFast(OWNER, fast.fastLadderId()).attempt().puzzleId());
            }
            assertThat(served).doesNotContain(fixture.c(), fixture.h()).doesNotHaveDuplicates();
            assertThat(ladders.startFast(OWNER, "mateIn1")).isEmpty();
            assertThat(ladders.startFast(OWNER, "operaMate")).isEmpty();
        }
    }

    @Test
    void aPuzzleInAFastLadderIsRetainedByALaterImport() {
        try (var application = start()) {
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            jdbc.update("""
                    INSERT INTO training_puzzle (
                        provider, external_id, source_fen, source_moves_uci, setup_move_uci,
                        position_fen, solution_uci, game_url, license, created_at_ms
                    )
                    SELECT provider, 'Z0001', source_fen, source_moves_uci, setup_move_uci,
                           position_fen, solution_uci, game_url, license, created_at_ms
                    FROM training_puzzle ORDER BY id LIMIT 1
                    """);
            long puzzleId = jdbc.queryForObject(
                    "SELECT id FROM training_puzzle WHERE external_id = 'Z0001'", Long.class);
            Object[] arguments = java.util.Collections.nCopies(7, puzzleId).toArray();
            assertThat(jdbc.queryForObject(JdbcPuzzleCorpusStore.RETAINED, Integer.class, arguments))
                    .isZero();

            int fork = jdbc.queryForObject("""
                    SELECT id FROM training_puzzle_category
                    WHERE kind = 'theme' AND classification_key = 'fork'
                    """, Integer.class);
            jdbc.update("""
                    INSERT INTO training_puzzle_fast_ladder (account_id, category_id, created_at_ms)
                    VALUES (?, ?, 0)
                    """, OWNER, fork);
            jdbc.update("""
                    INSERT INTO training_puzzle_fast_ladder_item (
                        fast_ladder_id, position, puzzle_id, rating_low, rating_high
                    ) VALUES (last_insert_rowid(), 1, ?, 0, 800)
                    """, puzzleId);
            assertThat(jdbc.queryForObject(JdbcPuzzleCorpusStore.RETAINED, Integer.class, arguments))
                    .isEqualTo(1);
        }
    }

    private record FastFixture(long a, long b, long c, long d, long e, long f, long h) {}

    /**
     * Fork Puzzles A 750, B 950, C 2010 (fork only); D 2250 and F 2300 (fork and pin); E 2900
     * (fork and skewer); and H 2100 (fork and skewer, below the popularity gate).
     */
    private static FastFixture fastFixture(ConfigurableApplicationContext application) {
        JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
        List<Long> ids = jdbc.queryForList(
                "SELECT puzzle_id FROM training_puzzle_current ORDER BY puzzle_id LIMIT 7", Long.class);
        jdbc.update("DELETE FROM training_puzzle_category_member");
        jdbc.update("UPDATE training_puzzle_current SET popularity = 0");
        int[] ratings = {750, 950, 2010, 2250, 2900, 2300, 2100};
        String[] themes = {"fork", "fork", "fork", "fork pin", "fork skewer", "fork pin", "fork skewer"};
        for (int index = 0; index < ids.size(); index++) {
            jdbc.update("""
                    UPDATE training_puzzle_current
                    SET rating = ?, rating_deviation = 80, popularity = ?, play_count = 500,
                        source_themes = ?
                    WHERE puzzle_id = ?
                    """, ratings[index], index == 6 ? 50 : 90, themes[index], ids.get(index));
            for (String theme : themes[index].split(" ")) {
                jdbc.update("""
                        INSERT INTO training_puzzle_category_member (category_id, puzzle_id)
                        SELECT id, ? FROM training_puzzle_category
                        WHERE kind = 'theme' AND classification_key = ?
                        """, ids.get(index), theme);
            }
        }
        application.getBean(PuzzleCategoryIndex.class).refreshCounts();
        return new FastFixture(ids.get(0), ids.get(1), ids.get(2), ids.get(3), ids.get(4),
                ids.get(5), ids.get(6));
    }

    /**
     * Re-tags the built-in Puzzles: five quality fork Puzzles rated 950, 1200, 1200, 1350, and
     * 1500 (IDs in that order), a fork Puzzle below the popularity gate, and a quality mateIn1
     * Puzzle. Returns the seven IDs in that order.
     */
    private static List<Long> fixture(ConfigurableApplicationContext application) {
        JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
        List<Long> ids = new ArrayList<>(jdbc.queryForList(
                "SELECT puzzle_id FROM training_puzzle_current ORDER BY puzzle_id LIMIT 7", Long.class));
        jdbc.update("DELETE FROM training_puzzle_category_member");
        jdbc.update("UPDATE training_puzzle_current SET popularity = 0");
        int[] ratings = {950, 1200, 1200, 1350, 1500, 1100, 1200};
        for (int index = 0; index < ids.size(); index++) {
            boolean gated = index != 5;
            jdbc.update("""
                    UPDATE training_puzzle_current
                    SET rating = ?, rating_deviation = 80, popularity = ?, play_count = 500
                    WHERE puzzle_id = ?
                    """, ratings[index], gated ? 90 : 50, ids.get(index));
            jdbc.update("""
                    INSERT INTO training_puzzle_category_member (category_id, puzzle_id)
                    SELECT id, ? FROM training_puzzle_category
                    WHERE kind = 'theme' AND classification_key = ?
                    """, ids.get(index), index == 6 ? "mateIn1" : "fork");
        }
        application.getBean(PuzzleCategoryIndex.class).refreshCounts();
        return ids;
    }

    private static PatternLadders ladders(ConfigurableApplicationContext application) {
        return application.getBean(PatternLadders.class);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + directory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }
}
