package com.chesstweek.gamereview.internal.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameRatingContext;
import com.chesstweek.gamereview.RatingContext;
import com.chesstweek.gamereview.RatingContext.BotGroup;
import com.chesstweek.gamereview.RatingContext.RatingPoint;
import com.chesstweek.gamereview.RatingContext.SpeedHistory;
import com.chesstweek.gamereview.RatingContext.Tally;

@Repository
public class JdbcGameRatingContext implements GameRatingContext {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final String RATED_STANDARD = """
            account_id = :accountId
              AND rated = 1
              AND variant = 'standard'
              AND owner_rating IS NOT NULL
              AND speed IN (:speeds)
            """;
    private static final String RECENT_RATINGS = """
            SELECT speed, played_at_ms, owner_rating, owner_rating_change, recency
            FROM (
                SELECT speed, played_at_ms, lichess_game_id, owner_rating, owner_rating_change,
                       ROW_NUMBER() OVER (
                           PARTITION BY speed
                           ORDER BY played_at_ms DESC, lichess_game_id DESC
                       ) AS recency
                FROM game_review_game
                WHERE %s
            )
            WHERE recency <= :points
            ORDER BY speed, recency DESC
            """.formatted(RATED_STANDARD);
    private static final String RATED_COUNTS = """
            SELECT speed, COUNT(*) AS games
            FROM game_review_game
            WHERE %s
            GROUP BY speed
            """.formatted(RATED_STANDARD);
    private static final String BOT_RESULTS = """
            SELECT opponent_kind, opponent_rating, opponent_ai_level, owner_result,
                   COUNT(*) AS games
            FROM game_review_game
            WHERE account_id = :accountId
              AND (opponent_kind = 'ai'
                   OR (opponent_kind = 'user' AND opponent_title = 'BOT'))
            GROUP BY opponent_kind, opponent_rating, opponent_ai_level, owner_result
            """;

    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcGameRatingContext(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    public RatingContext ratingContext(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Invalid account id");
        }
        return new RatingContext(histories(accountId), botRecord(accountId));
    }

    private List<SpeedHistory> histories(String accountId) {
        var parameters = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("speeds", RatingContext.HISTORY_SPEEDS.stream()
                        .map(CatalogGame.Speed::value)
                        .toList())
                .addValue("points", RatingContext.HISTORY_POINTS);

        Map<CatalogGame.Speed, Integer> counts = new EnumMap<>(CatalogGame.Speed.class);
        namedJdbc.query(RATED_COUNTS, parameters, row -> {
            counts.put(CatalogGame.Speed.fromValue(row.getString("speed")), row.getInt("games"));
        });

        Map<CatalogGame.Speed, List<RatingPoint>> points = new EnumMap<>(CatalogGame.Speed.class);
        Map<CatalogGame.Speed, Integer> latestChanges = new EnumMap<>(CatalogGame.Speed.class);
        namedJdbc.query(RECENT_RATINGS, parameters, row -> {
            CatalogGame.Speed speed = CatalogGame.Speed.fromValue(row.getString("speed"));
            int rating = row.getInt("owner_rating");
            int change = row.getInt("owner_rating_change");
            boolean changeMissing = row.wasNull();
            points.computeIfAbsent(speed, ignored -> new ArrayList<>()).add(new RatingPoint(
                    Instant.ofEpochMilli(row.getLong("played_at_ms")),
                    Math.max(0, rating + (changeMissing ? 0 : change))));
            if (row.getInt("recency") == 1) {
                latestChanges.put(speed, changeMissing ? null : change);
            }
        });

        return RatingContext.HISTORY_SPEEDS.stream()
                .map(speed -> new SpeedHistory(
                        speed,
                        counts.getOrDefault(speed, 0),
                        latestChanges.get(speed),
                        points.getOrDefault(speed, List.of())))
                .toList();
    }

    private RatingContext.BotRecord botRecord(String accountId) {
        List<BotResultRow> rows = namedJdbc.query(
                BOT_RESULTS,
                new MapSqlParameterSource().addValue("accountId", accountId),
                (row, rowNumber) -> new BotResultRow(
                        CatalogGame.OpponentKind.fromValue(row.getString("opponent_kind")),
                        nullableInteger(row.getInt("opponent_rating"), row.wasNull()),
                        nullableInteger(row.getInt("opponent_ai_level"), row.wasNull()),
                        CatalogGame.Result.fromValue(row.getString("owner_result")),
                        row.getInt("games")));

        Map<Integer, Tally> aiLevels = new TreeMap<>();
        Map<Integer, Tally> botBands = new TreeMap<>();
        Tally unratedBots = Tally.EMPTY;
        Tally total = Tally.EMPTY;
        Integer lowest = null;
        Integer highest = null;
        for (BotResultRow row : rows) {
            Tally tally = Tally.EMPTY.plus(row.result(), row.games());
            total = combine(total, tally);
            if (row.kind() == CatalogGame.OpponentKind.AI) {
                aiLevels.merge(row.aiLevel(), tally, JdbcGameRatingContext::combine);
            } else if (row.rating() == null) {
                unratedBots = combine(unratedBots, tally);
            } else {
                lowest = lowest == null ? row.rating() : Math.min(lowest, row.rating());
                highest = highest == null ? row.rating() : Math.max(highest, row.rating());
                int bandStart = Math.floorDiv(row.rating(), RatingContext.BOT_RATING_BAND)
                        * RatingContext.BOT_RATING_BAND;
                botBands.merge(bandStart, tally, JdbcGameRatingContext::combine);
            }
        }

        var groups = new ArrayList<BotGroup>();
        aiLevels.forEach((level, tally) ->
                groups.add(new BotGroup(BotGroup.Kind.LICHESS_AI, null, null, level, tally)));
        botBands.forEach((bandStart, tally) -> groups.add(new BotGroup(
                BotGroup.Kind.BOT_RATING_BAND,
                bandStart,
                bandStart + RatingContext.BOT_RATING_BAND - 1,
                null,
                tally)));
        if (unratedBots.games() > 0) {
            groups.add(new BotGroup(BotGroup.Kind.BOT_UNRATED, null, null, null, unratedBots));
        }
        return new RatingContext.BotRecord(total, lowest, highest, groups);
    }

    private static Integer nullableInteger(int value, boolean wasNull) {
        return wasNull ? null : value;
    }

    private static Tally combine(Tally first, Tally second) {
        return new Tally(
                first.wins() + second.wins(),
                first.draws() + second.draws(),
                first.losses() + second.losses());
    }

    private record BotResultRow(
            CatalogGame.OpponentKind kind,
            Integer rating,
            Integer aiLevel,
            CatalogGame.Result result,
            int games) {}
}
