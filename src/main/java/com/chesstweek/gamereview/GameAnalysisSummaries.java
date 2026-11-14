package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** The newest stored analysis of each side of stored games, for library cards. */
@FunctionalInterface
public interface GameAnalysisSummaries {

    int MAX_GAMES = 50;

    /**
     * The newest version per side for each of the account's games that has one. Games with no
     * stored analysis are absent. At most {@link #MAX_GAMES} ids are accepted.
     */
    Map<String, Summary> latest(String accountId, Collection<String> gameIds);

    /** Either side is null when that side has never been analyzed. */
    record Summary(Version myMoves, Version opponentMoves) {}

    /** {@code currentProfile} is false when a newer benchmark profile exists than the one used. */
    record Version(
            long id,
            Instant completedAt,
            boolean currentProfile,
            int moves,
            int inaccuracies,
            int mistakes,
            int blunders) {}
}
