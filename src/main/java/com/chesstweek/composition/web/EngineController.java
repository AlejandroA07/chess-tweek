package com.chesstweek.composition.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.gamereview.EngineHealth;
import com.chesstweek.gamereview.EngineStatus;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;

@RestController
public final class EngineController {

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final EngineHealth engineHealth;

    EngineController(LichessSessionAuthorizer sessionAuthorizer, EngineHealth engineHealth) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.engineHealth = engineHealth;
    }

    @GetMapping("/api/engine")
    public ResponseEntity<?> status(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        ResponseEntity<EngineError> denied = deny(sessionAuthorizer.authorize(sealedSession));
        return denied != null ? denied : ok(engineHealth.status());
    }

    @PostMapping("/api/engine/health-checks")
    public ResponseEntity<?> check(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ResponseEntity<EngineError> denied = deny(sessionAuthorizer.authorize(sealedSession));
        if (denied != null) {
            return denied;
        }
        EngineStatus status = engineHealth.check();
        return status.state() == EngineStatus.State.BUSY
                ? error(HttpStatus.CONFLICT, "engine_busy")
                : ok(status);
    }

    private static ResponseEntity<EngineError> deny(LichessSessionAuthorization authorization) {
        return switch (authorization.type()) {
            case CONNECTED -> null;
            case DISCONNECTED -> {
                var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore());
                if (authorization.clearCookie()) {
                    response.header(
                            HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
                }
                yield response.body(new EngineError("not_connected"));
            }
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "session_unavailable");
        };
    }

    private static ResponseEntity<EngineResponse> ok(EngineStatus status) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new EngineResponse(
                        switch (status.state()) {
                            case NOT_CHECKED -> "not_checked";
                            case READY -> "ready";
                            case NOT_INSTALLED -> "not_installed";
                            case NOT_EXECUTABLE -> "not_executable";
                            case UNSUPPORTED_ENGINE -> "unsupported_engine";
                            case NOT_RESPONDING -> "not_responding";
                            case BUSY -> "busy";
                        },
                        status.path(),
                        status.engineName()));
    }

    private static ResponseEntity<EngineError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new EngineError(code));
    }

    public record EngineResponse(String state, String path, String engineName) {}

    public record EngineError(String error) {}
}
