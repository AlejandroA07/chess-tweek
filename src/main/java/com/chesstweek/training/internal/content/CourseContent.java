package com.chesstweek.training.internal.content;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.chesstweek.training.GuidedCourses;

public record CourseContent(
        GuidedCourses.Course course,
        Map<String, GuidedCourses.Lesson> lessons,
        Map<String, ExerciseAnswer> answers) {

    public Optional<GuidedCourses.Lesson> lesson(String key) {
        return Optional.ofNullable(lessons.get(key));
    }

    public Optional<ExerciseAnswer> answer(String key) {
        return Optional.ofNullable(answers.get(key));
    }

    public record ExerciseAnswer(
            String lessonKey,
            String fen,
            List<String> acceptedMoves,
            List<String> solutionLine,
            List<String> hints,
            String correctFeedback,
            String incorrectFeedback,
            String explanation,
            SourceMetadata source) {}

    public record SourceMetadata(
            String provider,
            String puzzleId,
            String gameUrl,
            String license,
            int ratingAtSelection,
            int ratingDeviationAtSelection,
            int popularityAtSelection,
            int playsAtSelection,
            List<String> themes,
            String snapshotDate) {}
}
