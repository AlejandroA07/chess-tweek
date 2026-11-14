package com.chesstweek.gamereview.internal.persistence;

import java.util.Optional;

import com.chesstweek.gamereview.CatalogGame;

@FunctionalInterface
public interface GameLookup {

    /** The account's stored game with this Lichess id, if any. */
    Optional<CatalogGame> find(String accountId, String gameId);
}
