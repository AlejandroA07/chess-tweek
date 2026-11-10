package com.chesstweek.identity;

import com.fasterxml.jackson.annotation.JsonProperty;

record LichessToken(
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresInSeconds) {

    boolean isValid() {
        return tokenType != null
                && tokenType.equalsIgnoreCase("Bearer")
                && accessToken != null
                && accessToken.matches("[A-Za-z0-9_]{1,3072}")
                && expiresInSeconds > 0
                && expiresInSeconds <= 34_560_000;
    }
}
