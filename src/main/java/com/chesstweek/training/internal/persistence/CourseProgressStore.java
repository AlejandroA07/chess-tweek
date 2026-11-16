package com.chesstweek.training.internal.persistence;

import java.util.Map;

public interface CourseProgressStore {

    Snapshot load(String accountId, String courseKey, int contentVersion);

    void startLesson(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            long timestamp);

    void recordAttempt(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            String exerciseKey,
            String moveUci,
            boolean correct,
            long timestamp);

    void recordHint(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            String exerciseKey,
            int level,
            long timestamp);

    void reveal(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            String exerciseKey,
            long timestamp);

    void completeLesson(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            long timestamp);

    record Snapshot(
            Map<String, LessonRow> lessons,
            Map<String, ExerciseRow> exercises) {}

    record LessonRow(boolean completed) {}

    record ExerciseRow(State state, int attempts, int hintLevel) {}

    enum State {
        IN_PROGRESS,
        SOLVED,
        REVEALED
    }
}
