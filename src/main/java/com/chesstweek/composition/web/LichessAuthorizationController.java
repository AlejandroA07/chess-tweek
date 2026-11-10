package com.chesstweek.composition.web;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.identity.LichessAuthorizationService;

@RestController
public final class LichessAuthorizationController {

    private static final String LOCAL_ORIGIN = "http://127.0.0.1:8080";

    private final LichessAuthorizationService authorizationService;

    public LichessAuthorizationController(LichessAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/api/lichess/authorize")
    public ResponseEntity<Void> authorize(HttpServletRequest request) {
        if (!isTrustedInitiation(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        URI location = authorizationService.beginAuthorization();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location.toASCIIString())
                .build();
    }

    private static boolean isTrustedInitiation(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && fetchSite.equalsIgnoreCase("cross-site")) {
            return false;
        }

        String origin = request.getHeader(HttpHeaders.ORIGIN);
        return LOCAL_ORIGIN.equals(origin);
    }
}
