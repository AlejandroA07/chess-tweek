package com.chesstweek.gamereview.internal.persistence;

import java.util.Optional;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameRef;

@FunctionalInterface
public interface GameLookup {

    /** The account's stored game with this source and ID, if any. */
    Optional<CatalogGame> find(String accountId, GameRef game);
}
