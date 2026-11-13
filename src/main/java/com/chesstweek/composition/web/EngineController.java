package com.chesstweek.composition.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.gamereview.EngineBenchmark;
import com.chesstweek.gamereview.EngineHealth;
import com.chesstweek.gamereview.EngineProfile;
import com.chesstweek.gamereview.EngineStatus;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;

@RestController
public final class EngineController {

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final EngineHealth engineHealth;
    private final EngineBenchmark engineBenchmark;

    EngineController(
            LichessSessionAuthorizer sessionAuthorizer,
            EngineHealth engineHealth,
            EngineBenchmark engineBenchmark) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.engineHealth = engineHealth;
        this.engineBenchmark = engineBenchmark;
    }

    @GetMapping("/api/engine")
    public ResponseEntity<?> status(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        ResponseEntity<EngineError> denied = deny(sessionAuthorizer.authorize(sealedSession));
        if (denied != null) {
            return denied;
        }
        try {
            return ok(engineHealth.status(), engineBenchmark.currentProfile().orElse(null));
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "engine_profile_unavailable");
        }
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
        if (status.state() == EngineStatus.State.BUSY) {
            return error(HttpStatus.CONFLICT, "engine_busy");
        }
        try {
            return ok(status, engineBenchmark.currentProfile().orElse(null));
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "engine_profile_unavailable");
        }
    }

    @PostMapping("/api/engine/benchmarks")
    public ResponseEntity<?> benchmark(
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
        EngineBenchmark.BenchmarkResult result;
        try {
            result = engineBenchmark.run();
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "engine_profile_unavailable");
        }
        EngineStatus status = result.engine();
        if (status.state() == EngineStatus.State.BUSY) {
            return error(HttpStatus.CONFLICT, "engine_busy");
        }
        if (status.state() != EngineStatus.State.READY) {
            return error(HttpStatus.CONFLICT, "engine_not_ready");
        }
        if (result.profile() == null) {
            return error(HttpStatus.BAD_GATEWAY, "benchmark_failed");
        }
        return ok(status, result.profile());
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

    private static ResponseEntity<EngineResponse> ok(EngineStatus status, EngineProfile profile) {
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
                        status.engineName(),
                        profile == null ? null : new ProfileResponse(
                                profile.id(),
                                profile.engineName(),
                                profile.threads(),
                                profile.hashMb(),
                                profile.multiPv(),
                                profile.benchmarkPositions(),
                                profile.benchmarkNodesPerPosition(),
                                profile.nodesPerSecond(),
                                profile.quickTargetMs(),
                                profile.deepTargetMs(),
                                profile.quickNodes(),
                                profile.deepNodes(),
                                profile.createdAt().toString())));
    }

    private static ResponseEntity<EngineError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new EngineError(code));
    }

    public record EngineResponse(
            String state, String path, String engineName, ProfileResponse profile) {}

    public record ProfileResponse(
            long id,
            String engineName,
            int threads,
            int hashMb,
            int multiPv,
            int benchmarkPositions,
            long benchmarkNodesPerPosition,
            long nodesPerSecond,
            int quickTargetMs,
            int deepTargetMs,
            long quickNodes,
            long deepNodes,
            String createdAt) {}

    public record EngineError(String error) {}
}
