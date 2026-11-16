package com.chesstweek.gamereview;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChessComGameParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OWNER = "OwnerName";
    private static final String MOVES =
            "1. e4 {[%clk 0:09:58.5]} 1... c6 {[%clk 0:09:59.1]} 2. d4 {[%clk 0:09:55]} "
                    + "2... d5 {[%clk 0:09:57.6]} 3. e5 {[%clk 0:09:50]} 3... Bf5 {[%clk 0:09:40]} 0-1";

    private final ChessComGameParser parser = new ChessComGameParser();

    @Test
    void mapsALiveRapidLossWithClocksAndOpening() {
        CatalogGame game = parser.parse(OWNER, game(Map.of()));

        assertThat(game.ref()).isEqualTo(new GameRef(GameRef.Source.CHESSCOM, "145200000001"));
        assertThat(game.playedAt()).isEqualTo(Instant.parse("2026-11-10T18:03:21Z"));
        assertThat(game.lastMoveAt()).isEqualTo(Instant.ofEpochSecond(1_794_336_902L));
        assertThat(game.status()).isEqualTo(CatalogGame.Status.RESIGN);
        assertThat(game.ownerResult()).isEqualTo(CatalogGame.Result.LOSS);
        assertThat(game.ownerColor()).isEqualTo(CatalogGame.Color.WHITE);
        assertThat(game.ownerRating()).isEqualTo(1302);
        assertThat(game.ownerRatingChange()).isNull();
        assertThat(game.opponent()).isEqualTo(new CatalogGame.Opponent(
                CatalogGame.OpponentKind.USER, "rival_player", "Rival_Player", 1310, null, null));
        assertThat(game.speed()).isEqualTo(CatalogGame.Speed.RAPID);
        assertThat(game.rated()).isTrue();
        assertThat(game.variant()).isEqualTo(CatalogGame.Variant.STANDARD);
        assertThat(game.timeControl()).isEqualTo(new CatalogGame.TimeControl(600, 5, null));
        assertThat(game.opening()).isEqualTo(new CatalogGame.Opening(
                "B12", "Caro Kann Defense Advance Variation 3...Bf5", null));
        assertThat(game.phaseDivision()).isNull();
        assertThat(game.movesSan()).isEqualTo("e4 c6 d4 d5 e5 Bf5");
        assertThat(game.clocks()).containsExactly(59_850, 59_910, 59_500, 59_760, 59_000, 58_000);
        assertThat(game.pgn()).contains("[Link \"https://www.chess.com/game/live/145200000001\"]");
        assertThat(game.analysisEligible()).isTrue();
    }

    @Test
    void findsTheOwnerOnEitherSideIgnoringLetterCase() {
        CatalogGame game = parser.parse("rival_player", game(Map.of()));

        assertThat(game.ownerColor()).isEqualTo(CatalogGame.Color.BLACK);
        assertThat(game.ownerResult()).isEqualTo(CatalogGame.Result.WIN);
        assertThat(game.ownerRating()).isEqualTo(1310);
        assertThat(game.opponent().name()).isEqualTo("OwnerName");
    }

    @Test
    void mapsFinishesAndDrawsOntoTheCatalogStatuses() {
        assertThat(finish("win", "checkmated")).containsExactly(
                CatalogGame.Status.MATE, CatalogGame.Result.WIN);
        assertThat(finish("timeout", "win")).containsExactly(
                CatalogGame.Status.OUT_OF_TIME, CatalogGame.Result.LOSS);
        assertThat(finish("abandoned", "win")).containsExactly(
                CatalogGame.Status.TIMEOUT, CatalogGame.Result.LOSS);
        assertThat(finish("stalemate", "stalemate")).containsExactly(
                CatalogGame.Status.STALEMATE, CatalogGame.Result.DRAW);
        for (String draw : List.of("agreed", "repetition", "insufficient", "50move",
                "timevsinsufficient")) {
            assertThat(finish(draw, draw)).containsExactly(
                    CatalogGame.Status.DRAW, CatalogGame.Result.DRAW);
        }
    }

    @Test
    void readsDailyGamesAsCorrespondenceWithDaysPerTurn() {
        CatalogGame game = parser.parse(OWNER, game(Map.of(
                "url", "https://www.chess.com/game/daily/812345678",
                "time_class", "daily",
                "time_control", "1/259200",
                "start_time", 1_794_000_000L)));

        assertThat(game.ref()).isEqualTo(new GameRef(GameRef.Source.CHESSCOM, "812345678"));
        assertThat(game.speed()).isEqualTo(CatalogGame.Speed.CORRESPONDENCE);
        assertThat(game.timeControl()).isEqualTo(new CatalogGame.TimeControl(null, null, 3));
        assertThat(game.playedAt()).isEqualTo(Instant.ofEpochSecond(1_794_000_000L));
    }

    @Test
    void keepsTheMovesWithoutClocksWhenAnyClockIsMissing() {
        CatalogGame game = parser.parse(OWNER, game(Map.of("moves",
                "1. e4 {[%clk 0:09:58.5]} 1... c6 2. d4 d5 0-1")));

        assertThat(game.movesSan()).isEqualTo("e4 c6 d4 d5");
        assertThat(game.clocks()).isEmpty();
    }

    @Test
    void rejectsGamesItCannotStoreFaithfully() {
        for (Map<String, Object> change : List.<Map<String, Object>>of(
                Map.of("rules", "chess960"),
                Map.of("rules", "bughouse"),
                Map.of("url", "https://evil.example/game/live/1"),
                Map.of("url", "https://www.chess.com/game/live/12ab"),
                Map.of("time_class", "hyperbullet"),
                Map.of("time_control", "600+abc"),
                Map.of("rated", "yes"),
                Map.of("white_username", "someone_else"),
                Map.of("white_result", "win", "black_result", "win"),
                Map.of("white_result", "agreed", "black_result", "repetition"),
                Map.of("white_result", "conceded"),
                Map.of("white_rating", 9_000),
                Map.of("moves", "1. e4 e5 2. Ke3 Qh4 0-1"),
                Map.of("moves", "0-1"),
                Map.of("setup", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKB1R w KQkq - 0 1"),
                Map.of("pgn", "x".repeat(600_000)))) {
            assertThatThrownBy(() -> parser.parse(OWNER, game(change)))
                    .as("change %s", change)
                    .isInstanceOf(ChessComGameParser.InvalidGameException.class);
        }
        assertThatThrownBy(() -> parser.parse(OWNER, JSON.readTree("[]")))
                .isInstanceOf(ChessComGameParser.InvalidGameException.class);
    }

    @Test
    void leavesAnUnknownOrMalformedOpeningOut() {
        assertThat(parser.parse(OWNER, game(Map.of("eco", "Z99"))).opening()).isNull();
        assertThat(parser.parse(OWNER, game(Map.of("eco_url", "https://evil.example/openings/X")))
                .opening()).isEqualTo(new CatalogGame.Opening("B12", null, null));
    }

    private List<Object> finish(String whiteResult, String blackResult) {
        CatalogGame game = parser.parse(OWNER, game(Map.of(
                "white_result", whiteResult, "black_result", blackResult)));
        return List.of(game.status(), game.ownerResult());
    }

    /** A chess.com monthly-archive game; {@code change} overrides individual fields. */
    static JsonNode game(Map<String, Object> change) {
        String url = (String) change.getOrDefault(
                "url", "https://www.chess.com/game/live/145200000001");
        String moves = (String) change.getOrDefault("moves", MOVES);
        String setup = (String) change.get("setup");
        String pgn = (String) change.getOrDefault("pgn", """
                [Event "Live Chess"]
                [Site "Chess.com"]
                [Date "2026.11.10"]
                [White "OwnerName"]
                [Black "Rival_Player"]
                [Result "0-1"]
                %s[ECO "%s"]
                [ECOUrl "%s"]
                [UTCDate "2026.11.10"]
                [UTCTime "18:03:21"]
                [WhiteElo "1302"]
                [BlackElo "1310"]
                [TimeControl "600+5"]
                [Termination "Rival_Player won by resignation"]
                [Link "%s"]

                %s
                """.formatted(
                        setup == null ? "" : "[SetUp \"1\"]\n[FEN \"" + setup + "\"]\n",
                        change.getOrDefault("eco", "B12"),
                        change.getOrDefault("eco_url",
                                "https://www.chess.com/openings/Caro-Kann-Defense-Advance-Variation-3...Bf5"),
                        url,
                        moves));
        var node = JSON.createObjectNode();
        node.put("url", url);
        node.put("pgn", pgn);
        node.put("time_control", (String) change.getOrDefault("time_control", "600+5"));
        node.put("end_time", ((Number) change.getOrDefault("end_time", 1_794_336_902L)).longValue());
        Object rated = change.getOrDefault("rated", true);
        if (rated instanceof Boolean value) {
            node.put("rated", value);
        } else {
            node.put("rated", rated.toString());
        }
        node.put("time_class", (String) change.getOrDefault("time_class", "rapid"));
        node.put("rules", (String) change.getOrDefault("rules", "chess"));
        if (change.containsKey("start_time")) {
            node.put("start_time", (Long) change.get("start_time"));
        }
        var white = node.putObject("white");
        white.put("username", (String) change.getOrDefault("white_username", "OwnerName"));
        white.put("rating", (Integer) change.getOrDefault("white_rating", 1302));
        white.put("result", (String) change.getOrDefault("white_result", "resigned"));
        white.put("@id", "https://api.chess.com/pub/player/ownername");
        var black = node.putObject("black");
        black.put("username", "Rival_Player");
        black.put("rating", 1310);
        black.put("result", (String) change.getOrDefault("black_result", "win"));
        black.put("@id", "https://api.chess.com/pub/player/rival_player");
        return node;
    }
}
