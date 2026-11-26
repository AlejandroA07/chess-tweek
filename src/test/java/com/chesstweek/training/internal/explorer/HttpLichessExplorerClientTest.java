package com.chesstweek.training.internal.explorer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningExplorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpLichessExplorerClientTest {

    private static final ExplorerPosition AFTER_E4 = ExplorerPosition.parse(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1").orElseThrow();
    private static final String MASTERS_ANSWER = """
            {
              "white": 1200, "draws": 1500, "black": 900,
              "moves": [
                {"uci": "c7c5", "san": "c5", "averageRating": 2410,
                 "white": 500, "draws": 600, "black": 400, "game": null,
                 "opening": {"eco": "B20", "name": "Sicilian Defense"}},
                {"uci": "c7c6", "san": "c6", "averageRating": 2420,
                 "white": 100, "draws": 150, "black": 90, "game": {"id": "abcdEFGH"},
                 "opening": null}
              ],
              "topGames": [
                {"uci": "c7c5", "id": "abcdEFGH", "winner": "black",
                 "white": {"name": "Some Master", "rating": 2700},
                 "black": {"name": "Other Master", "rating": 2750},
                 "year": 2019, "month": "2019-04"}
              ],
              "opening": {"eco": "B00", "name": "King's Pawn Game"}
            }
            """;

    @Test
    void asksMastersWithTheTokenAndKeepsOnlyValidatedFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://explorer.lichess.org/masters?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer liu_private_token_123"))
                .andExpect(queryParam("fen", "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR%20b%20KQkq%20-%200%201"))
                .andExpect(queryParam("moves", "12"))
                .andExpect(queryParam("topGames", "15"))
                .andRespond(withSuccess(MASTERS_ANSWER, MediaType.APPLICATION_JSON));

        ExplorerAnswer answer = client(builder).masters("liu_private_token_123", AFTER_E4);

        assertThat(answer.results()).isEqualTo(new OpeningExplorer.Results(1200, 1500, 900));
        assertThat(answer.opening()).isEqualTo(new OpeningExplorer.Opening("B00", "King's Pawn Game"));
        assertThat(answer.moves()).extracting(OpeningExplorer.NextMove::san).containsExactly("c5", "c6");
        assertThat(answer.moves().getFirst().averageRating()).isEqualTo(2410);
        assertThat(answer.moves().get(1).opening()).isNull();
        assertThat(answer.topGames()).singleElement().satisfies(game -> {
            assertThat(game.id()).isEqualTo("abcdEFGH");
            assertThat(game.winner()).isEqualTo("black");
            assertThat(game.white().name()).isEqualTo("Some Master");
            assertThat(game.year()).isEqualTo(2019);
        });
        upstream.verify();
    }

    @Test
    void asksTheLichessDatabaseWithSortedSpeedsAndRatingGroups() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://explorer.lichess.org/lichess?")))
                .andExpect(queryParam("variant", "standard"))
                .andExpect(queryParam("speeds", "blitz,rapid"))
                .andExpect(queryParam("ratings", "1400,1600,1800"))
                .andExpect(queryParam("topGames", "4"))
                .andExpect(queryParam("recentGames", "0"))
                .andRespond(withSuccess("""
                        {"white": 0, "draws": 0, "black": 0, "moves": [], "topGames": [],
                         "recentGames": [], "opening": null}
                        """, MediaType.APPLICATION_JSON));

        ExplorerAnswer answer = client(builder).lichess(
                "token",
                AFTER_E4,
                EnumSet.of(OpeningExplorer.Speed.RAPID, OpeningExplorer.Speed.BLITZ),
                EnumSet.of(
                        OpeningExplorer.RatingGroup.R1800,
                        OpeningExplorer.RatingGroup.R1400,
                        OpeningExplorer.RatingGroup.R1600));

        assertThat(answer.moves()).isEmpty();
        assertThat(answer.opening()).isNull();
        upstream.verify();
    }

    @Test
    void omitsFiltersThatAreNotChosen() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(request -> {
                    assertThat(request.getURI().getQuery()).doesNotContain("speeds", "ratings");
                })
                .andRespond(withSuccess("""
                        {"white": 1, "draws": 0, "black": 0, "moves": [], "topGames": [], "opening": null}
                        """, MediaType.APPLICATION_JSON));

        client(builder).lichess("token", AFTER_E4, Set.of(), Set.of());

        upstream.verify();
    }

    @Test
    void reportsRateLimitsWithTheirWait() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "75");
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith("https://explorer.lichess.org/masters?")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(() -> client(builder).masters("token", AFTER_E4))
                .isInstanceOfSatisfying(ExplorerClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(ExplorerClientException.Reason.RATE_LIMITED);
                    assertThat(exception.retryAfterSeconds()).isEqualTo(75);
                });
    }

    @Test
    void reportsRefusalsAndUpstreamFailuresWithoutTheToken() {
        assertFailure(withStatus(HttpStatus.UNAUTHORIZED), ExplorerClientException.Reason.UNAUTHORIZED);
        assertFailure(withStatus(HttpStatus.BAD_GATEWAY), ExplorerClientException.Reason.UNAVAILABLE);
        assertFailure(
                withSuccess("<html></html>", MediaType.TEXT_HTML),
                ExplorerClientException.Reason.INVALID_RESPONSE);
    }

    @Test
    void rejectsAnswersWithInvalidOrOversizedContent() {
        String[] invalid = {
            "[]",
            "{\"white\": -1, \"draws\": 0, \"black\": 0, \"moves\": [], \"topGames\": [], \"opening\": null}",
            "{\"white\": 1, \"draws\": 0, \"black\": 0, \"topGames\": [], \"opening\": null}",
            MASTERS_ANSWER.replace("\"c7c5\", \"san\"", "\"c7c5; drop\", \"san\""),
            MASTERS_ANSWER.replace("\"san\": \"c5\"", "\"san\": \"<script>\""),
            MASTERS_ANSWER.replace("\"B20\"", "\"Z99\""),
            MASTERS_ANSWER.replace("Some Master", "Some\\u0007Master"),
            MASTERS_ANSWER.replace("\"winner\": \"black\"", "\"winner\": \"nobody\""),
            MASTERS_ANSWER.replace("\"id\": \"abcdEFGH\", \"winner\"", "\"id\": \"../../x\", \"winner\""),
            "{\"white\": 1, \"draws\": 0, \"black\": 0, \"moves\": [], \"topGames\": [], \"opening\": null, \"pad\": \""
                    + "x".repeat(300 * 1024) + "\"}",
        };
        for (String body : invalid) {
            assertFailure(
                    withSuccess(body, MediaType.APPLICATION_JSON),
                    ExplorerClientException.Reason.INVALID_RESPONSE);
        }
    }

    private static void assertFailure(
            org.springframework.test.web.client.ResponseCreator response,
            ExplorerClientException.Reason reason) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer upstream = MockRestServiceServer.bindTo(builder).build();
        upstream.expect(requestTo(org.hamcrest.Matchers.startsWith("https://explorer.lichess.org/masters?")))
                .andRespond(response);
        assertThatThrownBy(() -> client(builder).masters("liu_private_token_123", AFTER_E4))
                .isInstanceOfSatisfying(ExplorerClientException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(reason);
                    assertThat(exception.getMessage()).doesNotContain("liu_private_token_123");
                });
    }

    private static HttpLichessExplorerClient client(RestClient.Builder builder) {
        return new HttpLichessExplorerClient(
                builder.build(), Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC));
    }
}
