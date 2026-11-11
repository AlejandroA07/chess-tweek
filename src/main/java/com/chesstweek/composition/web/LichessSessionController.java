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

import com.chesstweek.identity.LichessSessionService;

@RestController
public final class LichessSessionController {

    private final LichessSessionService sessionService;

    public LichessSessionController(LichessSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping("/api/lichess/session")
    public ResponseEntity<?> session(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        var status = sessionService.status(sealedSession);
        return switch (status.type()) {
            case CONNECTED -> ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new SessionResponse(true, status.account()));
            case DISCONNECTED -> disconnected(status.clearCookie());
            case UPSTREAM_UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new SessionError("lichess_unavailable"));
        };
    }

    @PostMapping("/api/lichess/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        sessionService.logout(sealedSession);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString())
                .build();
    }

    private static ResponseEntity<SessionResponse> disconnected(boolean clearCookie) {
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (clearCookie) {
            response.header(HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
        }
        return response.body(new SessionResponse(false, null));
    }

    public record SessionResponse(boolean connected, LichessSessionService.Account account) {}

    public record SessionError(String error) {}
}
