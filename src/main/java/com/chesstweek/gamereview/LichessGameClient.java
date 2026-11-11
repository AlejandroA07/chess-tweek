package com.chesstweek.gamereview;

import java.time.Instant;

interface LichessGameClient {

    GameExportBatch fetch(String accessToken, String username, int count, Instant before);
}
