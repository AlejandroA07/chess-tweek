package com.chesstweek.composition.web;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;
import com.chesstweek.training.PatternLadders;

/**
 * Pattern ladders for the connected owner (Stage 9). Reads need the session; starting the next
 * Puzzle and restarting a package change account state, so they are same-origin {@code POST}s.
 * The attempt's moves, hint, and Show solution use the normal Puzzle attempt endpoints.
 */
@RestController
@RequestMapping("/api/training/puzzles/ladders")
public final class PatternLadderController {

    private static final Pattern PATTERN_KEY = Pattern.compile("[A-Za-z0-9_-]{1,120}");

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final PatternLadders ladders;

    PatternLadderController(LichessSessionAuthorizer sessionAuthorizer, PatternLadders ladders) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.ladders = ladders;
    }

    @GetMapping
    public ResponseEntity<?> patterns(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return TrainingRequests.read(sessionAuthorizer, sealedSession, account ->
                TrainingRequests.ok(new PatternsResponse(ladders.patterns().stream()
                        .map(PatternLadderController::pattern)
                        .toList())));
    }

    @GetMapping("/{pattern}")
    public ResponseEntity<?> ladder(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("pattern") String pattern) {
        if (!validPattern(pattern)) {
            return TrainingRequests.error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
        }
        return TrainingRequests.read(sessionAuthorizer, sealedSession, account ->
                ladders.ladder(account, pattern)
                        .<ResponseEntity<?>>map(ladder -> TrainingRequests.ok(new LadderResponse(
                                pattern(ladder.pattern()),
                                ladder.packages().stream()
                                        .map(PatternLadderController::ratingPackage)
                                        .toList())))
                        .orElseGet(() -> TrainingRequests.error(
                                HttpStatus.NOT_FOUND, TrainingErrorCodes.PATTERN_NOT_FOUND)));
    }

    @PostMapping("/{pattern}/{package}/attempts")
    public ResponseEntity<?> next(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("pattern") String pattern,
            @PathVariable("package") String packageKey) {
        PatternLadders.RatingPackage ratingPackage =
                PatternLadders.RatingPackage.fromKey(packageKey).orElse(null);
        return write(request, sealedSession, validPattern(pattern) && ratingPackage != null, account -> {
            PatternLadders.Next next = ladders.next(account, pattern, ratingPackage);
            return switch (next.outcome()) {
                case STARTED -> ResponseEntity.status(HttpStatus.CREATED)
                        .cacheControl(CacheControl.noStore())
                        .body(new NextResponse(
                                PuzzleAttemptResponses.attempt(next.attempt()),
                                progress(next.progress())));
                case COMPLETE -> TrainingRequests.error(
                        HttpStatus.CONFLICT, TrainingErrorCodes.LADDER_COMPLETE);
                case NOT_FOUND -> TrainingRequests.error(
                        HttpStatus.NOT_FOUND, TrainingErrorCodes.PATTERN_NOT_FOUND);
            };
        });
    }

    @PostMapping("/{pattern}/{package}/restart")
    public ResponseEntity<?> restart(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("pattern") String pattern,
            @PathVariable("package") String packageKey) {
        PatternLadders.RatingPackage ratingPackage =
                PatternLadders.RatingPackage.fromKey(packageKey).orElse(null);
        return write(request, sealedSession, validPattern(pattern) && ratingPackage != null, account ->
                ladders.restart(account, pattern, ratingPackage)
                        ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                        : TrainingRequests.error(
                                HttpStatus.NOT_FOUND, TrainingErrorCodes.PATTERN_NOT_FOUND));
    }

    @PostMapping("/{pattern}/{package}/jump")
    public ResponseEntity<?> jump(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("pattern") String pattern,
            @PathVariable("package") String packageKey,
            @RequestBody(required = false) JumpRequest body) {
        PatternLadders.RatingPackage ratingPackage =
                PatternLadders.RatingPackage.fromKey(packageKey).orElse(null);
        boolean valid = validPattern(pattern) && ratingPackage != null && body != null
                && body.position() != null && body.position() >= 1 && body.position() <= 100_000_000;
        return write(request, sealedSession, valid, account ->
                switch (ladders.jump(account, pattern, ratingPackage, body.position())) {
                    case MOVED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                    case OUT_OF_RANGE -> TrainingRequests.error(
                            HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
                    case NOT_FOUND -> TrainingRequests.error(
                            HttpStatus.NOT_FOUND, TrainingErrorCodes.PATTERN_NOT_FOUND);
                });
    }

    @PostMapping("/{pattern}/fast")
    public ResponseEntity<?> startFast(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("pattern") String pattern) {
        return write(request, sealedSession, validPattern(pattern), account ->
                ladders.startFast(account, pattern)
                        .<ResponseEntity<?>>map(fast -> ResponseEntity.status(HttpStatus.CREATED)
                                .cacheControl(CacheControl.noStore())
                                .body(new FastLadderResponse(fast.fastLadderId(), fast.total())))
                        .orElseGet(() -> TrainingRequests.error(
                                HttpStatus.NOT_FOUND, TrainingErrorCodes.NO_PUZZLE_AVAILABLE)));
    }

    @PostMapping("/fast/{fastLadderId}/attempts")
    public ResponseEntity<?> nextFast(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("fastLadderId") long fastLadderId) {
        return write(request, sealedSession, fastLadderId > 0, account -> {
            PatternLadders.FastNext next = ladders.nextFast(account, fastLadderId);
            return switch (next.outcome()) {
                case STARTED -> ResponseEntity.status(HttpStatus.CREATED)
                        .cacheControl(CacheControl.noStore())
                        .body(new FastAttemptResponse(
                                PuzzleAttemptResponses.attempt(next.attempt()),
                                next.position(),
                                next.total(),
                                step(next.step())));
                case COMPLETE -> TrainingRequests.error(
                        HttpStatus.CONFLICT, TrainingErrorCodes.LADDER_COMPLETE);
                case NOT_FOUND -> TrainingRequests.error(
                        HttpStatus.NOT_FOUND, TrainingErrorCodes.SESSION_NOT_FOUND);
            };
        });
    }

    private ResponseEntity<?> write(
            HttpServletRequest request,
            String sealedSession,
            boolean valid,
            Function<String, ResponseEntity<?>> action) {
        return TrainingRequests.write(request, sessionAuthorizer, sealedSession, valid, action);
    }

    private static boolean validPattern(String pattern) {
        return pattern != null && PATTERN_KEY.matcher(pattern).matches();
    }

    private static PatternResponse pattern(PatternLadders.Pattern pattern) {
        return new PatternResponse(
                pattern.key(), pattern.displayName(), pattern.groupName(), pattern.puzzles());
    }

    private static PackageResponse ratingPackage(PatternLadders.PackageEntry entry) {
        PatternLadders.RatingPackage value = entry.ratingPackage();
        return new PackageResponse(
                value.key(), value.low(), value.high(), entry.puzzles(), progress(entry.progress()));
    }

    private static StepResponse step(PatternLadders.FastStep step) {
        if (step.combinedWith() != null) {
            return new StepResponse(null, null, pattern(step.combinedWith()));
        }
        return new StepResponse(step.range().low(), step.range().high(), null);
    }

    private static ProgressResponse progress(PatternLadders.Progress progress) {
        return progress == null ? null
                : new ProgressResponse(progress.presented(), progress.lastRating());
    }

    public record PatternResponse(String key, String name, String group, int puzzles) {}

    public record PatternsResponse(List<PatternResponse> patterns) {}

    /** {@code high} is exclusive; {@code progress} is null until the package is started. */
    public record PackageResponse(
            String key, int low, int high, int puzzles, ProgressResponse progress) {}

    public record ProgressResponse(int presented, int lastRating) {}

    public record LadderResponse(PatternResponse pattern, List<PackageResponse> packages) {}

    public record JumpRequest(Integer position) {}

    public record FastLadderResponse(long fastLadderId, int total) {}

    /** A rating step has {@code ratingLow} and exclusive {@code ratingHigh}; a combination step names the other pattern. */
    public record StepResponse(Integer ratingLow, Integer ratingHigh, PatternResponse combinedWith) {}

    public record FastAttemptResponse(
            PuzzleAttemptResponses.AttemptResponse attempt, int position, int total, StepResponse step) {}

    public record NextResponse(
            PuzzleAttemptResponses.AttemptResponse attempt, ProgressResponse progress) {}
}
