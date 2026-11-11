package com.chesstweek.composition.web;

import org.springframework.http.ResponseCookie;

import com.chesstweek.identity.LichessSessionService;

final class LichessSessionCookies {

    private LichessSessionCookies() {}

    static ResponseCookie cleared() {
        return ResponseCookie.from(LichessSessionService.COOKIE_NAME, "")
                .path("/")
                .maxAge(0)
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .build();
    }
}
