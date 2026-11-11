package com.chesstweek.gamereview;

import java.time.Instant;

@FunctionalInterface
public interface GameRetrieval {

    GameRetrievalResult retrieve(
            String accessToken, String username, int count, Instant before);
}
