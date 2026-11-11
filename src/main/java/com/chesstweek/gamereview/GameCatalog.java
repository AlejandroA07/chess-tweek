package com.chesstweek.gamereview;

public interface GameCatalog {

    GameCatalogPage games(String accountId, GameCatalogQuery query);
}
