package com.chesstweek.composition.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;
import com.chesstweek.training.GuidedCourses;
import com.chesstweek.training.PuzzleTraining;

@RestController
@RequestMapping("/api/training/courses/grandmaster-thinking-method")
public final class GuidedCourseController {

    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final GuidedCourses courses;

    GuidedCourseController(
            LichessSessionAuthorizer sessionAuthorizer, GuidedCourses courses) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.courses = courses;
    }

    /** Authored course material is non-account data and remains readable when logged out. */
    @GetMapping
    public ResponseEntity<CourseResponse> course() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(course(courses.course()));
    }

    @GetMapping("/progress")
    public ResponseEntity<?> progress(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return read(sealedSession, account -> ok(progress(courses.progress(account))));
    }

    @PostMapping("/lessons/{lessonKey}/start")
    public ResponseEntity<?> startLesson(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("lessonKey") String lessonKey) {
        return authorized(request, sealedSession, validKey(lessonKey), account ->
                courses.startLesson(account, lessonKey)
                        .<ResponseEntity<?>>map(value -> ok(progress(value)))
                        .orElseGet(() -> error(
                                HttpStatus.NOT_FOUND, TrainingErrorCodes.LESSON_NOT_FOUND)));
    }

    /** Starts a full Puzzle attempt on the Exercise's Puzzle; Solve Time starts now. */
    @PostMapping("/exercises/{exerciseKey}/attempts")
    public ResponseEntity<?> startExercise(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("exerciseKey") String exerciseKey) {
        return authorized(request, sealedSession, validKey(exerciseKey), account ->
                courses.startExercise(account, exerciseKey)
                        .<ResponseEntity<?>>map(attempt -> ResponseEntity.status(HttpStatus.CREATED)
                                .cacheControl(CacheControl.noStore())
                                .body(PuzzleAttemptResponses.attempt(attempt)))
                        .orElseGet(() -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.EXERCISE_NOT_FOUND)));
    }

    @PostMapping("/exercises/{exerciseKey}/attempts/{attemptId}/moves")
    public ResponseEntity<?> move(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("exerciseKey") String exerciseKey,
            @PathVariable("attemptId") long attemptId,
            @RequestBody(required = false) AttemptRequest body) {
        boolean valid = validKey(exerciseKey) && attemptId > 0 && body != null
                && body.move() != null && UCI.matcher(body.move()).matches();
        return authorized(request, sealedSession, valid, account -> {
            GuidedCourses.ExerciseMoveResult result =
                    courses.exerciseMove(account, exerciseKey, attemptId, body.move());
            PuzzleTraining.MoveResult move = result.move();
            return switch (move.outcome()) {
                case CORRECT, SOLVED, WRONG -> ok(new ExerciseMoveResponse(
                        lower(move.outcome()),
                        move.opponentReply(),
                        move.wrongMoves(),
                        result.feedback(),
                        result.explanation(),
                        PuzzleAttemptResponses.completion(move.completion())));
                case ILLEGAL -> error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.ILLEGAL_MOVE);
                case NOT_FOUND -> attemptNotFound();
                case FINISHED -> attemptFinished();
            };
        });
    }

    @PostMapping("/exercises/{exerciseKey}/attempts/{attemptId}/hint")
    public ResponseEntity<?> hint(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("exerciseKey") String exerciseKey,
            @PathVariable("attemptId") long attemptId,
            @RequestBody(required = false) HintRequest body) {
        boolean valid = validKey(exerciseKey) && attemptId > 0 && body != null
                && body.level() >= 1 && body.level() <= 2;
        return authorized(request, sealedSession, valid, account -> {
            GuidedCourses.HintResult result =
                    courses.exerciseHint(account, exerciseKey, attemptId, body.level());
            return switch (result.outcome()) {
                case SHOWN -> ok(new HintResponse(result.level(), result.text()));
                case INVALID_LEVEL -> error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
                case NOT_FOUND -> attemptNotFound();
                case FINISHED -> attemptFinished();
            };
        });
    }

    @PostMapping("/exercises/{exerciseKey}/attempts/{attemptId}/reveal")
    public ResponseEntity<?> reveal(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("exerciseKey") String exerciseKey,
            @PathVariable("attemptId") long attemptId) {
        boolean valid = validKey(exerciseKey) && attemptId > 0;
        return authorized(request, sealedSession, valid, account -> {
            GuidedCourses.ExerciseRevealResult result =
                    courses.exerciseReveal(account, exerciseKey, attemptId);
            PuzzleTraining.RevealResult reveal = result.reveal();
            return switch (reveal.outcome()) {
                case REVEALED -> ok(new ExerciseRevealResponse(
                        reveal.remainingMoves(),
                        result.explanation(),
                        PuzzleAttemptResponses.completion(reveal.completion())));
                case NOT_FOUND -> attemptNotFound();
                case FINISHED -> attemptFinished();
            };
        });
    }

    /** The full answer of an Exercise the owner already solved or revealed; read-only. */
    @GetMapping("/exercises/{exerciseKey}/answer")
    public ResponseEntity<?> answer(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("exerciseKey") String exerciseKey) {
        if (!validKey(exerciseKey)) {
            return error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
        }
        return read(sealedSession, account -> {
            GuidedCourses.ReviewResult result = courses.review(account, exerciseKey);
            return switch (result.outcome()) {
                case REVIEWED -> ok(new AnswerResponse(result.solutionLine(), result.explanation()));
                case NOT_RESOLVED -> error(HttpStatus.CONFLICT, TrainingErrorCodes.EXERCISE_NOT_RESOLVED);
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.EXERCISE_NOT_FOUND);
            };
        });
    }

    /** Authorization, then a read-only action; reads need no same-origin check. */
    private ResponseEntity<?> read(
            String sealedSession, Function<String, ResponseEntity<?>> action) {
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<CourseError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        return unavailableOnFailure(() -> action.apply(authorization.accountId()));
    }

    /** Same-origin check, then input validation, then authorization, then the action. */
    private ResponseEntity<?> authorized(
            HttpServletRequest request,
            String sealedSession,
            boolean valid,
            Function<String, ResponseEntity<?>> action) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!valid) {
            return error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
        }
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<CourseError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        return unavailableOnFailure(() -> action.apply(authorization.accountId()));
    }

    /** A storage or content failure becomes the safe {@code training_unavailable} error. */
    private static ResponseEntity<?> unavailableOnFailure(Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, TrainingErrorCodes.TRAINING_UNAVAILABLE);
        }
    }

    /** The attempt is not this account's attempt on this Exercise. */
    private static ResponseEntity<CourseError> attemptNotFound() {
        return error(HttpStatus.NOT_FOUND, TrainingErrorCodes.ATTEMPT_NOT_FOUND);
    }

    /** The attempt already ended, or a newer attempt replaced it. */
    private static ResponseEntity<CourseError> attemptFinished() {
        return error(HttpStatus.CONFLICT, TrainingErrorCodes.ATTEMPT_FINISHED);
    }

    @PostMapping("/lessons/{lessonKey}/complete")
    public ResponseEntity<?> completeLesson(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("lessonKey") String lessonKey) {
        return authorized(request, sealedSession, validKey(lessonKey), account -> {
            GuidedCourses.CompletionResult result = courses.completeLesson(account, lessonKey);
            return switch (result.outcome()) {
                case COMPLETED -> ok(progress(result.progress()));
                case INCOMPLETE -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .cacheControl(CacheControl.noStore())
                        .body(new CompletionError(
                                TrainingErrorCodes.LESSON_INCOMPLETE, progress(result.progress())));
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.LESSON_NOT_FOUND);
            };
        });
    }

    private static CourseResponse course(GuidedCourses.Course course) {
        return new CourseResponse(
                course.key(),
                course.version(),
                course.title(),
                course.subtitle(),
                course.attribution(),
                course.modules().stream()
                        .map(module -> new ModuleResponse(
                                module.key(),
                                module.title(),
                                module.description(),
                                lower(module.status()),
                                module.lessons().stream()
                                        .map(lesson -> new LessonResponse(
                                                lesson.key(),
                                                lesson.title(),
                                                lesson.summary(),
                                                lesson.estimatedMinutes(),
                                                lesson.explanation(),
                                                lesson.checklist(),
                                                lesson.exercises().stream()
                                                        .map(exercise -> new ExerciseResponse(
                                                                exercise.key(),
                                                                exercise.fen(),
                                                                lower(exercise.orientation()),
                                                                lower(exercise.difficultyTier()),
                                                                exercise.sourceRating(),
                                                                exercise.prompt()))
                                                        .toList()))
                                        .toList()))
                        .toList());
    }

    private static ProgressResponse progress(GuidedCourses.Progress progress) {
        Map<String, LessonProgressResponse> lessons = new LinkedHashMap<>();
        progress.lessons().forEach((lessonKey, lesson) -> {
            Map<String, ExerciseProgressResponse> exercises = new LinkedHashMap<>();
            lesson.exercises().forEach((exerciseKey, exercise) -> exercises.put(
                    exerciseKey,
                    new ExerciseProgressResponse(
                            lower(exercise.state()), exercise.attempts(), exercise.hintLevel())));
            lessons.put(
                    lessonKey,
                    new LessonProgressResponse(lower(lesson.state()), exercises));
        });
        return new ProgressResponse(
                progress.courseKey(),
                progress.contentVersion(),
                progress.completedLessons(),
                progress.totalLessons(),
                progress.recommendedLessonKey(),
                lessons);
    }

    private static boolean validKey(String key) {
        return key != null && key.length() <= 64 && KEY.matcher(key).matches();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static ResponseEntity<CourseError> deny(LichessSessionAuthorization authorization) {
        return switch (authorization.type()) {
            case CONNECTED -> null;
            case DISCONNECTED -> {
                var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore());
                if (authorization.clearCookie()) {
                    response.header(
                            HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
                }
                yield response.body(new CourseError(TrainingErrorCodes.NOT_CONNECTED));
            }
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, TrainingErrorCodes.SESSION_UNAVAILABLE);
        };
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<CourseError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new CourseError(code));
    }

    public record CourseResponse(
            String key,
            int version,
            String title,
            String subtitle,
            String attribution,
            List<ModuleResponse> modules) {}

    public record ModuleResponse(
            String key,
            String title,
            String description,
            String status,
            List<LessonResponse> lessons) {}

    public record LessonResponse(
            String key,
            String title,
            String summary,
            int estimatedMinutes,
            List<String> explanation,
            List<String> checklist,
            List<ExerciseResponse> exercises) {}

    public record ExerciseResponse(
            String key,
            String fen,
            String orientation,
            String difficultyTier,
            int sourceRating,
            String prompt) {}

    public record ProgressResponse(
            String courseKey,
            int contentVersion,
            int completedLessons,
            int totalLessons,
            String recommendedLessonKey,
            Map<String, LessonProgressResponse> lessons) {}

    public record LessonProgressResponse(
            String state, Map<String, ExerciseProgressResponse> exercises) {}

    public record ExerciseProgressResponse(String state, int attempts, int hintLevel) {}

    public record AttemptRequest(String move) {}

    public record ExerciseMoveResponse(
            String outcome,
            String opponentReply,
            int wrongMoves,
            String feedback,
            String explanation,
            PuzzleAttemptResponses.CompletionResponse completion) {}

    public record HintRequest(int level) {}

    public record HintResponse(int level, String text) {}

    public record ExerciseRevealResponse(
            List<String> remainingMoves,
            String explanation,
            PuzzleAttemptResponses.CompletionResponse completion) {}

    public record AnswerResponse(List<String> solutionLine, String explanation) {}

    public record CompletionError(String error, ProgressResponse progress) {}

    public record CourseError(String error) {}
}
