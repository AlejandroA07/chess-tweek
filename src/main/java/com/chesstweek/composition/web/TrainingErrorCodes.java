package com.chesstweek.composition.web;

/**
 * The error codes the Puzzle and guided-course endpoints return in {@code {"error": ...}}. The
 * interface reads them from {@code frontend/src/trainingErrorCodes.ts};
 * {@code TrainingErrorCodesTest} keeps both lists aligned.
 */
final class TrainingErrorCodes {

    static final String NOT_CONNECTED = "not_connected";
    static final String SESSION_UNAVAILABLE = "session_unavailable";
    static final String TRAINING_UNAVAILABLE = "training_unavailable";
    static final String INVALID_REQUEST = "invalid_training_request";
    static final String ILLEGAL_MOVE = "illegal_training_move";
    static final String ATTEMPT_NOT_FOUND = "attempt_not_found";
    static final String ATTEMPT_FINISHED = "attempt_finished";
    static final String NO_PUZZLE_AVAILABLE = "no_puzzle_available";
    static final String SESSION_COMPLETE = "session_complete";
    static final String SESSION_NOT_FOUND = "session_not_found";
    static final String LESSON_NOT_FOUND = "lesson_not_found";
    static final String LESSON_INCOMPLETE = "lesson_incomplete";
    static final String EXERCISE_NOT_FOUND = "exercise_not_found";
    static final String EXERCISE_NOT_RESOLVED = "exercise_not_resolved";

    private TrainingErrorCodes() {}
}
