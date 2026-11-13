package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.notation.NotationType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

@Component
final class GamePayloadParser {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern GAME_ID = Pattern.compile("[A-Za-z0-9]{8,32}");
    private static final Pattern ECO = Pattern.compile("[A-E][0-9]{2}");
    private static final Pattern TITLE =
            Pattern.compile("GM|WGM|IM|WIM|FM|WFM|NM|WNM|CM|WCM|LM|BOT");
    private static final int MAX_MOVES_LENGTH = 131_072;
    private static final int MAX_PGN_LENGTH = 524_288;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_OPENING_NAME_LENGTH = 160;
    private static final int MAX_PLIES = 2_000;
    private static final int MAX_RATING_CHANGE = 1_000;
    private static final Set<CatalogGame.Status> DRAW_STATUSES = Set.of(
            CatalogGame.Status.DRAW,
            CatalogGame.Status.STALEMATE,
            CatalogGame.Status.INSUFFICIENT_MATERIAL_CLAIM);

    private final ObjectMapper objectMapper;

    GamePayloadParser() {
        objectMapper = JsonMapper.builder().build();
    }

    CatalogGame parse(String accountId, ExportedGame exportedGame) {
        try {
            return parseValidated(accountId, exportedGame);
        } catch (GamePayloadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid(exception);
        }
    }

    private CatalogGame parseValidated(String accountId, ExportedGame exportedGame) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw invalid();
        }

        JsonNode root = objectMapper.readTree(exportedGame.ndjson());
        String id = requiredText(root, "id", 8, 32);
        if (!id.equals(exportedGame.id()) || !GAME_ID.matcher(id).matches()) {
            throw invalid();
        }
        CatalogGame.Status status = CatalogGame.Status.fromValue(requiredText(root, "status", 3, 40));
        if (!status.value().equals(exportedGame.status())) {
            throw invalid();
        }

        CatalogGame.Variant variant = CatalogGame.Variant.fromValue(
                requiredText(root, "variant", 3, 32));
        CatalogGame.Speed speed = CatalogGame.Speed.fromValue(requiredText(root, "speed", 3, 32));
        boolean rated = requiredBoolean(root, "rated");
        long playedAt = requiredLong(root, "createdAt", 0, Long.MAX_VALUE);
        long lastMoveAt = requiredLong(root, "lastMoveAt", playedAt, Long.MAX_VALUE);
        String moves = requiredText(root, "moves", 1, MAX_MOVES_LENGTH);
        int plyCount = moves.split("\\s+").length;
        if (plyCount > MAX_PLIES) {
            throw invalid();
        }
        if (variant == CatalogGame.Variant.STANDARD) {
            validateStandardMoves(moves);
        }

        Players players = players(root.get("players"), accountId);
        CatalogGame.Color ownerColor = players.ownerColor();
        Integer ownerRatingChange = rated ? ratingChange(players.ownerNode()) : null;
        CatalogGame.Result result = result(root.get("winner"), ownerColor, status);
        String pgn = optionalText(root, "pgn", 1, MAX_PGN_LENGTH);
        List<Integer> clocks = clocks(root.get("clocks"));
        CatalogGame.TimeControl timeControl = timeControl(root);
        CatalogGame.Opening opening = opening(root.get("opening"), plyCount);
        CatalogGame.PhaseDivision division = division(root.get("division"), plyCount);

        return new CatalogGame(
                id,
                Instant.ofEpochMilli(playedAt),
                Instant.ofEpochMilli(lastMoveAt),
                status,
                result,
                ownerColor,
                players.owner().rating(),
                ownerRatingChange,
                players.opponent().toOpponent(),
                speed,
                rated,
                variant,
                timeControl,
                opening,
                division,
                moves,
                pgn,
                clocks,
                variant == CatalogGame.Variant.STANDARD);
    }

    private static void validateStandardMoves(String moves) {
        try {
            new ChessGame().playMoves(NotationType.SAN, moves);
        } catch (RuntimeException exception) {
            throw invalid(exception);
        }
    }

    private static Players players(JsonNode players, String accountId) {
        if (players == null || !players.isObject()) {
            throw invalid();
        }
        JsonNode whiteNode = players.get("white");
        JsonNode blackNode = players.get("black");
        Player white = player(whiteNode);
        Player black = player(blackNode);
        boolean ownerIsWhite = white.userId() != null && white.userId().equalsIgnoreCase(accountId);
        boolean ownerIsBlack = black.userId() != null && black.userId().equalsIgnoreCase(accountId);
        if (ownerIsWhite == ownerIsBlack) {
            throw invalid();
        }
        if (ownerIsWhite) {
            return new Players(CatalogGame.Color.WHITE, white, black, whiteNode);
        }
        return new Players(CatalogGame.Color.BLACK, black, white, blackNode);
    }

    private static Integer ratingChange(JsonNode ownerNode) {
        return optionalInteger(ownerNode, "ratingDiff", -MAX_RATING_CHANGE, MAX_RATING_CHANGE);
    }

    private static Player player(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        JsonNode aiLevel = node.get("aiLevel");
        if (aiLevel != null) {
            int level = integer(aiLevel, 1, 8);
            return new Player(CatalogGame.OpponentKind.AI, null, "Lichess AI", null, null, level);
        }

        JsonNode user = node.get("user");
        if (user != null && user.isObject()) {
            String id = requiredText(user, "id", 1, 32);
            String name = requiredText(user, "name", 1, MAX_NAME_LENGTH);
            int rating = requiredInteger(node, "rating", 0, 5_000);
            String title = optionalText(user, "title", 2, 3);
            if (title != null && !TITLE.matcher(title).matches()) {
                throw invalid();
            }
            return new Player(CatalogGame.OpponentKind.USER, id, name, rating, title, null);
        }

        String name = optionalText(node, "name", 1, MAX_NAME_LENGTH);
        Integer rating = optionalInteger(node, "rating", 0, 5_000);
        return new Player(
                CatalogGame.OpponentKind.ANONYMOUS,
                null,
                name == null ? "Anonymous" : name,
                rating,
                null,
                null);
    }

    private static CatalogGame.Result result(
            JsonNode winner, CatalogGame.Color ownerColor, CatalogGame.Status status) {
        if (winner == null || winner.isNull()) {
            if (DRAW_STATUSES.contains(status)) {
                return CatalogGame.Result.DRAW;
            }
            throw invalid();
        }
        if (!winner.isString()) {
            throw invalid();
        }
        CatalogGame.Color winnerColor = CatalogGame.Color.fromValue(winner.stringValue());
        return winnerColor == ownerColor ? CatalogGame.Result.WIN : CatalogGame.Result.LOSS;
    }

    private static CatalogGame.TimeControl timeControl(JsonNode root) {
        JsonNode clock = root.get("clock");
        Integer initial = null;
        Integer increment = null;
        if (clock != null) {
            if (!clock.isObject()) {
                throw invalid();
            }
            initial = requiredInteger(clock, "initial", 0, 604_800);
            increment = requiredInteger(clock, "increment", 0, 86_400);
        }
        Integer daysPerTurn = optionalInteger(root, "daysPerTurn", 1, 30);
        return initial == null && daysPerTurn == null
                ? null
                : new CatalogGame.TimeControl(initial, increment, daysPerTurn);
    }

    private static CatalogGame.Opening opening(JsonNode node, int plyCount) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalid();
        }
        String eco = requiredText(node, "eco", 3, 3);
        if (!ECO.matcher(eco).matches()) {
            throw invalid();
        }
        String name = requiredText(node, "name", 1, MAX_OPENING_NAME_LENGTH);
        int ply = requiredInteger(node, "ply", 0, plyCount);
        return new CatalogGame.Opening(eco, name, ply);
    }

    private static CatalogGame.PhaseDivision division(JsonNode node, int plyCount) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalid();
        }
        Integer middle = optionalInteger(node, "middle", 0, plyCount);
        Integer end = optionalInteger(node, "end", 0, plyCount);
        if (middle != null && end != null && end < middle) {
            throw invalid();
        }
        return new CatalogGame.PhaseDivision(middle, end);
    }

    private static List<Integer> clocks(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_PLIES) {
            throw invalid();
        }
        var result = new ArrayList<Integer>(node.size());
        for (JsonNode clock : node) {
            result.add(integer(clock, 0, 100_000_000));
        }
        return result;
    }

    private static String requiredText(JsonNode node, String field, int min, int max) {
        String value = optionalText(node, field, min, max);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()
                || value.stringValue().length() < min
                || value.stringValue().length() > max) {
            throw invalid();
        }
        return value.stringValue();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid();
        }
        return value.booleanValue();
    }

    private static long requiredLong(JsonNode node, String field, long min, long max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid();
        }
        long result = value.longValue();
        if (result < min || result > max) {
            throw invalid();
        }
        return result;
    }

    private static int requiredInteger(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw invalid();
        }
        return integer(value, min, max);
    }

    private static Integer optionalInteger(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : integer(value, min, max);
    }

    private static int integer(JsonNode value, int min, int max) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        int result = value.intValue();
        if (result < min || result > max) {
            throw invalid();
        }
        return result;
    }

    private static GamePayloadException invalid() {
        return new GamePayloadException(null);
    }

    private static GamePayloadException invalid(Throwable cause) {
        return new GamePayloadException(cause);
    }

    static final class GamePayloadException extends RuntimeException {

        private GamePayloadException(Throwable cause) {
            super(cause);
        }
    }

    private record Players(
            CatalogGame.Color ownerColor,
            Player owner,
            Player opponent,
            JsonNode ownerNode) {}

    private record Player(
            CatalogGame.OpponentKind kind,
            String userId,
            String name,
            Integer rating,
            String title,
            Integer aiLevel) {

        private CatalogGame.Opponent toOpponent() {
            return new CatalogGame.Opponent(kind, userId, name, rating, title, aiLevel);
        }
    }
}
