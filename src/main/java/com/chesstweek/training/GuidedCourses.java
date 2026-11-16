package com.chesstweek.training;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The public interface for guided, authored training courses. Course content is readable without
 * an account; progress and exercise actions are always scoped to an authorized account ID.
 */
public interface GuidedCourses {

    String COURSE_KEY = "grandmaster-thinking-method";

    Course course();

    Progress progress(String accountId);

    Optional<Progress> startLesson(String accountId, String lessonKey);

    AttemptResult attempt(String accountId, String exerciseKey, String moveUci);

    HintResult hint(String accountId, String exerciseKey, int level);

    RevealResult reveal(String accountId, String exerciseKey);

    CompletionResult completeLesson(String accountId, String lessonKey);

    record Course(
            String key,
            int version,
            String title,
            String subtitle,
            String attribution,
            List<CourseModule> modules) {}

    record CourseModule(
            String key,
            String title,
            String description,
            ModuleStatus status,
            List<Lesson> lessons) {}

    enum ModuleStatus {
        AVAILABLE,
        PLANNED
    }

    record Lesson(
            String key,
            String title,
            String summary,
            int estimatedMinutes,
            List<String> explanation,
            List<String> checklist,
            List<Exercise> exercises) {}

    record Exercise(
            String key,
            String fen,
            Orientation orientation,
            String prompt) {}

    enum Orientation {
        WHITE,
        BLACK
    }

    record Progress(
            String courseKey,
            int contentVersion,
            int completedLessons,
            int totalLessons,
            String recommendedLessonKey,
            Map<String, LessonProgress> lessons) {}

    record LessonProgress(
            LessonState state,
            Map<String, ExerciseProgress> exercises) {}

    enum LessonState {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED
    }

    record ExerciseProgress(ExerciseState state, int attempts, int hintLevel) {}

    enum ExerciseState {
        NOT_STARTED,
        IN_PROGRESS,
        SOLVED,
        REVEALED
    }

    record AttemptResult(
            AttemptOutcome outcome,
            String feedback,
            String solutionMove,
            String explanation) {}

    enum AttemptOutcome {
        CORRECT,
        INCORRECT,
        INVALID_MOVE,
        NOT_FOUND
    }

    record HintResult(HintOutcome outcome, int level, String text) {}

    enum HintOutcome {
        SHOWN,
        INVALID_LEVEL,
        NOT_FOUND
    }

    record RevealResult(
            RevealOutcome outcome,
            String solutionMove,
            String explanation) {}

    enum RevealOutcome {
        REVEALED,
        NOT_FOUND
    }

    record CompletionResult(CompletionOutcome outcome, Progress progress) {}

    enum CompletionOutcome {
        COMPLETED,
        INCOMPLETE,
        NOT_FOUND
    }
}
