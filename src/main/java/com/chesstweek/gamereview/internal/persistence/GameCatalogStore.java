package com.chesstweek.gamereview.internal.persistence;

import java.time.Instant;
import java.util.List;

import com.chesstweek.gamereview.CatalogGame;

public interface GameCatalogStore {

    void save(String accountId, List<CatalogGame> games, Instant retrievedAt);
}
