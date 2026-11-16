CREATE TABLE training_course_lesson_progress (
    account_id TEXT NOT NULL CHECK (length(account_id) BETWEEN 1 AND 32),
    course_key TEXT NOT NULL CHECK (length(course_key) BETWEEN 1 AND 64),
    content_version INTEGER NOT NULL CHECK (content_version BETWEEN 1 AND 1000),
    lesson_key TEXT NOT NULL CHECK (length(lesson_key) BETWEEN 1 AND 64),
    started_at_ms INTEGER NOT NULL CHECK (started_at_ms >= 0),
    completed_at_ms INTEGER CHECK (
        completed_at_ms IS NULL OR completed_at_ms >= started_at_ms
    ),
    PRIMARY KEY (account_id, course_key, content_version, lesson_key)
) WITHOUT ROWID;

CREATE TABLE training_course_exercise_progress (
    account_id TEXT NOT NULL,
    course_key TEXT NOT NULL,
    content_version INTEGER NOT NULL,
    lesson_key TEXT NOT NULL,
    exercise_key TEXT NOT NULL CHECK (length(exercise_key) BETWEEN 1 AND 64),
    state TEXT NOT NULL CHECK (state IN ('in_progress', 'solved', 'revealed')),
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 0 AND 10000),
    hint_level INTEGER NOT NULL CHECK (hint_level BETWEEN 0 AND 2),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= 0),
    PRIMARY KEY (
        account_id, course_key, content_version, lesson_key, exercise_key
    ),
    FOREIGN KEY (account_id, course_key, content_version, lesson_key)
        REFERENCES training_course_lesson_progress (
            account_id, course_key, content_version, lesson_key
        ) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE training_course_exercise_attempt (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id TEXT NOT NULL,
    course_key TEXT NOT NULL,
    content_version INTEGER NOT NULL,
    lesson_key TEXT NOT NULL,
    exercise_key TEXT NOT NULL,
    move_uci TEXT NOT NULL CHECK (
        length(move_uci) BETWEEN 4 AND 5
        AND move_uci NOT GLOB '*[^a-h1-8qrbn]*'
    ),
    outcome TEXT NOT NULL CHECK (outcome IN ('correct', 'incorrect')),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    FOREIGN KEY (
        account_id, course_key, content_version, lesson_key, exercise_key
    ) REFERENCES training_course_exercise_progress (
        account_id, course_key, content_version, lesson_key, exercise_key
    ) ON DELETE CASCADE
);

CREATE INDEX training_course_attempt_account
    ON training_course_exercise_attempt (
        account_id, course_key, content_version, lesson_key, exercise_key, id
    );
