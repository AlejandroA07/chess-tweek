package com.chesstweek.gamereview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GamePayloadParserTest {

    private final GamePayloadParser parser = new GamePayloadParser();

    @Test
    void retainsLichessAiContext() {
        CatalogGame game = parser.parse("ownerid", exportedGame(
                "{\"aiLevel\":5,\"rating\":2000}"));

        assertThat(game.opponent().kind()).isEqualTo(CatalogGame.OpponentKind.AI);
        assertThat(game.opponent().name()).isEqualTo("Lichess AI");
        assertThat(game.opponent().aiLevel()).isEqualTo(5);
    }

    @Test
    void assignsASafeDisplayNameToAnAnonymousOpponent() {
        CatalogGame game = parser.parse("ownerid", exportedGame("{\"rating\":1500}"));

        assertThat(game.opponent().kind()).isEqualTo(CatalogGame.OpponentKind.ANONYMOUS);
        assertThat(game.opponent().name()).isEqualTo("Anonymous");
        assertThat(game.opponent().rating()).isEqualTo(1500);
    }

    @Test
    void retainsTheOwnersLichessRatingChangeForARatedGame() {
        CatalogGame game = parser.parse("ownerid", ratedGame("""
                {"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800,"ratingDiff":-7}
                """, """
                {"user":{"id":"opponent","name":"Opponent"},"rating":1750,"ratingDiff":7}
                """));

        assertThat(game.ownerRating()).isEqualTo(1800);
        assertThat(game.ownerRatingChange()).isEqualTo(-7);
    }

    @Test
    void retainsAnUnchangedRatingAsZeroRatherThanMissing() {
        CatalogGame game = parser.parse("ownerid", ratedGame("""
                {"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800,"ratingDiff":0}
                """, """
                {"user":{"id":"opponent","name":"Opponent"},"rating":1750,"ratingDiff":0}
                """));

        assertThat(game.ownerRatingChange()).isZero();
    }

    @Test
    void leavesTheRatingChangeEmptyWhenLichessDoesNotSupplyOne() {
        CatalogGame casual = parser.parse("ownerid", exportedGame("{\"rating\":1500}"));
        CatalogGame ratedWithoutChange = parser.parse("ownerid", ratedGame("""
                {"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800}
                """, """
                {"user":{"id":"opponent","name":"Opponent"},"rating":1750}
                """));

        assertThat(casual.ownerRatingChange()).isNull();
        assertThat(ratedWithoutChange.ownerRatingChange()).isNull();
    }

    @Test
    void ignoresTheOpponentsRatingChange() {
        CatalogGame game = parser.parse("ownerid", ratedGame("""
                {"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800}
                """, """
                {"user":{"id":"opponent","name":"Opponent"},"rating":1750,"ratingDiff":9}
                """));

        assertThat(game.ownerRatingChange()).isNull();
    }

    @Test
    void doesNotRejectAGameForTheOpponentsRatingChange() {
        CatalogGame game = parser.parse("ownerid", ratedGame("""
                {"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800,"ratingDiff":3}
                """, """
                {"user":{"id":"opponent","name":"Opponent"},"rating":1750,"ratingDiff":"odd"}
                """));
        CatalogGame ownerIsBlack = parser.parse("ownerid", ratedGame("""
                {"user":{"id":"opponent","name":"Opponent"},"rating":1750,"ratingDiff":5000}
                """, """
                {"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800,"ratingDiff":-3}
                """));

        assertThat(game.ownerRatingChange()).isEqualTo(3);
        assertThat(ownerIsBlack.ownerColor()).isEqualTo(CatalogGame.Color.BLACK);
        assertThat(ownerIsBlack.ownerRatingChange()).isEqualTo(-3);
    }

    @Test
    void rejectsAnInvalidOwnerRatingChange() {
        for (String ratingDiff : new String[] {"1001", "-1001", "7.5", "\"7\"", "true"}) {
            ExportedGame invalid = ratedGame("""
                    {"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800,"ratingDiff":%s}
                    """.formatted(ratingDiff), """
                    {"user":{"id":"opponent","name":"Opponent"},"rating":1750}
                    """);

            assertThatThrownBy(() -> parser.parse("ownerid", invalid))
                    .as("ratingDiff %s", ratingDiff)
                    .isInstanceOf(GamePayloadParser.GamePayloadException.class);
        }
    }

    @Test
    void ignoresARatingChangeOnACasualGameInsteadOfDroppingTheGame() {
        ExportedGame casualWithChange = new ExportedGame("game0001", "mate", """
                {
                  "id":"game0001",
                  "rated":false,
                  "variant":"standard",
                  "speed":"blitz",
                  "createdAt":1794355200000,
                  "lastMoveAt":1794355500000,
                  "status":"mate",
                  "winner":"white",
                  "players":{
                    "white":{"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800,"ratingDiff":"odd"},
                    "black":{"user":{"id":"opponent","name":"Opponent"},"rating":1750}
                  },
                  "moves":"e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#"
                }
                """);

        CatalogGame game = parser.parse("ownerid", casualWithChange);

        assertThat(game.rated()).isFalse();
        assertThat(game.ownerRatingChange()).isNull();
    }

    private static ExportedGame ratedGame(String white, String black) {
        String payload = """
                {
                  "id":"game0001",
                  "rated":true,
                  "variant":"standard",
                  "speed":"blitz",
                  "createdAt":1794355200000,
                  "lastMoveAt":1794355500000,
                  "status":"mate",
                  "winner":"white",
                  "players":{"white":%s,"black":%s},
                  "moves":"e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#"
                }
                """.formatted(white.strip(), black.strip());
        return new ExportedGame("game0001", "mate", payload);
    }

    private static ExportedGame exportedGame(String opponent) {
        String payload = """
                {
                  "id":"game0001",
                  "rated":false,
                  "variant":"standard",
                  "speed":"blitz",
                  "createdAt":1794355200000,
                  "lastMoveAt":1794355500000,
                  "status":"mate",
                  "winner":"white",
                  "players":{
                    "white":{"user":{"id":"ownerid","name":"ChessOwner"},"rating":1800},
                    "black":%s
                  },
                  "moves":"e4 e5 Qh5 Nc6 Bc4 Nf6 Qxf7#"
                }
                """.formatted(opponent);
        return new ExportedGame("game0001", "mate", payload);
    }
}
