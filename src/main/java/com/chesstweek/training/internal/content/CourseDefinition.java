package com.chesstweek.training.internal.content;

import java.util.List;

record CourseDefinition(
        String key,
        int version,
        String title,
        String subtitle,
        String attribution,
        List<ModuleDefinition> modules) {

    record ModuleDefinition(
            String key,
            String title,
            String description,
            String status,
            List<LessonDefinition> lessons) {}

    record LessonDefinition(
            String key,
            String title,
            String summary,
            int estimatedMinutes,
            List<String> explanation,
            List<String> checklist,
            List<ExerciseDefinition> exercises) {}

    record ExerciseDefinition(
            String key,
            String fen,
            String orientation,
            String difficultyTier,
            String prompt,
            List<String> acceptedMoves,
            List<String> solutionLine,
            List<String> hints,
            String correctFeedback,
            String incorrectFeedback,
            String explanation,
            SourceDefinition source) {}

    record SourceDefinition(
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
