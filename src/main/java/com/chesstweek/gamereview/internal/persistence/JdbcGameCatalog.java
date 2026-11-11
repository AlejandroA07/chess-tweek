package com.chesstweek.gamereview.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameCatalog;
import com.chesstweek.gamereview.GameCatalogPage;
import com.chesstweek.gamereview.GameCatalogQuery;

@Repository
public class JdbcGameCatalog implements GameCatalog, GameCatalogStore {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() {};
    private static final String UPSERT = """
            INSERT INTO game_review_game (
                account_id, lichess_game_id, played_at_ms, last_move_at_ms, status,
                owner_result, owner_color, owner_rating, opponent_kind, opponent_user_id,
                opponent_name, opponent_rating, opponent_title, opponent_ai_level, speed,
                rated, variant, clock_initial_seconds, clock_increment_seconds, days_per_turn,
                opening_eco, opening_name, opening_ply, middlegame_ply, endgame_ply,
                moves_san, pgn, clocks_json, analysis_eligible, retrieved_at_ms
            ) VALUES (
                :accountId, :gameId, :playedAt, :lastMoveAt, :status,
                :ownerResult, :ownerColor, :ownerRating, :opponentKind, :opponentUserId,
                :opponentName, :opponentRating, :opponentTitle, :opponentAiLevel, :speed,
                :rated, :variant, :clockInitial, :clockIncrement, :daysPerTurn,
                :openingEco, :openingName, :openingPly, :middlegamePly, :endgamePly,
                :movesSan, :pgn, :clocksJson, :analysisEligible, :retrievedAt
            )
            ON CONFLICT (account_id, lichess_game_id) DO UPDATE SET
                played_at_ms = excluded.played_at_ms,
                last_move_at_ms = excluded.last_move_at_ms,
                status = excluded.status,
                owner_result = excluded.owner_result,
                owner_color = excluded.owner_color,
                owner_rating = excluded.owner_rating,
                opponent_kind = excluded.opponent_kind,
                opponent_user_id = excluded.opponent_user_id,
                opponent_name = excluded.opponent_name,
                opponent_rating = excluded.opponent_rating,
                opponent_title = excluded.opponent_title,
                opponent_ai_level = excluded.opponent_ai_level,
                speed = excluded.speed,
                rated = excluded.rated,
                variant = excluded.variant,
                clock_initial_seconds = excluded.clock_initial_seconds,
                clock_increment_seconds = excluded.clock_increment_seconds,
                days_per_turn = excluded.days_per_turn,
                opening_eco = excluded.opening_eco,
                opening_name = excluded.opening_name,
                opening_ply = excluded.opening_ply,
                middlegame_ply = excluded.middlegame_ply,
                endgame_ply = excluded.endgame_ply,
                moves_san = excluded.moves_san,
                pgn = excluded.pgn,
                clocks_json = excluded.clocks_json,
                analysis_eligible = excluded.analysis_eligible,
                retrieved_at_ms = excluded.retrieved_at_ms
            """;
    private static final String FIND_BY_ACCOUNT = """
            SELECT *
            FROM game_review_game
            WHERE account_id = :accountId
            """;
    private static final String COUNT_BY_ACCOUNT = """
            SELECT COUNT(*)
            FROM game_review_game
            WHERE account_id = :accountId
            """;
    private static final String NEWEST_FIRST =
            " ORDER BY played_at_ms DESC, lichess_game_id LIMIT :limit OFFSET :offset";
    private static final String OLDEST_FIRST =
            " ORDER BY played_at_ms ASC, lichess_game_id LIMIT :limit OFFSET :offset";

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;

    public JdbcGameCatalog(JdbcTemplate jdbc) {
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = JsonMapper.builder().build();
    }

    @Override
    @Transactional
    public void save(String accountId, List<CatalogGame> games, Instant retrievedAt) {
        requireAccountId(accountId);
        for (CatalogGame game : games) {
            namedJdbc.update(UPSERT, parameters(accountId, game, retrievedAt));
        }
    }

    @Override
    public GameCatalogPage games(String accountId, GameCatalogQuery query) {
        requireAccountId(accountId);
        var parameters = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("limit", query.limit())
                .addValue("offset", query.offset());
        var filters = new StringBuilder();
        if (query.result() != null) {
            filters.append(" AND owner_result = :result");
            parameters.addValue("result", query.result().value());
        }
        if (query.color() != null) {
            filters.append(" AND owner_color = :color");
            parameters.addValue("color", query.color().value());
        }
        String order = query.sort() == GameCatalogQuery.Sort.OLDEST
                ? OLDEST_FIRST
                : NEWEST_FIRST;
        List<CatalogGame> games = namedJdbc.query(
                FIND_BY_ACCOUNT + filters + order,
                parameters,
                this::mapGame);
        int total = namedJdbc.queryForObject(
                COUNT_BY_ACCOUNT + filters,
                parameters,
                Integer.class);
        return new GameCatalogPage(games, total);
    }

    private MapSqlParameterSource parameters(
            String accountId, CatalogGame game, Instant retrievedAt) {
        CatalogGame.Opponent opponent = game.opponent();
        CatalogGame.TimeControl time = game.timeControl();
        CatalogGame.Opening opening = game.opening();
        CatalogGame.PhaseDivision phase = game.phaseDivision();
        return new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("gameId", game.id())
                .addValue("playedAt", game.playedAt().toEpochMilli())
                .addValue("lastMoveAt", game.lastMoveAt().toEpochMilli())
                .addValue("status", game.status().value())
                .addValue("ownerResult", game.ownerResult().value())
                .addValue("ownerColor", game.ownerColor().value())
                .addValue("ownerRating", game.ownerRating())
                .addValue("opponentKind", opponent.kind().value())
                .addValue("opponentUserId", opponent.userId())
                .addValue("opponentName", opponent.name())
                .addValue("opponentRating", opponent.rating())
                .addValue("opponentTitle", opponent.title())
                .addValue("opponentAiLevel", opponent.aiLevel())
                .addValue("speed", game.speed().value())
                .addValue("rated", game.rated() ? 1 : 0)
                .addValue("variant", game.variant().value())
                .addValue("clockInitial", time == null ? null : time.initialSeconds())
                .addValue("clockIncrement", time == null ? null : time.incrementSeconds())
                .addValue("daysPerTurn", time == null ? null : time.daysPerTurn())
                .addValue("openingEco", opening == null ? null : opening.eco())
                .addValue("openingName", opening == null ? null : opening.name())
                .addValue("openingPly", opening == null ? null : opening.ply())
                .addValue("middlegamePly", phase == null ? null : phase.middlePly())
                .addValue("endgamePly", phase == null ? null : phase.endPly())
                .addValue("movesSan", game.movesSan())
                .addValue("pgn", game.pgn())
                .addValue("clocksJson", objectMapper.writeValueAsString(game.clocks()))
                .addValue("analysisEligible", game.analysisEligible() ? 1 : 0)
                .addValue("retrievedAt", retrievedAt.toEpochMilli());
    }

    private CatalogGame mapGame(ResultSet result, int rowNumber) throws SQLException {
        CatalogGame.TimeControl time = timeControl(result);
        CatalogGame.Opening opening = opening(result);
        CatalogGame.PhaseDivision phase = phaseDivision(result);
        return new CatalogGame(
                result.getString("lichess_game_id"),
                Instant.ofEpochMilli(result.getLong("played_at_ms")),
                Instant.ofEpochMilli(result.getLong("last_move_at_ms")),
                CatalogGame.Status.fromValue(result.getString("status")),
                CatalogGame.Result.fromValue(result.getString("owner_result")),
                CatalogGame.Color.fromValue(result.getString("owner_color")),
                nullableInteger(result, "owner_rating"),
                new CatalogGame.Opponent(
                        CatalogGame.OpponentKind.fromValue(result.getString("opponent_kind")),
                        result.getString("opponent_user_id"),
                        result.getString("opponent_name"),
                        nullableInteger(result, "opponent_rating"),
                        result.getString("opponent_title"),
                        nullableInteger(result, "opponent_ai_level")),
                CatalogGame.Speed.fromValue(result.getString("speed")),
                result.getInt("rated") == 1,
                CatalogGame.Variant.fromValue(result.getString("variant")),
                time,
                opening,
                phase,
                result.getString("moves_san"),
                result.getString("pgn"),
                objectMapper.readValue(result.getString("clocks_json"), INTEGER_LIST),
                result.getInt("analysis_eligible") == 1);
    }

    private static CatalogGame.TimeControl timeControl(ResultSet result) throws SQLException {
        Integer initial = nullableInteger(result, "clock_initial_seconds");
        Integer increment = nullableInteger(result, "clock_increment_seconds");
        Integer days = nullableInteger(result, "days_per_turn");
        return initial == null && increment == null && days == null
                ? null
                : new CatalogGame.TimeControl(initial, increment, days);
    }

    private static CatalogGame.Opening opening(ResultSet result) throws SQLException {
        String eco = result.getString("opening_eco");
        return eco == null
                ? null
                : new CatalogGame.Opening(
                        eco,
                        result.getString("opening_name"),
                        nullableInteger(result, "opening_ply"));
    }

    private static CatalogGame.PhaseDivision phaseDivision(ResultSet result) throws SQLException {
        Integer middle = nullableInteger(result, "middlegame_ply");
        Integer end = nullableInteger(result, "endgame_ply");
        return middle == null && end == null ? null : new CatalogGame.PhaseDivision(middle, end);
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static void requireAccountId(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Invalid account id");
        }
    }
}
