package com.chesstweek.training.internal.explorer;

import java.util.List;
import java.util.Objects;

import com.chesstweek.training.OpeningExplorer;

/** A validated explorer answer as it is cached: everything but when it was fetched. */
public record ExplorerAnswer(
        OpeningExplorer.Opening opening,
        OpeningExplorer.Results results,
        List<OpeningExplorer.NextMove> moves,
        List<OpeningExplorer.TopGame> topGames) {

    public ExplorerAnswer {
        Objects.requireNonNull(results);
        moves = List.copyOf(moves);
        topGames = List.copyOf(topGames);
    }

    OpeningExplorer.Statistics fetchedAt(java.time.Instant fetchedAt) {
        return new OpeningExplorer.Statistics(opening, results, moves, topGames, fetchedAt, false);
    }
}
