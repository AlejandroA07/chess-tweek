package com.chesstweek.composition.web;

import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;

/**
 * The request checks every Puzzle training endpoint applies, in one order: a write checks the
 * same-origin request, then its validated input, then the connected session; a read checks the
 * session. A storage or rule failure becomes {@code 503 training_unavailable}. Every response is
 * {@code no-store}.
 */
final class TrainingRequests {

    private TrainingRequests() {}

    static ResponseEntity<?> read(
            LichessSessionAuthorizer authorizer,
            String sealedSession,
            Function<String, ResponseEntity<?>> action) {
        LichessSessionAuthorization authorization = authorizer.authorize(sealedSession);
        ResponseEntity<TrainingError> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        return run(authorization.accountId(), action);
    }

    static ResponseEntity<?> write(
            HttpServletRequest request,
            LichessSessionAuthorizer authorizer,
            String sealedSession,
            boolean valid,
            Function<String, ResponseEntity<?>> action) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!valid) {
            return error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
        }
        return read(authorizer, sealedSession, action);
    }

    static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    static ResponseEntity<TrainingError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new TrainingError(code));
    }

    private static ResponseEntity<?> run(
            String accountId, Function<String, ResponseEntity<?>> action) {
        try {
            return action.apply(accountId);
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, TrainingErrorCodes.TRAINING_UNAVAILABLE);
        }
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

    public record TrainingError(String error) {}
}
