package com.chesstweek.composition.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Course material is original, non-account data and remains readable when logged out. */
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
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<CourseError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            return ok(progress(courses.progress(authorization.accountId())));
        } catch (DataAccessException | IllegalArgumentException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "training_unavailable");
        }
    }

    @PostMapping("/lessons/{lessonKey}/start")
    public ResponseEntity<?> startLesson(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("lessonKey") String lessonKey) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!validKey(lessonKey)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_training_request");
        }
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<CourseError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            return courses.startLesson(authorization.accountId(), lessonKey)
                    .<ResponseEntity<?>>map(value -> ok(progress(value)))
                    .orElseGet(() -> error(HttpStatus.NOT_FOUND, "lesson_not_found"));
        } catch (DataAccessException | IllegalArgumentException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "training_unavailable");
        }
    }

    @PostMapping("/exercises/{exerciseKey}/attempts")
    public ResponseEntity<?> attempt(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("exerciseKey") String exerciseKey,
            @RequestBody(required = false) AttemptRequest body) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!validKey(exerciseKey)
                || body == null
                || body.move() == null
                || !UCI.matcher(body.move()).matches()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_training_request");
        }
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<CourseError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            GuidedCourses.AttemptResult result = courses.attempt(
                    authorization.accountId(), exerciseKey, body.move());
            return switch (result.outcome()) {
                case CORRECT, INCORRECT -> ok(new AttemptResponse(
                        lower(result.outcome()),
                        result.feedback(),
                        result.solutionMove(),
                        result.explanation()));
                case INVALID_MOVE -> error(HttpStatus.BAD_REQUEST, "illegal_training_move");
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "exercise_not_found");
            };
        } catch (DataAccessException | IllegalArgumentException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "training_unavailable");
        }
    }

    @PostMapping("/exercises/{exerciseKey}/hint")
    public ResponseEntity<?> hint(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("exerciseKey") String exerciseKey,
            @RequestBody(required = false) HintRequest body) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!validKey(exerciseKey) || body == null || body.level() < 1 || body.level() > 2) {
            return error(HttpStatus.BAD_REQUEST, "invalid_training_request");
        }
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<CourseError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            GuidedCourses.HintResult result = courses.hint(
                    authorization.accountId(), exerciseKey, body.level());
            return switch (result.outcome()) {
                case SHOWN -> ok(new HintResponse(result.level(), result.text()));
                case INVALID_LEVEL -> error(HttpStatus.BAD_REQUEST, "invalid_training_request");
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "exercise_not_found");
            };
        } catch (DataAccessException | IllegalArgumentException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "training_unavailable");
        }
    }

    @PostMapping("/exercises/{exerciseKey}/reveal")
    public ResponseEntity<?> reveal(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("exerciseKey") String exerciseKey) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!validKey(exerciseKey)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_training_request");
        }
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<CourseError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            GuidedCourses.RevealResult result = courses.reveal(
                    authorization.accountId(), exerciseKey);
            return switch (result.outcome()) {
                case REVEALED -> ok(new RevealResponse(
                        result.solutionMove(), result.explanation()));
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "exercise_not_found");
            };
        } catch (DataAccessException | IllegalArgumentException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "training_unavailable");
        }
    }

    @PostMapping("/lessons/{lessonKey}/complete")
    public ResponseEntity<?> completeLesson(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("lessonKey") String lessonKey) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!validKey(lessonKey)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_training_request");
        }
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<CourseError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            GuidedCourses.CompletionResult result = courses.completeLesson(
                    authorization.accountId(), lessonKey);
            return switch (result.outcome()) {
                case COMPLETED -> ok(progress(result.progress()));
                case INCOMPLETE -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .cacheControl(CacheControl.noStore())
                        .body(new CompletionError(
                                "lesson_incomplete", progress(result.progress())));
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "lesson_not_found");
            };
        } catch (DataAccessException | IllegalArgumentException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "training_unavailable");
        }
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
                yield response.body(new CourseError("not_connected"));
            }
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "session_unavailable");
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
            String key, String fen, String orientation, String prompt) {}

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

    public record AttemptResponse(
            String outcome, String feedback, String solutionMove, String explanation) {}

    public record HintRequest(int level) {}

    public record HintResponse(int level, String text) {}

    public record RevealResponse(String solutionMove, String explanation) {}

    public record CompletionError(String error, ProgressResponse progress) {}

    public record CourseError(String error) {}
}
