package com.chesstweek.composition.web;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
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
import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.PuzzleTraining;

/**
 * Normal-Puzzle performance training for the connected owner. Every action changes account state,
 * so every endpoint is a same-origin {@code POST}; responses never include an unrevealed solution.
 */
@RestController
@RequestMapping("/api/training/puzzles")
public final class PuzzleTrainingController {

    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern CATEGORY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,120}");
    private static final String THEME = "theme";
    private static final String OPENING_FAMILY = "opening-family";
    private static final String OPENING_VARIATION = "opening-variation";

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final PuzzleTraining training;
    private final PuzzleLibrary library;

    PuzzleTrainingController(
            LichessSessionAuthorizer sessionAuthorizer,
            PuzzleTraining training,
            PuzzleLibrary library) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.training = training;
        this.library = library;
    }

    /** The categories By Category can start from; read-only, so no same-origin requirement. */
    @GetMapping("/categories")
    public ResponseEntity<?> categories(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<TrainingError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            return ok(new CategoriesResponse(library.categories().stream()
                    .map(PuzzleTrainingController::category)
                    .toList()));
        } catch (DataAccessException | IllegalStateException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, TrainingErrorCodes.TRAINING_UNAVAILABLE);
        }
    }

    /** The account's Training List sizes for the Repetition dashboard; read-only. */
    @GetMapping("/repetition")
    public ResponseEntity<?> repetition(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return read(sealedSession, account -> ok(new RepetitionResponse(
                training.listCounts(account).stream()
                        .map(count -> new ListCountResponse(lower(count.list()), count.puzzles()))
                        .toList())));
    }

    /** The account's Mastered Puzzles grouped by Theme and opening category; read-only. */
    @GetMapping("/repetition/mastered")
    public ResponseEntity<?> mastered(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return read(sealedSession, account -> {
            PuzzleTraining.MasteredSummary summary = training.mastered(account);
            return ok(new MasteredResponse(
                    summary.total(),
                    summary.categories().stream().map(PuzzleTrainingController::category).toList()));
        });
    }

    @PostMapping("/repetition/{list}/sessions")
    public ResponseEntity<?> startSession(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("list") String list) {
        PuzzleTraining.TrainingList trainingList = activeList(list);
        return authorized(request, sealedSession, trainingList != null, account ->
                session(training.startSession(account, trainingList)));
    }

    @PostMapping("/repetition/mastered/{kind}/{key}/sessions")
    public ResponseEntity<?> startMasteredSession(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key) {
        PuzzleLibrary.CategoryKind categoryKind = categoryKind(kind);
        boolean valid = categoryKind != null && key != null && CATEGORY_KEY.matcher(key).matches();
        return authorized(request, sealedSession, valid, account -> session(
                training.startMasteredSession(account, new PuzzleLibrary.Category(categoryKind, key))));
    }

    @PostMapping("/sessions/{sessionId}/attempts")
    public ResponseEntity<?> nextInSession(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("sessionId") long sessionId) {
        return authorized(request, sealedSession, sessionId > 0, account -> {
            PuzzleTraining.SessionAttempt next = training.nextInSession(account, sessionId);
            return switch (next.outcome()) {
                case STARTED -> ResponseEntity.status(HttpStatus.CREATED)
                        .cacheControl(CacheControl.noStore())
                        .body(new SessionAttemptResponse(
                                PuzzleAttemptResponses.attempt(next.attempt()),
                                next.position(),
                                next.total()));
                case COMPLETE -> error(HttpStatus.CONFLICT, TrainingErrorCodes.SESSION_COMPLETE);
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.SESSION_NOT_FOUND);
            };
        });
    }

    @PostMapping("/random/attempts")
    public ResponseEntity<?> startRandom(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return authorized(request, sealedSession, true, account ->
                started(training.startRandom(account)));
    }

    @PostMapping("/categories/{kind}/{key}/attempts")
    public ResponseEntity<?> startInCategory(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key) {
        PuzzleLibrary.CategoryKind categoryKind = categoryKind(kind);
        boolean valid = categoryKind != null && key != null && CATEGORY_KEY.matcher(key).matches();
        return authorized(request, sealedSession, valid, account -> started(
                training.startInCategory(account, new PuzzleLibrary.Category(categoryKind, key))));
    }

    @PostMapping("/attempts/{attemptId}/moves")
    public ResponseEntity<?> move(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("attemptId") long attemptId,
            @RequestBody(required = false) MoveRequest body) {
        boolean valid = attemptId > 0 && body != null && body.move() != null
                && UCI.matcher(body.move()).matches();
        return authorized(request, sealedSession, valid, account -> {
            PuzzleTraining.MoveResult result = training.move(account, attemptId, body.move());
            return switch (result.outcome()) {
                case CORRECT, SOLVED, WRONG -> ok(new MoveResponse(
                        lower(result.outcome()),
                        result.opponentReply(),
                        result.wrongMoves(),
                        PuzzleAttemptResponses.completion(result.completion())));
                case ILLEGAL -> error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.ILLEGAL_MOVE);
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.ATTEMPT_NOT_FOUND);
                case FINISHED -> error(HttpStatus.CONFLICT, TrainingErrorCodes.ATTEMPT_FINISHED);
            };
        });
    }

    @PostMapping("/attempts/{attemptId}/hint")
    public ResponseEntity<?> hint(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("attemptId") long attemptId) {
        return authorized(request, sealedSession, attemptId > 0, account -> {
            PuzzleTraining.HintResult result = training.hint(account, attemptId);
            return switch (result.outcome()) {
                case SHOWN -> ok(new HintResponse(result.fromSquare()));
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.ATTEMPT_NOT_FOUND);
                case FINISHED -> error(HttpStatus.CONFLICT, TrainingErrorCodes.ATTEMPT_FINISHED);
            };
        });
    }

    @PostMapping("/attempts/{attemptId}/reveal")
    public ResponseEntity<?> reveal(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("attemptId") long attemptId) {
        return authorized(request, sealedSession, attemptId > 0, account -> {
            PuzzleTraining.RevealResult result = training.reveal(account, attemptId);
            return switch (result.outcome()) {
                case REVEALED -> ok(new RevealResponse(
                        result.remainingMoves(),
                        PuzzleAttemptResponses.completion(result.completion())));
                case NOT_FOUND -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.ATTEMPT_NOT_FOUND);
                case FINISHED -> error(HttpStatus.CONFLICT, TrainingErrorCodes.ATTEMPT_FINISHED);
            };
        });
    }

    /** Authorization, then a read-only action; reads need no same-origin check. */
    private ResponseEntity<?> read(
            String sealedSession, Function<String, ResponseEntity<?>> action) {
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<TrainingError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            return action.apply(authorization.accountId());
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, TrainingErrorCodes.TRAINING_UNAVAILABLE);
        }
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
        ResponseEntity<TrainingError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            return action.apply(authorization.accountId());
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, TrainingErrorCodes.TRAINING_UNAVAILABLE);
        }
    }

    private static ResponseEntity<?> started(Optional<PuzzleTraining.Attempt> attempt) {
        return attempt.<ResponseEntity<?>>map(value -> ResponseEntity.status(HttpStatus.CREATED)
                        .cacheControl(CacheControl.noStore())
                        .body(PuzzleAttemptResponses.attempt(value)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.NO_PUZZLE_AVAILABLE));
    }

    private static ResponseEntity<?> session(Optional<PuzzleTraining.Session> session) {
        return session.<ResponseEntity<?>>map(value -> ResponseEntity.status(HttpStatus.CREATED)
                        .cacheControl(CacheControl.noStore())
                        .body(new SessionResponse(
                                value.sessionId(), lower(value.list()), value.total())))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, TrainingErrorCodes.NO_PUZZLE_AVAILABLE));
    }

    private static CategoryResponse category(PuzzleLibrary.CategoryEntry entry) {
        return new CategoryResponse(
                kind(entry.category().kind()),
                entry.category().key(),
                entry.displayName(),
                entry.groupName(),
                entry.parentKey(),
                entry.puzzles());
    }

    private static PuzzleLibrary.CategoryKind categoryKind(String kind) {
        return switch (kind) {
            case THEME -> PuzzleLibrary.CategoryKind.THEME;
            case OPENING_FAMILY -> PuzzleLibrary.CategoryKind.OPENING_FAMILY;
            case OPENING_VARIATION -> PuzzleLibrary.CategoryKind.OPENING_VARIATION;
            default -> null;
        };
    }

    /** The four lists a repetition Practice Session can cover; Mastered is practiced by category. */
    private static PuzzleTraining.TrainingList activeList(String list) {
        return switch (list) {
            case "hard_to_solve" -> PuzzleTraining.TrainingList.HARD_TO_SOLVE;
            case "wrong_moves" -> PuzzleTraining.TrainingList.WRONG_MOVES;
            case "worth_repeating" -> PuzzleTraining.TrainingList.WORTH_REPEATING;
            case "retiring" -> PuzzleTraining.TrainingList.RETIRING;
            default -> null;
        };
    }

    /** The URL form of a category kind, shared by the category list and the start path. */
    private static String kind(PuzzleLibrary.CategoryKind kind) {
        return switch (kind) {
            case THEME -> THEME;
            case OPENING_FAMILY -> OPENING_FAMILY;
            case OPENING_VARIATION -> OPENING_VARIATION;
        };
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static ResponseEntity<TrainingError> deny(LichessSessionAuthorization authorization) {
        return switch (authorization.type()) {
            case CONNECTED -> null;
            case DISCONNECTED -> {
                var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore());
                if (authorization.clearCookie()) {
                    response.header(
                            HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
                }
                yield response.body(new TrainingError(TrainingErrorCodes.NOT_CONNECTED));
            }
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, TrainingErrorCodes.SESSION_UNAVAILABLE);
        };
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<TrainingError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new TrainingError(code));
    }

    public record CategoryResponse(
            String kind, String key, String name, String group, String parentKey, int puzzles) {}

    public record CategoriesResponse(List<CategoryResponse> categories) {}

    public record MoveRequest(String move) {}

    public record ListCountResponse(String list, int puzzles) {}

    public record RepetitionResponse(List<ListCountResponse> lists) {}

    /** {@code categories[].puzzles} is the account's Mastered count in that category. */
    public record MasteredResponse(int total, List<CategoryResponse> categories) {}

    public record SessionResponse(long sessionId, String list, int total) {}

    public record SessionAttemptResponse(
            PuzzleAttemptResponses.AttemptResponse attempt, int position, int total) {}

    public record MoveResponse(
            String outcome,
            String opponentReply,
            int wrongMoves,
            PuzzleAttemptResponses.CompletionResponse completion) {}

    public record HintResponse(String fromSquare) {}

    public record RevealResponse(
            List<String> remainingMoves, PuzzleAttemptResponses.CompletionResponse completion) {}

    public record TrainingError(String error) {}
}
