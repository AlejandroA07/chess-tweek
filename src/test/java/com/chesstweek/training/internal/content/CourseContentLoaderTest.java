package com.chesstweek.training.internal.content;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.chesstweek.training.GuidedCourses;

import static org.assertj.core.api.Assertions.assertThat;

class CourseContentLoaderTest {

    @Test
    void loadsTheCuratedCourseAndValidatesEveryExerciseSolution() {
        CourseContent content = new CourseContentLoader(JsonMapper.builder().build()).content();

        assertThat(content.course().key()).isEqualTo(GuidedCourses.COURSE_KEY);
        assertThat(content.course().version()).isEqualTo(2);
        assertThat(content.course().modules()).hasSize(7);
        assertThat(content.course().modules().getFirst().lessons()).hasSize(4);
        assertThat(content.answers()).hasSize(12);
        assertThat(content.course().modules().stream()
                        .flatMap(module -> module.lessons().stream())
                        .flatMap(lesson -> lesson.exercises().stream())
                        .map(GuidedCourses.Exercise::key))
                .containsExactlyInAnyOrderElementsOf(content.answers().keySet());
        assertThat(content.course().attribution())
                .contains("Lichess CC0 puzzle database")
                .contains("neither Lichess nor GM Igor Smirnov endorses it");
        assertThat(content.course().modules().getFirst().lessons())
                .allSatisfy(lesson -> assertThat(lesson.exercises())
                        .extracting(GuidedCourses.Exercise::difficultyTier)
                        .containsExactly(
                                GuidedCourses.DifficultyTier.FOUNDATION,
                                GuidedCourses.DifficultyTier.DEVELOPMENT,
                                GuidedCourses.DifficultyTier.STRETCH));
        assertThat(content.answers().values())
                .extracting(answer -> answer.source().puzzleId())
                .doesNotHaveDuplicates();
        assertThat(content.answers().values())
                .allSatisfy(answer -> {
                    assertThat(answer.solutionLine()).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(answer.solutionLine().getFirst())
                            .isIn(answer.acceptedMoves());
                    assertThat(answer.source().provider()).isEqualTo("lichess");
                    assertThat(answer.source().license()).isEqualTo("CC0-1.0");
                });
    }
}
