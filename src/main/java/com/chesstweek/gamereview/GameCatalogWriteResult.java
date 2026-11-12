package com.chesstweek.gamereview;

public record GameCatalogWriteResult(int created, int updated, int unchanged) {

    public GameCatalogWriteResult {
        if (created < 0 || updated < 0 || unchanged < 0) {
            throw new IllegalArgumentException("Game catalog write counts must not be negative");
        }
    }

    public int total() {
        return Math.addExact(Math.addExact(created, updated), unchanged);
    }
}
