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

    @Test
    void keepsAccountScopedLessonAndExerciseProgressAcrossRestart(@TempDir Path directory) {
        Path database = directory.resolve("runtime/chess-tweek.db");
        try (var application = start(database)) {
            GuidedCourses courses = application.getBean(GuidedCourses.class);
            assertThat(courses.course().modules()).hasSize(7);
            assertThat(courses.progress("owner-id").completedLessons()).isZero();

            assertThat(courses.startLesson("owner-id", "read-the-last-move")).isPresent();
            assertThat(courses.hint("owner-id", "intermezzo-after-queen-capture", 1).text())
                    .isEqualTo("Do not recapture automatically; list every forcing move first.");
            assertThat(courses.attempt("owner-id", "intermezzo-after-queen-capture", "b2b9").outcome())
                    .isEqualTo(GuidedCourses.AttemptOutcome.INVALID_MOVE);
            assertThat(courses.attempt("owner-id", "intermezzo-after-queen-capture", "b2b1").outcome())
                    .isEqualTo(GuidedCourses.AttemptOutcome.INCORRECT);
            assertThat(courses.attempt("owner-id", "intermezzo-after-queen-capture", "b2g7").outcome())
                    .isEqualTo(GuidedCourses.AttemptOutcome.CORRECT);
            courses.reveal("owner-id", "counter-before-recapture");
            courses.reveal("owner-id", "advanced-pawn-race");
            assertThat(courses.completeLesson("owner-id", "read-the-last-move").outcome())
                    .isEqualTo(GuidedCourses.CompletionOutcome.COMPLETED);

            assertThat(courses.progress("other-id").completedLessons()).isZero();
            assertThat(application.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM training_course_exercise_attempt", Integer.class))
                    .isEqualTo(2);
        }

        try (var restarted = start(database)) {
            GuidedCourses.Progress progress = restarted.getBean(GuidedCourses.class)
                    .progress("owner-id");
            assertThat(progress.completedLessons()).isEqualTo(1);
            assertThat(progress.lessons().get("read-the-last-move").state())
                    .isEqualTo(GuidedCourses.LessonState.COMPLETED);
            assertThat(progress.lessons().get("read-the-last-move").exercises()
                            .get("intermezzo-after-queen-capture"))
                    .isEqualTo(new GuidedCourses.ExerciseProgress(
                            GuidedCourses.ExerciseState.SOLVED, 2, 1));
            assertThat(progress.recommendedLessonKey()).isEqualTo("generate-candidates");
        }
    }

    private static ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + database, "--logging.level.root=OFF");
    }
}
