package com.chesstweek.composition.web;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.identity.LichessSessionService;

@RestController
public final class LichessGameRetrievalController {

    private final LichessGameRetrievalFacade facade;

    LichessGameRetrievalController(LichessGameRetrievalFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/api/lichess/games/retrievals")
    public ResponseEntity<?> retrieve(
            HttpServletRequest servletRequest,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestBody RetrievalRequest request) {
        if (!LoopbackRequestPolicy.isSameOrigin(servletRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        var validated = validate(request);
        if (validated == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_retrieval_request");
        }

        var outcome = facade.retrieve(sealedSession, validated.count(), validated.beforeInstant());
        return switch (outcome.status()) {
            case SUCCESS -> ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new RetrievalResponse(
                            validated.count(),
                            outcome.received(),
                            outcome.rejected(),
                            validated.beforeText()));
            case NOT_CONNECTED -> notConnected(outcome.clearCookie());
            case RATE_LIMITED -> rateLimited(outcome.retryAfterSeconds());
            case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "invalid_lichess_response");
            case UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "lichess_unavailable");
            case STORAGE_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "catalog_unavailable");
            case BUSY -> error(HttpStatus.CONFLICT, "retrieval_already_running");
        };
    }

    private static ValidatedRequest validate(RetrievalRequest request) {
        if (request == null || request.count() == null || request.count() < 1 || request.count() > 50) {
            return null;
        }
        if (request.before() == null) {
            return new ValidatedRequest(request.count(), null, null);
        }
        if (request.before().length() != 10) {
            return null;
        }
        try {
            LocalDate before = LocalDate.parse(request.before());
            return new ValidatedRequest(
                    request.count(), before.toString(), before.atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static ResponseEntity<ErrorResponse> notConnected(boolean clearCookie) {
        var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore());
        if (clearCookie) {
            response.header(HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
        }
        return response.body(new ErrorResponse("not_connected"));
    }

    private static ResponseEntity<ErrorResponse> rateLimited(Long retryAfterSeconds) {
        long retryAfter = retryAfterSeconds == null ? 60 : retryAfterSeconds;
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter))
                .body(new ErrorResponse("lichess_rate_limited"));
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String error) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(error));
    }

    public record RetrievalRequest(Integer count, String before) {}

    public record RetrievalResponse(int requested, int received, int rejected, String before) {}

    public record ErrorResponse(String error) {}

    private record ValidatedRequest(int count, String beforeText, Instant beforeInstant) {}
}
