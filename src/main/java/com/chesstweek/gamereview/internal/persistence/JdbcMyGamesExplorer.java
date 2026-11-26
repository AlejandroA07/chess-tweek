package com.chesstweek.gamereview.internal.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameRef;
import com.chesstweek.gamereview.MyGamesExplorer;
import com.chesstweek.gamereview.internal.explorer.OpeningPositions;
import com.chesstweek.shared.chess.ExplorerPosition;

/**
 * Counts the owner's stored standard games through a position from the derived position index,
 * first indexing any game stored since the last request.
 */
@Repository
public class JdbcMyGamesExplorer implements MyGamesExplorer {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int INDEX_BATCH = 200;
    private static final String UNINDEXED_GAMES = """
            SELECT g.source, g.game_id, g.moves_san
            FROM game_review_game g
            WHERE g.account_id = :accountId
              AND g.variant = 'standard'
              AND NOT EXISTS (
                  SELECT 1 FROM game_review_position_index_game i
                  WHERE i.account_id = g.account_id
                    AND i.source = g.source
                    AND i.game_id = g.game_id)
            ORDER BY g.played_at_ms DESC, g.source, g.game_id
            LIMIT :limit
            """;
    private static final String MARK_INDEXED = """
            INSERT OR IGNORE INTO game_review_position_index_game (
                account_id, source, game_id, indexed_plies)
            VALUES (:accountId, :source, :gameId, :indexedPlies)
            """;
    private static final String INSERT_POSITION = """
            INSERT INTO game_review_game_position (
                account_id, source, game_id, ply, position_key, next_uci, next_san)
            VALUES (:accountId, :source, :gameId, :ply, :positionKey, :nextUci, :nextSan)
            """;
    // The first time each game reached the position, with the move it played from there.
    private static final String GAMES_THROUGH_POSITION = """
            WITH hits AS (
                SELECT source, game_id, MIN(ply) AS ply
                FROM game_review_game_position
                WHERE account_id = :accountId AND position_key = :positionKey
                GROUP BY source, game_id
            )
            SELECT g.source, g.game_id, g.played_at_ms, g.owner_color, g.owner_result,
                   g.opponent_name, g.opponent_rating, g.speed, p.next_uci, p.next_san
            FROM hits h
            JOIN game_review_game g
              ON g.account_id = :accountId AND g.source = h.source AND g.game_id = h.game_id
            JOIN game_review_game_position p
              ON p.account_id = :accountId AND p.source = h.source
             AND p.game_id = h.game_id AND p.ply = h.ply
            WHERE (:ownerColor IS NULL OR g.owner_color = :ownerColor)
              AND (:speed IS NULL OR g.speed = :speed)
              AND (:source IS NULL OR g.source = :source)
            ORDER BY g.played_at_ms DESC, g.source, g.game_id
            """;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transactions;

    public JdbcMyGamesExplorer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public PositionStatistics statistics(
            String accountId, ExplorerPosition position, Filter filter) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()
                || position == null || filter == null) {
            throw new IllegalArgumentException("Invalid My games explorer request");
        }
        catchUp(accountId);
        var parameters = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("positionKey", position.key())
                .addValue("ownerColor", filter.ownerColor() == null ? null : filter.ownerColor().value())
                .addValue("speed", filter.speed() == null ? null : filter.speed().value())
                .addValue("source", filter.source() == null ? null : filter.source().value());
        List<GameHit> hits = namedJdbc.query(GAMES_THROUGH_POSITION, parameters, (row, index) ->
                new GameHit(
                        new GameRef(
                                GameRef.Source.fromValue(row.getString("source")).orElseThrow(),
                                row.getString("game_id")),
                        Instant.ofEpochMilli(row.getLong("played_at_ms")),
                        CatalogGame.Color.fromValue(row.getString("owner_color")),
                        CatalogGame.Result.fromValue(row.getString("owner_result")),
                        row.getString("opponent_name"),
                        nullableInteger(row, "opponent_rating"),
                        CatalogGame.Speed.fromValue(row.getString("speed")),
                        row.getString("next_uci"),
                        row.getString("next_san")));
        return summarize(hits);
    }

    private static Integer nullableInteger(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static PositionStatistics summarize(List<GameHit> hits) {
        var total = new Tally();
        var byMove = new LinkedHashMap<String, MoveTally>();
        var recent = new ArrayList<RecentGame>();
        for (GameHit hit : hits) {
            Winner winner = winner(hit.ownerColor(), hit.ownerResult());
            total.add(winner);
            if (hit.nextUci() != null) {
                byMove.computeIfAbsent(hit.nextUci(), uci -> new MoveTally(uci, hit.nextSan()))
                        .tally.add(winner);
            }
            if (recent.size() < RECENT_GAMES) {
                recent.add(new RecentGame(
                        hit.ref(),
                        hit.playedAt(),
                        hit.ownerColor(),
                        hit.ownerResult(),
                        hit.opponentName(),
                        hit.opponentRating(),
                        hit.speed(),
                        hit.nextSan()));
            }
        }
        List<NextMove> moves = byMove.values().stream()
                .map(move -> new NextMove(move.uci, move.san, move.tally.results()))
                .sorted(Comparator.comparingInt((NextMove move) -> move.results().games())
                        .reversed()
                        .thenComparing(NextMove::san))
                .toList();
        return new PositionStatistics(total.results(), moves, recent);
    }

    private static Winner winner(CatalogGame.Color ownerColor, CatalogGame.Result ownerResult) {
        return switch (ownerResult) {
            case DRAW -> Winner.NONE;
            case WIN -> ownerColor == CatalogGame.Color.WHITE ? Winner.WHITE : Winner.BLACK;
            case LOSS -> ownerColor == CatalogGame.Color.WHITE ? Winner.BLACK : Winner.WHITE;
        };
    }

    /** Indexes every standard game of the account that has no index yet, in small batches. */
    private void catchUp(String accountId) {
        while (true) {
            Integer indexed = transactions.execute(status -> indexBatch(accountId));
            if (indexed == null || indexed < INDEX_BATCH) {
                return;
            }
        }
    }

    private int indexBatch(String accountId) {
        var games = namedJdbc.query(
                UNINDEXED_GAMES,
                new MapSqlParameterSource()
                        .addValue("accountId", accountId)
                        .addValue("limit", INDEX_BATCH),
                (row, index) -> new UnindexedGame(
                        row.getString("source"), row.getString("game_id"), row.getString("moves_san")));
        for (UnindexedGame game : games) {
            List<OpeningPositions.IndexedPosition> positions;
            try {
                positions = OpeningPositions.of(game.movesSan());
            } catch (IllegalArgumentException exception) {
                positions = List.of();
            }
            int indexedPlies = (int) positions.stream()
                    .filter(position -> position.nextUci() != null)
                    .count();
            int marked = namedJdbc.update(MARK_INDEXED, new MapSqlParameterSource()
                    .addValue("accountId", accountId)
                    .addValue("source", game.source())
                    .addValue("gameId", game.gameId())
                    .addValue("indexedPlies", indexedPlies));
            if (marked == 0 || positions.isEmpty()) {
                continue;
            }
            MapSqlParameterSource[] rows = positions.stream()
                    .map(position -> new MapSqlParameterSource()
                            .addValue("accountId", accountId)
                            .addValue("source", game.source())
                            .addValue("gameId", game.gameId())
                            .addValue("ply", position.ply())
                            .addValue("positionKey", position.key())
                            .addValue("nextUci", position.nextUci())
                            .addValue("nextSan", position.nextSan()))
                    .toArray(MapSqlParameterSource[]::new);
            namedJdbc.batchUpdate(INSERT_POSITION, rows);
        }
        return games.size();
    }

    private enum Winner {
        WHITE,
        BLACK,
        NONE
    }

    private static final class Tally {

        private int white;
        private int draws;
        private int black;

        void add(Winner winner) {
            switch (winner) {
                case WHITE -> white++;
                case BLACK -> black++;
                case NONE -> draws++;
            }
        }

        Results results() {
            return new Results(white, draws, black);
        }
    }

    private static final class MoveTally {

        private final String uci;
        private final String san;
        private final Tally tally = new Tally();

        MoveTally(String uci, String san) {
            this.uci = uci;
            this.san = san;
        }
    }

    private record UnindexedGame(String source, String gameId, String movesSan) {}

    private record GameHit(
            GameRef ref,
            Instant playedAt,
            CatalogGame.Color ownerColor,
            CatalogGame.Result ownerResult,
            String opponentName,
            Integer opponentRating,
            CatalogGame.Speed speed,
            String nextUci,
            String nextSan) {}
}
