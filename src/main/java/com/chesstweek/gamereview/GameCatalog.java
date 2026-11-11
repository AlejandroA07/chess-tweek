package com.chesstweek.gamereview;

import java.util.List;

public interface GameCatalog {

    List<CatalogGame> games(String accountId);
}
