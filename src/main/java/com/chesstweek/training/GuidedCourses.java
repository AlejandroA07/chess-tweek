package com.chesstweek.training;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The public interface for guided, authored training courses. Course content is readable without
 * an account; progress and exercise actions are always scoped to an authorized account ID.
 *
 * <p>Every Exercise references a shared Lichess Puzzle. Solving it is a full {@link PuzzleTraining}
 * attempt on that Puzzle, so Wrong Moves, hints (including the written course hints), Show
 * solution, and Solve Time update the Puzzle's normal Training List state. Lesson and Exercise
 * progress stay separate and never move backwards.
 */
public interface GuidedCourses {

    String COURSE_KEY = "grandmaster-thinking-method";

    Course course();

    Progress progress(String accountId);

    Optional<Progress> startLesson(String accountId, String lessonKey);

    /** Starts a full Puzzle attempt on the Exercise's Puzzle; empty for an unknown Exercise. */
    Optional<PuzzleTraining.Attempt> startExercise(String accountId, String exerciseKey);

    /** One move of an attempt that {@link #startExercise} started for this Exercise. */
    ExerciseMoveResult exerciseMove(
            String accountId, String exerciseKey, long attemptId, String moveUci);

    /** A written hint; it counts as a hint for the Puzzle's Training List. */
    HintResult exerciseHint(String accountId, String exerciseKey, long attemptId, int level);

    /** Show solution: completes the attempt as hinted and reveals the remaining line. */
    ExerciseRevealResult exerciseReveal(String accountId, String exerciseKey, long attemptId);

    /** The full answer of an Exercise already solved or revealed; no Puzzle state changes. */
    ReviewResult review(String accountId, String exerciseKey);

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
            DifficultyTier difficultyTier,
            int sourceRating,
            String prompt) {}

    enum DifficultyTier {
        FOUNDATION,
        DEVELOPMENT,
        STRETCH
    }

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

    /**
     * The Puzzle's move result; {@code feedback} is the authored text for a Wrong Move or the
     * solved line, and {@code explanation} is set once the line is solved.
     */
    record ExerciseMoveResult(
            PuzzleTraining.MoveResult move, String feedback, String explanation) {}

    record HintResult(HintOutcome outcome, int level, String text) {}

    enum HintOutcome {
        SHOWN,
        INVALID_LEVEL,
        NOT_FOUND,
        /** The attempt already ended. */
        FINISHED
    }

    record ExerciseRevealResult(PuzzleTraining.RevealResult reveal, String explanation) {}

    record ReviewResult(ReviewOutcome outcome, List<String> solutionLine, String explanation) {}

    enum ReviewOutcome {
        REVIEWED,
        /** Solve or reveal the Exercise before reviewing its answer. */
        NOT_RESOLVED,
        NOT_FOUND
    }

    record CompletionResult(CompletionOutcome outcome, Progress progress) {}

    enum CompletionOutcome {
        COMPLETED,
        INCOMPLETE,
        NOT_FOUND
    }
}
