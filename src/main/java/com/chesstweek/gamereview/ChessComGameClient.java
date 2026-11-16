package com.chesstweek.gamereview;

import java.time.YearMonth;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Reads a player's finished games from the public chess.com archives, one request at a time. */
interface ChessComGameClient {

    /** The months that have games, newest first. */
    List<YearMonth> archiveMonths(String username);

    /** One month's games, in the order chess.com returns them. */
    List<JsonNode> monthGames(String username, YearMonth month);
}
