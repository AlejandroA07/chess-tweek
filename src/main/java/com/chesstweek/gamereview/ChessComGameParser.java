package com.chesstweek.gamereview;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.notation.NotationType;
import io.github.wolfraam.chessgame.pgn.PGNComment;
import io.github.wolfraam.chessgame.pgn.PGNData;
import io.github.wolfraam.chessgame.pgn.PGNImporter;
import io.github.wolfraam.chessgame.pgn.PGNTag;
import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * Maps one game from a chess.com monthly archive onto the shared catalog. Only standard chess from
 * the normal starting position is stored; every other game is rejected rather than stored
 * partially. Moves are replayed from the PGN, so an illegal or unreadable game is rejected too.
 */
@Component
final class ChessComGameParser {

    private static final Pattern GAME_URL =
            Pattern.compile("https://www\\.chess\\.com/game/(?:live|daily)/([0-9]{1,20})");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern LIVE_TIME_CONTROL = Pattern.compile("([0-9]{1,6})(?:\\+([0-9]{1,5}))?");
    private static final Pattern DAILY_TIME_CONTROL = Pattern.compile("1/([0-9]{1,7})");
    private static final Pattern ECO = Pattern.compile("[A-E][0-9]{2}");
    private static final String OPENINGS_URL = "https://www.chess.com/openings/";
    private static final Pattern OPENING_SLUG = Pattern.compile("[A-Za-z0-9.,'()-]{1,160}");
    private static final Pattern CLOCK =
            Pattern.compile("\\[%clk ([0-9]{1,3}):([0-5][0-9]):([0-5][0-9])(?:\\.([0-9]))?\\]");
    private static final DateTimeFormatter PGN_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd");
    private static final String STANDARD_START =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final int MAX_PGN_LENGTH = 524_288;
    private static final int MAX_PLIES = 2_000;
    private static final Map<String, CatalogGame.Speed> SPEEDS = Map.of(
            "bullet", CatalogGame.Speed.BULLET,
            "blitz", CatalogGame.Speed.BLITZ,
            "rapid", CatalogGame.Speed.RAPID,
            "daily", CatalogGame.Speed.CORRESPONDENCE);
    /** How the losing side's game ended. */
    private static final Map<String, CatalogGame.Status> LOSSES = Map.of(
            "checkmated", CatalogGame.Status.MATE,
            "resigned", CatalogGame.Status.RESIGN,
            "timeout", CatalogGame.Status.OUT_OF_TIME,
            "abandoned", CatalogGame.Status.TIMEOUT,
            "lose", CatalogGame.Status.UNKNOWN_FINISH);
    private static final Set<String> DRAWS = Set.of(
            "agreed", "repetition", "stalemate", "insufficient", "50move", "timevsinsufficient");

    CatalogGame parse(String ownerUsername, JsonNode game) {
        try {
            return parseValidated(ownerUsername, game);
        } catch (InvalidGameException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidGameException(exception);
        }
    }

    private CatalogGame parseValidated(String ownerUsername, JsonNode game) {
        if (game == null || !game.isObject()) {
            throw invalid();
        }
        Matcher url = GAME_URL.matcher(text(game, "url", 1, 80));
        if (!url.matches()) {
            throw invalid();
        }
        GameRef ref = new GameRef(GameRef.Source.CHESSCOM, url.group(1));
        if (!"chess".equals(text(game, "rules", 1, 32))) {
            throw invalid();
        }
        CatalogGame.Speed speed = SPEEDS.get(text(game, "time_class", 1, 16));
        if (speed == null) {
            throw invalid();
        }
        CatalogGame.TimeControl timeControl =
                timeControl(text(game, "time_control", 1, 16), speed);
        JsonNode rated = game.get("rated");
        if (rated == null || !rated.isBoolean()) {
            throw invalid();
        }
        Instant endedAt = Instant.ofEpochSecond(longValue(game, "end_time"));

        Player white = player(game.get("white"));
        Player black = player(game.get("black"));
        boolean ownerIsWhite = white.username().equalsIgnoreCase(ownerUsername);
        boolean ownerIsBlack = black.username().equalsIgnoreCase(ownerUsername);
        if (ownerIsWhite == ownerIsBlack) {
            throw invalid();
        }
        Player owner = ownerIsWhite ? white : black;
        Player opponent = ownerIsWhite ? black : white;
        Finish finish = finish(owner.result(), opponent.result());

        String pgn = text(game, "pgn", 1, MAX_PGN_LENGTH);
        ChessGame replay = replay(pgn);
        PGNData pgnData = replay.getPGNData();
        List<String> san = replay.getNotationList(NotationType.SAN);
        if (san.isEmpty() || san.size() > MAX_PLIES) {
            throw invalid();
        }

        Instant playedAt = startedAt(game, pgnData, endedAt);
        return new CatalogGame(
                ref,
                playedAt,
                endedAt,
                finish.status(),
                finish.result(),
                ownerIsWhite ? CatalogGame.Color.WHITE : CatalogGame.Color.BLACK,
                owner.rating(),
                null,
                new CatalogGame.Opponent(
                        CatalogGame.OpponentKind.USER,
                        opponent.username().toLowerCase(Locale.ROOT),
                        opponent.username(),
                        opponent.rating(),
                        null,
                        null),
                speed,
                rated.booleanValue(),
                CatalogGame.Variant.STANDARD,
                timeControl,
                opening(pgnData),
                null,
                String.join(" ", san),
                pgn,
                clocks(pgnData, san.size()),
                true);
    }

    private static ChessGame replay(String pgn) {
        var games = new ArrayList<ChessGame>(1);
        var failed = new boolean[1];
        var importer = new PGNImporter();
        importer.setOnGame(games::add);
        importer.setOnError((message, exception) -> failed[0] = true);
        importer.setOnWarning(message -> {
            // Warnings (for example unknown pgnData) do not change the replayed moves.
        });
        importer.run(new ByteArrayInputStream(pgn.getBytes(StandardCharsets.UTF_8)));
        if (failed[0] || games.size() != 1) {
            throw invalid();
        }
        ChessGame game = games.getFirst();
        String setUp = game.getPGNData().getPGNTagValue(PGNTag.SET_UP);
        String fen = game.getPGNData().getPGNTagValue(PGNTag.FEN);
        if ((setUp != null && !"0".equals(setUp)) || (fen != null && !STANDARD_START.equals(fen))) {
            throw invalid();
        }
        return game;
    }

    private static Finish finish(String owner, String opponent) {
        if ("win".equals(owner) && LOSSES.containsKey(opponent)) {
            return new Finish(LOSSES.get(opponent), CatalogGame.Result.WIN);
        }
        if ("win".equals(opponent) && LOSSES.containsKey(owner)) {
            return new Finish(LOSSES.get(owner), CatalogGame.Result.LOSS);
        }
        if (DRAWS.contains(owner) && owner.equals(opponent)) {
            CatalogGame.Status status = "stalemate".equals(owner)
                    ? CatalogGame.Status.STALEMATE
                    : CatalogGame.Status.DRAW;
            return new Finish(status, CatalogGame.Result.DRAW);
        }
        throw invalid();
    }

    private static CatalogGame.TimeControl timeControl(String value, CatalogGame.Speed speed) {
        if (speed == CatalogGame.Speed.CORRESPONDENCE) {
            Matcher daily = DAILY_TIME_CONTROL.matcher(value);
            if (!daily.matches()) {
                throw invalid();
            }
            int seconds = Integer.parseInt(daily.group(1));
            if (seconds % 86_400 != 0 || seconds / 86_400 < 1 || seconds / 86_400 > 30) {
                throw invalid();
            }
            return new CatalogGame.TimeControl(null, null, seconds / 86_400);
        }
        Matcher live = LIVE_TIME_CONTROL.matcher(value);
        if (!live.matches()) {
            throw invalid();
        }
        int initial = Integer.parseInt(live.group(1));
        int increment = live.group(2) == null ? 0 : Integer.parseInt(live.group(2));
        if (initial > 604_800 || increment > 86_400) {
            throw invalid();
        }
        return new CatalogGame.TimeControl(initial, increment, null);
    }

    /** Daily games carry their start; live games carry it in the PGN. Never after the end. */
    private static Instant startedAt(JsonNode game, PGNData pgnData, Instant endedAt) {
        Instant started = null;
        JsonNode startTime = game.get("start_time");
        if (startTime != null && !startTime.isNull()) {
            started = Instant.ofEpochSecond(longValue(game, "start_time"));
        } else {
            String date = pgnData.getPGNTagValue(PGNTag.UTC_DATE);
            String time = pgnData.getPGNTagValue(PGNTag.UTC_TIME);
            if (date != null && time != null) {
                try {
                    started = LocalDate.parse(date, PGN_DATE)
                            .atTime(LocalTime.parse(time))
                            .toInstant(ZoneOffset.UTC);
                } catch (RuntimeException exception) {
                    started = null;
                }
            }
        }
        return started == null || started.isAfter(endedAt) ? endedAt : started;
    }

    private static CatalogGame.Opening opening(PGNData pgnData) {
        String eco = pgnData.getPGNTagValue(PGNTag.ECO);
        if (eco == null || !ECO.matcher(eco).matches()) {
            return null;
        }
        String url = pgnData.getPGNTagValue(PGNTag.ECO_URL);
        String name = null;
        if (url != null && url.startsWith(OPENINGS_URL)) {
            String slug = url.substring(OPENINGS_URL.length());
            if (OPENING_SLUG.matcher(slug).matches()) {
                name = slug.replace('-', ' ').trim();
            }
        }
        return new CatalogGame.Opening(eco, name == null || name.isEmpty() ? null : name, null);
    }

    /** Remaining time after each ply in centiseconds, or none unless every ply has one. */
    private static List<Integer> clocks(PGNData pgnData, int plies) {
        var clocks = new ArrayList<Integer>(plies);
        for (int ply = 0; ply < plies; ply++) {
            Integer clock = null;
            List<PGNComment> comments = pgnData.getPGNCommentsAfter(ply);
            if (comments != null) {
                for (PGNComment comment : comments) {
                    Matcher matcher = CLOCK.matcher(comment.getText());
                    if (matcher.find()) {
                        int tenths = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
                        clock = ((Integer.parseInt(matcher.group(1)) * 60
                                + Integer.parseInt(matcher.group(2))) * 60
                                + Integer.parseInt(matcher.group(3))) * 100 + tenths * 10;
                        break;
                    }
                }
            }
            if (clock == null) {
                return List.of();
            }
            clocks.add(clock);
        }
        return clocks;
    }

    private static Player player(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        String username = text(node, "username", 1, 32);
        if (!USERNAME.matcher(username).matches()) {
            throw invalid();
        }
        JsonNode rating = node.get("rating");
        if (rating == null || !rating.isIntegralNumber() || !rating.canConvertToInt()
                || rating.intValue() < 0 || rating.intValue() > 5_000) {
            throw invalid();
        }
        return new Player(username, rating.intValue(), text(node, "result", 1, 32));
    }

    private static String text(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()
                || value.stringValue().length() < min || value.stringValue().length() > max) {
            throw invalid();
        }
        return value.stringValue();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0 || value.longValue() > 32_503_680_000L) {
            throw invalid();
        }
        return value.longValue();
    }

    private static InvalidGameException invalid() {
        return new InvalidGameException(null);
    }

    private record Player(String username, int rating, String result) {}

    private record Finish(CatalogGame.Status status, CatalogGame.Result result) {}

    static final class InvalidGameException extends RuntimeException {

        private InvalidGameException(Throwable cause) {
            super("Invalid chess.com game", cause);
        }
    }
}
