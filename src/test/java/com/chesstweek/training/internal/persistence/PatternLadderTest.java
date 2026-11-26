package com.chesstweek.training.internal.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
