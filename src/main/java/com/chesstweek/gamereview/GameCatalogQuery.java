package com.chesstweek.gamereview;

import java.time.LocalDate;
import java.util.Objects;

public record GameCatalogQuery(
        int offset,
        int limit,
        CatalogGame.Result result,
        CatalogGame.Color color,
        String opening,
        OpponentType opponentType,
        CatalogGame.Speed speed,
        LocalDate playedFrom,
        LocalDate playedTo,
        CatalogGame.Variant variant,
        AnalysisState analysisState,
        Sort sort) {

    public GameCatalogQuery {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Invalid catalog page");
        }
        if (opening != null && (opening.isBlank()
                || opening.length() > 80
                || !opening.equals(opening.strip())
                || opening.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid opening filter");
        }
        LocalDate earliest = LocalDate.of(2000, 1, 1);
        LocalDate latest = LocalDate.of(2100, 12, 31);
        if ((playedFrom != null && (playedFrom.isBefore(earliest) || playedFrom.isAfter(latest)))
                || (playedTo != null && (playedTo.isBefore(earliest) || playedTo.isAfter(latest)))) {
            throw new IllegalArgumentException("Catalog date filter out of range");
        }
        if (playedFrom != null && playedTo != null && playedFrom.isAfter(playedTo)) {
            throw new IllegalArgumentException("Invalid catalog date range");
        }
        Objects.requireNonNull(sort, "sort");
    }

    public GameCatalogQuery(
            int offset,
            int limit,
            CatalogGame.Result result,
            CatalogGame.Color color,
            Sort sort) {
        this(
                offset,
                limit,
                result,
                color,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sort);
    }

    public static GameCatalogQuery unfiltered(int offset, int limit) {
        return new GameCatalogQuery(offset, limit, null, null, Sort.NEWEST);
    }

    public enum Sort {
        NEWEST,
        OLDEST
    }

    public enum OpponentType {
        HUMAN,
        LICHESS_AI,
        BOT,
        ANONYMOUS
    }

    public enum AnalysisState {
        ELIGIBLE,
        CATALOG_ONLY
    }
}
