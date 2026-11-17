package com.chesstweek.training.internal;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.training.GuidedCourses;
import com.chesstweek.training.internal.content.CourseContent;
import com.chesstweek.training.internal.content.CourseContentLoader;
import com.chesstweek.training.internal.persistence.CourseProgressStore;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.notation.NotationType;

@Service
public class GuidedCourseService implements GuidedCourses {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");

    private final CourseContent content;
    private final CourseProgressStore progressStore;
    private final Clock clock;

    @Autowired
    public GuidedCourseService(CourseContentLoader loader, CourseProgressStore progressStore) {
        this(loader.content(), progressStore, Clock.systemUTC());
    }

    GuidedCourseService(CourseContent content, CourseProgressStore progressStore, Clock clock) {
        this.content = content;
        this.progressStore = progressStore;
        this.clock = clock;
    }

    @Override
    public Course course() {
        return content.course();
    }

    @Override
    public Progress progress(String accountId) {
        requireAccount(accountId);
        return progress(accountId, progressStore.load(
                accountId, course().key(), course().version()));
    }

    @Override
    @Transactional
    public Optional<Progress> startLesson(String accountId, String lessonKey) {
        requireAccount(accountId);
        if (content.lesson(lessonKey).isEmpty()) {
            return Optional.empty();
        }
        progressStore.startLesson(
                accountId,
                course().key(),
                course().version(),
                lessonKey,
                now());
        return Optional.of(progress(accountId));
    }

    @Override
    @Transactional
    public AttemptResult attempt(String accountId, String exerciseKey, String moveUci) {
        requireAccount(accountId);
        CourseContent.ExerciseAnswer answer = content.answer(exerciseKey).orElse(null);
        if (answer == null) {
            return new AttemptResult(AttemptOutcome.NOT_FOUND, null, null, null);
        }
        if (moveUci == null || !UCI.matcher(moveUci).matches() || !legal(answer.fen(), moveUci)) {
            return new AttemptResult(
                    AttemptOutcome.INVALID_MOVE,
                    "That move is not legal in this position.",
                    null,
                    null);
        }
        long timestamp = now();
        progressStore.startLesson(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                timestamp);
        boolean correct = answer.acceptedMoves().contains(moveUci);
        progressStore.recordAttempt(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                exerciseKey,
                moveUci,
                correct,
                timestamp);
        return new AttemptResult(
                correct ? AttemptOutcome.CORRECT : AttemptOutcome.INCORRECT,
                correct ? answer.correctFeedback() : answer.incorrectFeedback(),
                correct ? moveUci : null,
                correct ? answer.explanation() : null);
    }

    @Override
    @Transactional
    public HintResult hint(String accountId, String exerciseKey, int level) {
        requireAccount(accountId);
        if (level < 1 || level > 2) {
            return new HintResult(HintOutcome.INVALID_LEVEL, 0, null);
        }
        CourseContent.ExerciseAnswer answer = content.answer(exerciseKey).orElse(null);
        if (answer == null) {
            return new HintResult(HintOutcome.NOT_FOUND, 0, null);
        }
        long timestamp = now();
        progressStore.startLesson(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                timestamp);
        progressStore.recordHint(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                exerciseKey,
                level,
                timestamp);
        return new HintResult(HintOutcome.SHOWN, level, answer.hints().get(level - 1));
    }

    @Override
    @Transactional
    public RevealResult reveal(String accountId, String exerciseKey) {
        requireAccount(accountId);
        CourseContent.ExerciseAnswer answer = content.answer(exerciseKey).orElse(null);
        if (answer == null) {
            return new RevealResult(RevealOutcome.NOT_FOUND, null, null);
        }
        long timestamp = now();
        progressStore.startLesson(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                timestamp);
        progressStore.reveal(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                exerciseKey,
                timestamp);
        return new RevealResult(
                RevealOutcome.REVEALED,
                answer.acceptedMoves().getFirst(),
                answer.explanation());
    }

    @Override
    @Transactional
    public CompletionResult completeLesson(String accountId, String lessonKey) {
        requireAccount(accountId);
        Lesson lesson = content.lesson(lessonKey).orElse(null);
        if (lesson == null) {
            return new CompletionResult(CompletionOutcome.NOT_FOUND, progress(accountId));
        }
        CourseProgressStore.Snapshot snapshot = progressStore.load(
                accountId, course().key(), course().version());
        boolean complete = lesson.exercises().stream()
                .map(Exercise::key)
                .map(snapshot.exercises()::get)
                .allMatch(row -> row != null
                        && (row.state() == CourseProgressStore.State.SOLVED
                                || row.state() == CourseProgressStore.State.REVEALED));
        if (!complete) {
            return new CompletionResult(
                    CompletionOutcome.INCOMPLETE, progress(accountId, snapshot));
        }
        progressStore.completeLesson(
                accountId,
                course().key(),
                course().version(),
                lessonKey,
                now());
        return new CompletionResult(CompletionOutcome.COMPLETED, progress(accountId));
    }

    private Progress progress(String accountId, CourseProgressStore.Snapshot snapshot) {
        List<Lesson> availableLessons = course().modules().stream()
                .filter(module -> module.status() == ModuleStatus.AVAILABLE)
                .flatMap(module -> module.lessons().stream())
                .toList();
        Map<String, LessonProgress> lessons = new LinkedHashMap<>();
        int completed = 0;
        String recommended = null;
        for (Lesson lesson : availableLessons) {
            CourseProgressStore.LessonRow lessonRow = snapshot.lessons().get(lesson.key());
            LessonState lessonState;
            if (lessonRow == null) {
                lessonState = LessonState.NOT_STARTED;
            } else if (lessonRow.completed()) {
                lessonState = LessonState.COMPLETED;
                completed++;
            } else {
                lessonState = LessonState.IN_PROGRESS;
            }
            if (recommended == null && lessonState != LessonState.COMPLETED) {
                recommended = lesson.key();
            }
            Map<String, ExerciseProgress> exercises = new LinkedHashMap<>();
            for (Exercise exercise : lesson.exercises()) {
                CourseProgressStore.ExerciseRow row = snapshot.exercises().get(exercise.key());
                exercises.put(
                        exercise.key(),
                        row == null
                                ? new ExerciseProgress(ExerciseState.NOT_STARTED, 0, 0)
                                : new ExerciseProgress(
                                        exerciseState(row.state()),
                                        row.attempts(),
                                        row.hintLevel()));
            }
            lessons.put(lesson.key(), new LessonProgress(lessonState, Map.copyOf(exercises)));
        }
        if (recommended == null && !availableLessons.isEmpty()) {
            recommended = availableLessons.getFirst().key();
        }
        return new Progress(
                course().key(),
                course().version(),
                completed,
                availableLessons.size(),
                recommended,
                Map.copyOf(lessons));
    }

    private static ExerciseState exerciseState(CourseProgressStore.State state) {
        return switch (state) {
            case IN_PROGRESS -> ExerciseState.IN_PROGRESS;
            case SOLVED -> ExerciseState.SOLVED;
            case REVEALED -> ExerciseState.REVEALED;
        };
    }

    private static boolean legal(String fen, String moveUci) {
        try {
            ChessGame game = new ChessGame(fen);
            return game.getLegalMoves().contains(game.getMove(NotationType.UCI, moveUci));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void requireAccount(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Account ID is invalid");
        }
    }

    private long now() {
        return clock.instant().toEpochMilli();
    }
}
