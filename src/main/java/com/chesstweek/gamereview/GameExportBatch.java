package com.chesstweek.gamereview;

import java.util.List;

record GameExportBatch(List<ExportedGame> games) {

    GameExportBatch {
        games = List.copyOf(games);
    }
}
