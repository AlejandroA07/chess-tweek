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
import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.internal.content.CourseContent;
import com.chesstweek.training.internal.content.CourseContentLoader;
import com.chesstweek.training.internal.persistence.CourseProgressStore;


@Service
public class GuidedCourseService implements GuidedCourses {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final CourseContent content;
    private final CourseProgressStore progressStore;
    private final PuzzleTraining puzzles;
    private final Clock clock;

    @Autowired
    public GuidedCourseService(
            CourseContentLoader loader, CourseProgressStore progressStore, PuzzleTraining puzzles) {
        this(loader.content(), progressStore, puzzles, Clock.systemUTC());
    }

    GuidedCourseService(
            CourseContent content,
            CourseProgressStore progressStore,
            PuzzleTraining puzzles,
            Clock clock) {
        this.content = content;
        this.progressStore = progressStore;
        this.puzzles = puzzles;
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
    public Optional<PuzzleTraining.Attempt> startExercise(String accountId, String exerciseKey) {
        requireAccount(accountId);
        CourseContent.ExerciseAnswer answer = content.answer(exerciseKey).orElse(null);
        if (answer == null) {
            return Optional.empty();
        }
        startLesson(accountId, answer);
        return puzzles.startForMethod(accountId, answer.source().id());
    }

    @Override
    @Transactional
    public ExerciseMoveResult exerciseMove(
            String accountId, String exerciseKey, long attemptId, String moveUci) {
        requireAccount(accountId);
        CourseContent.ExerciseAnswer answer = bound(accountId, exerciseKey, attemptId);
        if (answer == null) {
            return new ExerciseMoveResult(new PuzzleTraining.MoveResult(
                    PuzzleTraining.MoveOutcome.NOT_FOUND, null, 0, null), null, null);
        }
        PuzzleTraining.MoveResult result = puzzles.move(accountId, attemptId, moveUci);
        return switch (result.outcome()) {
            case WRONG -> {
                record(accountId, answer, exerciseKey, moveUci, false);
                yield new ExerciseMoveResult(result, answer.incorrectFeedback(), null);
            }
            case SOLVED -> {
                record(accountId, answer, exerciseKey, moveUci, true);
                yield new ExerciseMoveResult(
                        result, answer.correctFeedback(), answer.explanation());
            }
            default -> new ExerciseMoveResult(result, null, null);
        };
    }

    @Override
    @Transactional
    public HintResult exerciseHint(
            String accountId, String exerciseKey, long attemptId, int level) {
        requireAccount(accountId);
        if (level < 1 || level > 2) {
            return new HintResult(HintOutcome.INVALID_LEVEL, 0, null);
        }
        CourseContent.ExerciseAnswer answer = bound(accountId, exerciseKey, attemptId);
        if (answer == null) {
            return new HintResult(HintOutcome.NOT_FOUND, 0, null);
        }
        // A written hint is a hint: it counts against the Puzzle's Training List first.
        if (puzzles.hint(accountId, attemptId).outcome() != PuzzleTraining.HintOutcome.SHOWN) {
            return new HintResult(HintOutcome.FINISHED, 0, null);
        }
        progressStore.recordHint(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                exerciseKey,
                level,
                now());
        return new HintResult(HintOutcome.SHOWN, level, answer.hints().get(level - 1));
    }

    @Override
    @Transactional
    public ExerciseRevealResult exerciseReveal(
            String accountId, String exerciseKey, long attemptId) {
        requireAccount(accountId);
        CourseContent.ExerciseAnswer answer = bound(accountId, exerciseKey, attemptId);
        if (answer == null) {
            return new ExerciseRevealResult(new PuzzleTraining.RevealResult(
                    PuzzleTraining.RevealOutcome.NOT_FOUND, List.of(), null), null);
        }
        PuzzleTraining.RevealResult result = puzzles.reveal(accountId, attemptId);
        if (result.outcome() != PuzzleTraining.RevealOutcome.REVEALED) {
            return new ExerciseRevealResult(result, null);
        }
        progressStore.reveal(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                exerciseKey,
                now());
        return new ExerciseRevealResult(result, answer.explanation());
    }

    @Override
    public ReviewResult review(String accountId, String exerciseKey) {
        requireAccount(accountId);
        CourseContent.ExerciseAnswer answer = content.answer(exerciseKey).orElse(null);
        if (answer == null) {
            return new ReviewResult(ReviewOutcome.NOT_FOUND, List.of(), null);
        }
        CourseProgressStore.ExerciseRow row = progressStore
                .load(accountId, course().key(), course().version())
                .exercises().get(exerciseKey);
        boolean resolved = row != null
                && (row.state() == CourseProgressStore.State.SOLVED
                        || row.state() == CourseProgressStore.State.REVEALED);
        if (!resolved) {
            return new ReviewResult(ReviewOutcome.NOT_RESOLVED, List.of(), null);
        }
        return new ReviewResult(
                ReviewOutcome.REVIEWED, answer.solutionLine(), answer.explanation());
    }

    /**
     * The Exercise's answer when {@code attemptId} is one of the account's guided-method attempts
     * on that Exercise's Puzzle; null for an unknown Exercise or any other attempt.
     */
    private CourseContent.ExerciseAnswer bound(
            String accountId, String exerciseKey, long attemptId) {
        CourseContent.ExerciseAnswer answer = content.answer(exerciseKey).orElse(null);
        if (answer == null) {
            return null;
        }
        long puzzleId = answer.source().id();
        return puzzles.methodAttemptPuzzle(accountId, attemptId)
                .filter(id -> id == puzzleId)
                .map(id -> answer)
                .orElse(null);
    }

    private void startLesson(String accountId, CourseContent.ExerciseAnswer answer) {
        progressStore.startLesson(
                accountId, course().key(), course().version(), answer.lessonKey(), now());
    }

    private void record(
            String accountId,
            CourseContent.ExerciseAnswer answer,
            String exerciseKey,
            String moveUci,
            boolean solved) {
        long timestamp = now();
        progressStore.startLesson(
                accountId, course().key(), course().version(), answer.lessonKey(), timestamp);
        progressStore.recordAttempt(
                accountId,
                course().key(),
                course().version(),
                answer.lessonKey(),
                exerciseKey,
                moveUci,
                solved,
                timestamp);
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

    private static void requireAccount(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Account ID is invalid");
        }
    }

    private long now() {
        return clock.instant().toEpochMilli();
    }
}
