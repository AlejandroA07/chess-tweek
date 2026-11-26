package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.chesstweek.shared.chess.ExplorerPosition;

/**
 * Opening explorer statistics from the owner's own stored standard games of both sites. Counts are
 * games, not visits: a game that passes through a position twice counts once for it.
 */
public interface MyGamesExplorer {

    /** The most recent games through a position that a result lists. */
    int RECENT_GAMES = 8;

    PositionStatistics statistics(String accountId, ExplorerPosition position, Filter filter);

    /** Optional narrowing; a null field means any. */
    record Filter(CatalogGame.Color ownerColor, CatalogGame.Speed speed, GameRef.Source source) {

        public static Filter any() {
            return new Filter(null, null, null);
        }
    }

    /** White wins, draws, and Black wins of the games through the position. */
    record Results(int white, int draws, int black) {

        public Results {
            if (white < 0 || draws < 0 || black < 0) {
                throw new IllegalArgumentException("Counts cannot be negative");
            }
        }

        public int games() {
            return white + draws + black;
        }
    }

    /** A move played from the position, with the results of the games that played it. */
    record NextMove(String uci, String san, Results results) {

        public NextMove {
            Objects.requireNonNull(uci);
            Objects.requireNonNull(san);
            Objects.requireNonNull(results);
        }
    }

    record RecentGame(
            GameRef ref,
            Instant playedAt,
            CatalogGame.Color ownerColor,
            CatalogGame.Result ownerResult,
            String opponentName,
            Integer opponentRating,
            CatalogGame.Speed speed,
            String nextSan) {

        public RecentGame {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(playedAt);
            Objects.requireNonNull(ownerColor);
            Objects.requireNonNull(ownerResult);
            Objects.requireNonNull(opponentName);
            Objects.requireNonNull(speed);
        }
    }

    /** Moves are ordered from the most played; recent games from the newest. */
    record PositionStatistics(Results results, List<NextMove> moves, List<RecentGame> recentGames) {

        public PositionStatistics {
            Objects.requireNonNull(results);
            moves = List.copyOf(moves);
            recentGames = List.copyOf(recentGames);
        }
    }
}
