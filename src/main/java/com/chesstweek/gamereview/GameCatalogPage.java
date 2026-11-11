package com.chesstweek.gamereview;

import java.util.List;

public record GameCatalogPage(List<CatalogGame> games, int total) {

    public GameCatalogPage {
        games = List.copyOf(games);
        if (total < games.size()) {
            throw new IllegalArgumentException("Catalog total must include the returned games");
        }
    }
}
