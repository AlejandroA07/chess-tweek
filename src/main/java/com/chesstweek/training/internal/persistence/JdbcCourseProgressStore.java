package com.chesstweek.training.internal.persistence;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCourseProgressStore implements CourseProgressStore {

    private static final String LESSONS = """
            SELECT lesson_key, completed_at_ms
            FROM training_course_lesson_progress
            WHERE account_id = :accountId
              AND course_key = :courseKey
              AND content_version = :contentVersion
            """;
    private static final String EXERCISES = """
            SELECT exercise_key, state, attempts, hint_level
            FROM training_course_exercise_progress
            WHERE account_id = :accountId
              AND course_key = :courseKey
              AND content_version = :contentVersion
            """;
    private static final String START_LESSON = """
            INSERT INTO training_course_lesson_progress (
                account_id, course_key, content_version, lesson_key, started_at_ms, completed_at_ms
            ) VALUES (
                :accountId, :courseKey, :contentVersion, :lessonKey, :timestamp, NULL
            )
            ON CONFLICT (account_id, course_key, content_version, lesson_key) DO NOTHING
            """;
    private static final String UPSERT_ATTEMPT_PROGRESS = """
            INSERT INTO training_course_exercise_progress (
                account_id, course_key, content_version, lesson_key, exercise_key,
                state, attempts, hint_level, updated_at_ms
            ) VALUES (
                :accountId, :courseKey, :contentVersion, :lessonKey, :exerciseKey,
                :state, 1, 0, :timestamp
            )
            ON CONFLICT (
                account_id, course_key, content_version, lesson_key, exercise_key
            ) DO UPDATE SET
                state = CASE
                    WHEN training_course_exercise_progress.state IN ('solved', 'revealed')
                        THEN training_course_exercise_progress.state
                    ELSE excluded.state
                END,
                attempts = training_course_exercise_progress.attempts + 1,
                updated_at_ms = excluded.updated_at_ms
            """;
    private static final String INSERT_ATTEMPT = """
            INSERT INTO training_course_exercise_attempt (
                account_id, course_key, content_version, lesson_key, exercise_key,
                move_uci, outcome, created_at_ms
            ) VALUES (
                :accountId, :courseKey, :contentVersion, :lessonKey, :exerciseKey,
                :moveUci, :outcome, :timestamp
            )
            """;
    private static final String UPSERT_HINT = """
            INSERT INTO training_course_exercise_progress (
                account_id, course_key, content_version, lesson_key, exercise_key,
                state, attempts, hint_level, updated_at_ms
            ) VALUES (
                :accountId, :courseKey, :contentVersion, :lessonKey, :exerciseKey,
                'in_progress', 0, :level, :timestamp
            )
            ON CONFLICT (
                account_id, course_key, content_version, lesson_key, exercise_key
            ) DO UPDATE SET
                hint_level = MAX(training_course_exercise_progress.hint_level, excluded.hint_level),
                updated_at_ms = excluded.updated_at_ms
            """;
    private static final String UPSERT_REVEAL = """
            INSERT INTO training_course_exercise_progress (
                account_id, course_key, content_version, lesson_key, exercise_key,
                state, attempts, hint_level, updated_at_ms
            ) VALUES (
                :accountId, :courseKey, :contentVersion, :lessonKey, :exerciseKey,
                'revealed', 0, 0, :timestamp
            )
            ON CONFLICT (
                account_id, course_key, content_version, lesson_key, exercise_key
            ) DO UPDATE SET
                state = CASE
                    WHEN training_course_exercise_progress.state = 'solved' THEN 'solved'
                    ELSE 'revealed'
                END,
                updated_at_ms = excluded.updated_at_ms
            """;
    private static final String COMPLETE_LESSON = """
            UPDATE training_course_lesson_progress
            SET completed_at_ms = COALESCE(completed_at_ms, :timestamp)
            WHERE account_id = :accountId
              AND course_key = :courseKey
              AND content_version = :contentVersion
              AND lesson_key = :lessonKey
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCourseProgressStore(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Snapshot load(String accountId, String courseKey, int contentVersion) {
        MapSqlParameterSource parameters = base(accountId, courseKey, contentVersion);
        Map<String, LessonRow> lessons = new HashMap<>();
        jdbc.query(LESSONS, parameters, (RowCallbackHandler) result -> lessons.put(
                result.getString("lesson_key"),
                new LessonRow(result.getObject("completed_at_ms") != null)));
        Map<String, ExerciseRow> exercises = new HashMap<>();
        jdbc.query(EXERCISES, parameters, (RowCallbackHandler) result -> exercises.put(
                result.getString("exercise_key"),
                new ExerciseRow(
                        State.valueOf(result.getString("state").toUpperCase(Locale.ROOT)),
                        result.getInt("attempts"),
                        result.getInt("hint_level"))));
        return new Snapshot(Map.copyOf(lessons), Map.copyOf(exercises));
    }

    @Override
    public void startLesson(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            long timestamp) {
        jdbc.update(START_LESSON, lesson(accountId, courseKey, contentVersion, lessonKey, timestamp));
    }

    @Override
    public void recordAttempt(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            String exerciseKey,
            String moveUci,
            boolean correct,
            long timestamp) {
        MapSqlParameterSource parameters = exercise(
                        accountId, courseKey, contentVersion, lessonKey, exerciseKey, timestamp)
                .addValue("moveUci", moveUci)
                .addValue("state", correct ? "solved" : "in_progress")
                .addValue("outcome", correct ? "correct" : "incorrect");
        jdbc.update(UPSERT_ATTEMPT_PROGRESS, parameters);
        jdbc.update(INSERT_ATTEMPT, parameters);
    }

    @Override
    public void recordHint(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            String exerciseKey,
            int level,
            long timestamp) {
        jdbc.update(
                UPSERT_HINT,
                exercise(accountId, courseKey, contentVersion, lessonKey, exerciseKey, timestamp)
                        .addValue("level", level));
    }

    @Override
    public void reveal(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            String exerciseKey,
            long timestamp) {
        jdbc.update(
                UPSERT_REVEAL,
                exercise(accountId, courseKey, contentVersion, lessonKey, exerciseKey, timestamp));
    }

    @Override
    public void completeLesson(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            long timestamp) {
        jdbc.update(
                COMPLETE_LESSON,
                lesson(accountId, courseKey, contentVersion, lessonKey, timestamp));
    }

    private static MapSqlParameterSource base(
            String accountId, String courseKey, int contentVersion) {
        return new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("courseKey", courseKey)
                .addValue("contentVersion", contentVersion);
    }

    private static MapSqlParameterSource lesson(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            long timestamp) {
        return base(accountId, courseKey, contentVersion)
                .addValue("lessonKey", lessonKey)
                .addValue("timestamp", timestamp);
    }

    private static MapSqlParameterSource exercise(
            String accountId,
            String courseKey,
            int contentVersion,
            String lessonKey,
            String exerciseKey,
            long timestamp) {
        return lesson(accountId, courseKey, contentVersion, lessonKey, timestamp)
                .addValue("exerciseKey", exerciseKey);
    }
}
