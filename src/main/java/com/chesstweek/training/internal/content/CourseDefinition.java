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
            String prompt,
            List<String> acceptedMoves,
            List<String> hints,
            String correctFeedback,
            String incorrectFeedback,
            String explanation) {}
}
