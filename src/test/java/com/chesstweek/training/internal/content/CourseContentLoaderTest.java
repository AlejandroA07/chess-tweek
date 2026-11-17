package com.chesstweek.training.internal.content;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.chesstweek.training.GuidedCourses;

import static org.assertj.core.api.Assertions.assertThat;

class CourseContentLoaderTest {

    @Test
    void loadsTheOriginalCourseAndValidatesEveryExerciseSolution() {
        CourseContent content = new CourseContentLoader(JsonMapper.builder().build()).content();

        assertThat(content.course().key()).isEqualTo(GuidedCourses.COURSE_KEY);
        assertThat(content.course().modules()).hasSize(7);
        assertThat(content.course().modules().getFirst().lessons()).hasSize(4);
        assertThat(content.answers()).hasSize(12);
        assertThat(content.course().modules().stream()
                        .flatMap(module -> module.lessons().stream())
                        .flatMap(lesson -> lesson.exercises().stream())
                        .map(GuidedCourses.Exercise::key))
                .containsExactlyInAnyOrderElementsOf(content.answers().keySet());
        assertThat(content.course().attribution())
                .contains("independent Chess Tweek course")
                .contains("original to Chess Tweek");
    }
}
