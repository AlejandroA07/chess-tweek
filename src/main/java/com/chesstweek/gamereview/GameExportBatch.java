package com.chesstweek.gamereview;

import java.util.List;

record GameExportBatch(List<ExportedGame> games, int rejected) {

    GameExportBatch {
        games = List.copyOf(games);
        if (rejected < 0) {
            throw new IllegalArgumentException("Rejected game count must not be negative");
        }
    }

    GameExportBatch(List<ExportedGame> games) {
        this(games, 0);
    }
}
