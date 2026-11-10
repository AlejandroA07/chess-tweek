package com.chesstweek.composition.web;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.identity.LichessAuthorizationService;

@RestController
public final class LichessAuthorizationController {

    private final LichessAuthorizationService authorizationService;

    public LichessAuthorizationController(LichessAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/api/lichess/authorize")
    public ResponseEntity<Void> authorize(HttpServletRequest request) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        URI location = authorizationService.beginAuthorization();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location.toASCIIString())
                .build();
    }

    @GetMapping("/api/lichess/callback")
    public ResponseEntity<CallbackError> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        if ("access_denied".equals(error) && authorizationService.cancelAuthorization(state)) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .header("Referrer-Policy", "no-referrer")
                    .body(new CallbackError("authorization_cancelled"));
        }
        var completion = authorizationService.completeAuthorization(code, state);
        return switch (completion.status()) {
            case SUCCESS -> successfulCallback(completion.sealedSession(), completion.maxAgeSeconds());
            case INVALID_CALLBACK -> ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .header("Referrer-Policy", "no-referrer")
                    .body(new CallbackError("invalid_oauth_callback"));
            case UPSTREAM_UNAVAILABLE -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .cacheControl(CacheControl.noStore())
                    .header("Referrer-Policy", "no-referrer")
                    .body(new CallbackError("lichess_unavailable"));
        };
    }

    private static ResponseEntity<CallbackError> successfulCallback(
            String sealedSession, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from("__Host-chess_tweek_session", sealedSession)
                .path("/")
                .maxAge(maxAgeSeconds)
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .build();
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.LOCATION, "/#/dashboard")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    public record CallbackError(String error) {}
}
