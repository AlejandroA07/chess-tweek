package com.chesstweek.gamereview.internal.persistence;

import java.time.Instant;
import java.util.List;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameCatalogWriteResult;

public interface GameCatalogStore {

    GameCatalogWriteResult save(String accountId, List<CatalogGame> games, Instant retrievedAt);
}
