package com.chesstweek.composition.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

final class LoopbackRequestPolicy {

    private static final String LOCAL_ORIGIN = "http://127.0.0.1:8080";

    private LoopbackRequestPolicy() {}

    static boolean isSameOrigin(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && fetchSite.equalsIgnoreCase("cross-site")) {
            return false;
        }
        return LOCAL_ORIGIN.equals(request.getHeader(HttpHeaders.ORIGIN));
    }
}
