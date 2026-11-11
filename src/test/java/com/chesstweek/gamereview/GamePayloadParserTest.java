package com.chesstweek.gamereview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
