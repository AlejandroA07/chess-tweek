package com.chesstweek.gamereview;

import java.time.Instant;

/** Manual retrieval of the account's newest finished chess.com games into the catalog. */
@FunctionalInterface
public interface ChessComRetrieval {

    int MAX_GAMES = 50;

    /**
     * Stores up to {@code count} (1 to {@link #MAX_GAMES}) of the newest finished games of the
     * account's chess.com username that ended before {@code before}, or the newest overall when
     * {@code before} is null.
     */
    ChessComRetrievalResult retrieve(String accountId, int count, Instant before);
}
