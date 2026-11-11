package com.chesstweek.identity;

@FunctionalInterface
public interface LichessSessionAuthorizer {

    LichessSessionAuthorization authorize(String sealedSession);
}
