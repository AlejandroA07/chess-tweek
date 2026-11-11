package com.chesstweek.gamereview;

import java.util.Objects;

public record GameCatalogQuery(
        int offset,
        int limit,
        CatalogGame.Result result,
        CatalogGame.Color color,
        Sort sort) {

    public GameCatalogQuery {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Invalid catalog page");
        }
        Objects.requireNonNull(sort, "sort");
    }

    public static GameCatalogQuery unfiltered(int offset, int limit) {
        return new GameCatalogQuery(offset, limit, null, null, Sort.NEWEST);
    }

    public enum Sort {
        NEWEST,
        OLDEST
    }
}
