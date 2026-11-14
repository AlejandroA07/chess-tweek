package com.chesstweek.composition.web;

import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.gamereview.GameAnalysis;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;

/**
 * Starts, reports, and cancels the single running game analysis. Every request needs the
 * sealed Lichess session; requests that change state also need the local same-origin check.
 */
@RestController
public final class GameAnalysisController {

    private static final Pattern GAME_ID = Pattern.compile("[A-Za-z0-9]{8,32}");

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final GameAnalysis gameAnalysis;

    GameAnalysisController(LichessSessionAuthorizer sessionAuthorizer, GameAnalysis gameAnalysis) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.gameAnalysis = gameAnalysis;
    }

    @PostMapping("/api/games/{gameId}/analyses")
    public ResponseEntity<?> start(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("gameId") String gameId,
            @RequestBody(required = false) StartRequest body) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        GameAnalysis.Perspective perspective = body == null ? null : perspective(body.perspective());
        if (!GAME_ID.matcher(gameId).matches() || perspective == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_analysis_request");
        }
        var authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<AnalysisError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        GameAnalysis.StartResult result;
        try {
            result = gameAnalysis.start(authorization.accountId(), gameId, perspective);
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "analysis_unavailable");
        }
        return switch (result.outcome()) {
            case STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore())
                    .body(new JobEnvelope(job(result.job())));
            case GAME_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "game_not_found");
            case NOT_ELIGIBLE -> error(HttpStatus.CONFLICT, "analysis_not_supported");
            case BUSY -> error(HttpStatus.CONFLICT, "analysis_busy");
            case NO_PROFILE -> error(HttpStatus.CONFLICT, "engine_profile_missing");
        };
    }

    @GetMapping("/api/analysis")
    public ResponseEntity<?> current(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        var authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<AnalysisError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new JobEnvelope(
                        gameAnalysis.current(authorization.accountId()).map(GameAnalysisController::job).orElse(null)));
    }

    @PostMapping("/api/analysis/cancel")
    public ResponseEntity<?> cancel(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<AnalysisError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        return gameAnalysis.cancel(authorization.accountId())
                .<ResponseEntity<?>>map(job -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new JobEnvelope(job(job))))
                .orElseGet(() -> error(HttpStatus.CONFLICT, "no_running_analysis"));
    }

    private static GameAnalysis.Perspective perspective(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "my_moves" -> GameAnalysis.Perspective.MY_MOVES;
            case "opponent_moves" -> GameAnalysis.Perspective.OPPONENT_MOVES;
            case "both" -> GameAnalysis.Perspective.BOTH;
            default -> null;
        };
    }

    private static JobResponse job(GameAnalysis.Job job) {
        return new JobResponse(
                job.gameId(),
                lower(job.perspective()),
                lower(job.state()),
                lower(job.pass()),
                job.judged(),
                job.total(),
                job.failure() == null ? null : lower(job.failure()),
                job.startedAt().toString(),
                job.finishedAt() == null ? null : job.finishedAt().toString(),
                job.versionIds());
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static ResponseEntity<AnalysisError> deny(LichessSessionAuthorization authorization) {
        return switch (authorization.type()) {
            case CONNECTED -> null;
            case DISCONNECTED -> {
                var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore());
                if (authorization.clearCookie()) {
                    response.header(
                            HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
                }
                yield response.body(new AnalysisError("not_connected"));
            }
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "session_unavailable");
        };
    }

    private static ResponseEntity<AnalysisError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new AnalysisError(code));
    }

    public record StartRequest(String perspective) {}

    public record JobEnvelope(JobResponse job) {}

    public record JobResponse(
            String gameId,
            String perspective,
            String state,
            String pass,
            int judged,
            int total,
            String failure,
            String startedAt,
            String finishedAt,
            List<Long> versionIds) {}

    public record AnalysisError(String error) {}
}
