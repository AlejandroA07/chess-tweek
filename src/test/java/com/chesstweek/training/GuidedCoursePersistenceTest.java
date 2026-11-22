package com.chesstweek.training;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;

import static org.assertj.core.api.Assertions.assertThat;

class GuidedCoursePersistenceTest {

    private static final String INTERMEZZO = "intermezzo-after-queen-capture";

    @Test
    void keepsAccountScopedLessonAndExerciseProgressAcrossRestart(@TempDir Path directory) {
        Path database = directory.resolve("runtime/chess-tweek.db");
        long puzzleId;
        try (var application = start(database)) {
            GuidedCourses courses = application.getBean(GuidedCourses.class);
            assertThat(courses.course().modules()).hasSize(7);
            assertThat(courses.progress("owner-id").completedLessons()).isZero();

            assertThat(courses.startLesson("owner-id", "read-the-last-move")).isPresent();
            PuzzleTraining.Attempt attempt =
                    courses.startExercise("owner-id", INTERMEZZO).orElseThrow();
            puzzleId = attempt.puzzleId();
            assertThat(attempt.currentList()).isNull();
            assertThat(courses.exerciseHint("owner-id", INTERMEZZO, attempt.attemptId(), 1).text())
                    .isEqualTo("Do not recapture automatically; list every forcing move first.");
            assertThat(move(courses, attempt, "b2b9").move().outcome())
                    .isEqualTo(PuzzleTraining.MoveOutcome.ILLEGAL);
            GuidedCourses.ExerciseMoveResult wrong = move(courses, attempt, "b2b1");
            assertThat(wrong.move().outcome()).isEqualTo(PuzzleTraining.MoveOutcome.WRONG);
            assertThat(wrong.feedback()).startsWith("Re-read the queen's last move");
            // The first correct move is not the end: the whole line must be played.
            GuidedCourses.ExerciseMoveResult first = move(courses, attempt, "b2g7");
            assertThat(first.move().outcome()).isEqualTo(PuzzleTraining.MoveOutcome.CORRECT);
            assertThat(first.move().opponentReply()).isEqualTo("g8g7");
            assertThat(first.explanation()).isNull();
            GuidedCourses.ExerciseMoveResult solved = move(courses, attempt, "g2f3");
            assertThat(solved.move().outcome()).isEqualTo(PuzzleTraining.MoveOutcome.SOLVED);
            assertThat(solved.explanation()).startsWith("Qxg7+ forces Kxg7");
            // The written hint counts as a hint for the shared Puzzle's Training List.
            assertThat(solved.move().completion().performance().list())
                    .isEqualTo(PuzzleTraining.TrainingList.HARD_TO_SOLVE);

            for (String exercise : new String[] {"counter-before-recapture", "advanced-pawn-race"}) {
                PuzzleTraining.Attempt revealed =
                        courses.startExercise("owner-id", exercise).orElseThrow();
                assertThat(courses.exerciseReveal("owner-id", exercise, revealed.attemptId())
                        .reveal().outcome()).isEqualTo(PuzzleTraining.RevealOutcome.REVEALED);
            }
            assertThat(courses.completeLesson("owner-id", "read-the-last-move").outcome())
                    .isEqualTo(GuidedCourses.CompletionOutcome.COMPLETED);

            assertThat(courses.progress("other-id").completedLessons()).isZero();
            assertThat(application.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM training_course_exercise_attempt", Integer.class))
                    .isEqualTo(2);
            assertThat(application.getBean(JdbcTemplate.class).queryForList(
                    "SELECT DISTINCT entry FROM training_puzzle_attempt", String.class))
                    .containsExactly("method");
        }

        try (var restarted = start(database)) {
            GuidedCourses.Progress progress = restarted.getBean(GuidedCourses.class)
                    .progress("owner-id");
            assertThat(progress.completedLessons()).isEqualTo(1);
            assertThat(progress.lessons().get("read-the-last-move").state())
                    .isEqualTo(GuidedCourses.LessonState.COMPLETED);
            assertThat(progress.lessons().get("read-the-last-move").exercises()
                            .get(INTERMEZZO))
                    .isEqualTo(new GuidedCourses.ExerciseProgress(
                            GuidedCourses.ExerciseState.SOLVED, 2, 1));
            assertThat(progress.recommendedLessonKey()).isEqualTo("generate-candidates");
            assertThat(restarted.getBean(PuzzleTraining.class).performance("owner-id", puzzleId))
                    .contains(new PuzzleTraining.Performance(
                            PuzzleTraining.TrainingList.HARD_TO_SOLVE, 0, 0));
        }
    }

    @Test
    void solvingAgainUpdatesThePuzzleWithoutChangingCourseProgress(@TempDir Path directory) {
        try (var application = start(directory.resolve("runtime/chess-tweek.db"))) {
            GuidedCourses courses = application.getBean(GuidedCourses.class);
            PuzzleTraining training = application.getBean(PuzzleTraining.class);
            PuzzleTraining.Attempt first = courses.startExercise("owner-id", INTERMEZZO).orElseThrow();
            move(courses, first, "b2g7");
            move(courses, first, "g2f3");
            PuzzleTraining.TrainingList placed =
                    training.performance("owner-id", first.puzzleId()).orElseThrow().list();
            assertThat(placed).isIn(
                    PuzzleTraining.TrainingList.RETIRING, PuzzleTraining.TrainingList.WORTH_REPEATING);

            PuzzleTraining.Attempt again = courses.startExercise("owner-id", INTERMEZZO).orElseThrow();
            assertThat(again.currentList()).isEqualTo(placed);
            // One Wrong Move in a repetition moves the Puzzle to Wrong Moves at once.
            move(courses, again, "b2b1");
            assertThat(training.performance("owner-id", again.puzzleId()))
                    .contains(new PuzzleTraining.Performance(
                            PuzzleTraining.TrainingList.WRONG_MOVES, 0, 0));
            move(courses, again, "b2g7");
            move(courses, again, "g2f3");
            assertThat(courses.progress("owner-id").lessons().get("read-the-last-move")
                    .exercises().get(INTERMEZZO).state())
                    .isEqualTo(GuidedCourses.ExerciseState.SOLVED);

            // The answer is reviewable only once resolved, and reviewing changes nothing.
            GuidedCourses.ReviewResult review = courses.review("owner-id", INTERMEZZO);
            assertThat(review.outcome()).isEqualTo(GuidedCourses.ReviewOutcome.REVIEWED);
            assertThat(review.solutionLine()).containsExactly("b2g7", "g8g7", "g2f3");
            assertThat(courses.review("owner-id", "counter-before-recapture").outcome())
                    .isEqualTo(GuidedCourses.ReviewOutcome.NOT_RESOLVED);
            assertThat(courses.review("owner-id", "no-such-exercise").outcome())
                    .isEqualTo(GuidedCourses.ReviewOutcome.NOT_FOUND);
            assertThat(training.performance("owner-id", again.puzzleId()))
                    .contains(new PuzzleTraining.Performance(
                            PuzzleTraining.TrainingList.WRONG_MOVES, 0, 0));
        }
    }

    @Test
    void anExerciseActsOnlyOnItsOwnMethodAttempt(@TempDir Path directory) {
        try (var application = start(directory.resolve("runtime/chess-tweek.db"))) {
            GuidedCourses courses = application.getBean(GuidedCourses.class);
            PuzzleTraining training = application.getBean(PuzzleTraining.class);
            PuzzleTraining.Attempt attempt = courses.startExercise("owner-id", INTERMEZZO).orElseThrow();
            assertThat(courses.startExercise("owner-id", "no-such-exercise")).isEmpty();

            // Another account, another Exercise, or a non-method attempt is never accepted.
            assertThat(courses.exerciseMove("other-id", INTERMEZZO, attempt.attemptId(), "b2g7")
                    .move().outcome()).isEqualTo(PuzzleTraining.MoveOutcome.NOT_FOUND);
            assertThat(courses.exerciseMove("owner-id", "counter-before-recapture",
                    attempt.attemptId(), "b2g7").move().outcome())
                    .isEqualTo(PuzzleTraining.MoveOutcome.NOT_FOUND);
            assertThat(courses.exerciseHint("owner-id", "counter-before-recapture",
                    attempt.attemptId(), 1).outcome())
                    .isEqualTo(GuidedCourses.HintOutcome.NOT_FOUND);
            assertThat(courses.exerciseReveal("owner-id", "counter-before-recapture",
                    attempt.attemptId()).reveal().outcome())
                    .isEqualTo(PuzzleTraining.RevealOutcome.NOT_FOUND);
            PuzzleTraining.Attempt random = training.startRandom("owner-id").orElseThrow();
            assertThat(courses.exerciseMove("owner-id", INTERMEZZO, random.attemptId(), "b2g7")
                    .move().outcome()).isEqualTo(PuzzleTraining.MoveOutcome.NOT_FOUND);
            assertThat(courses.exerciseHint("owner-id", INTERMEZZO, attempt.attemptId(), 3)
                    .outcome()).isEqualTo(GuidedCourses.HintOutcome.INVALID_LEVEL);

            // Starting the Random attempt ended the Exercise's attempt.
            assertThat(courses.exerciseMove("owner-id", INTERMEZZO, attempt.attemptId(), "b2g7")
                    .move().outcome()).isEqualTo(PuzzleTraining.MoveOutcome.FINISHED);
            assertThat(courses.exerciseHint("owner-id", INTERMEZZO, attempt.attemptId(), 1)
                    .outcome()).isEqualTo(GuidedCourses.HintOutcome.FINISHED);
            assertThat(training.performance("owner-id", attempt.puzzleId())).isEmpty();
            assertThat(courses.progress("owner-id").lessons().get("read-the-last-move")
                    .exercises().get(INTERMEZZO).state())
                    .isEqualTo(GuidedCourses.ExerciseState.NOT_STARTED);
        }
    }

    private static GuidedCourses.ExerciseMoveResult move(
            GuidedCourses courses, PuzzleTraining.Attempt attempt, String uci) {
        return courses.exerciseMove("owner-id", INTERMEZZO, attempt.attemptId(), uci);
    }

    private static ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + database, "--logging.level.root=OFF");
    }
}
