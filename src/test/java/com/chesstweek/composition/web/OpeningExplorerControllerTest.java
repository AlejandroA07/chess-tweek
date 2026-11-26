package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameRef;
import com.chesstweek.gamereview.MyGamesExplorer;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningExplorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpeningExplorerControllerTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final Cookie SESSION = new Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final LichessSessionAuthorizer CONNECTED = sealed ->
            LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_private_token_123");

    @Test
    void returnsMastersStatisticsWithoutExposingTheToken() throws Exception {
        var tokenSeen = new AtomicReference<String>();
        var explorer = new FakeExplorer() {
            @Override
            public Answer masters(String accessToken, ExplorerPosition position) {
                tokenSeen.set(accessToken);
                return Answer.success(statistics());
            }
        };

        mvc(CONNECTED, explorer, noGames())
                .perform(get("/api/explorer/masters").param("fen", START).cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.opening.name").value("Start"))
                .andExpect(jsonPath("$.results.white").value(10))
                .andExpect(jsonPath("$.moves[0].san").value("e4"))
                .andExpect(jsonPath("$.moves[0].results.black").value(4))
                .andExpect(jsonPath("$.topGames[0].white.title").value("GM"))
                .andExpect(jsonPath("$.fetchedAt").value("2026-11-26T12:00:00Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(content().string(not(containsString("liu_private_token_123"))));

        assertThat(tokenSeen).hasValue("liu_private_token_123");
    }

    @Test
    void passesTheChosenSpeedsAndRatingGroups() throws Exception {
        var seen = new AtomicReference<String>();
        var explorer = new FakeExplorer() {
            @Override
            public Answer lichess(
                    String accessToken, ExplorerPosition position, Set<Speed> speeds, Set<RatingGroup> ratings) {
                seen.set(speeds + " " + ratings);
                return Answer.success(statistics());
            }
        };

        mvc(CONNECTED, explorer, noGames())
                .perform(get("/api/explorer/lichess")
                        .param("fen", START)
                        .param("speeds", "rapid,blitz")
                        .param("ratings", "1600,1800")
                        .cookie(SESSION))
                .andExpect(status().isOk());

        assertThat(seen).hasValue("[BLITZ, RAPID] [R1600, R1800]");
    }

    @Test
    void rejectsInvalidPositionsAndFiltersBeforeCheckingTheSession() throws Exception {
        var sessionChecks = new AtomicInteger();
        LichessSessionAuthorizer counting = sealed -> {
            sessionChecks.incrementAndGet();
            return CONNECTED.authorize(sealed);
        };
        MockMvc mvc = mvc(counting, new FakeExplorer(), noGames());

        for (var request : List.of(
                get("/api/explorer/masters"),
                get("/api/explorer/masters").param("fen", "8/8/8/8/8/8/8/8 w - - 0 1"),
                get("/api/explorer/masters").param("fen", START + "; DROP"),
                get("/api/explorer/lichess").param("fen", START).param("speeds", "rapid,rapid"),
                get("/api/explorer/lichess").param("fen", START).param("speeds", "fast"),
                get("/api/explorer/lichess").param("fen", START).param("ratings", "1500"),
                get("/api/explorer/lichess").param("fen", START).param("ratings", "1600,"),
                get("/api/explorer/my-games").param("fen", START).param("color", "green"),
                get("/api/explorer/my-games").param("fen", START).param("speed", "fast"),
                get("/api/explorer/my-games").param("fen", START).param("source", "fics"))) {
            mvc.perform(request.cookie(SESSION))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_explorer_request"));
        }
        assertThat(sessionChecks).hasValue(0);
    }

    @Test
    void requiresAConnectedSession() throws Exception {
        LichessSessionAuthorizer disconnected = sealed -> LichessSessionAuthorization.disconnected(true);
        var explorerCalls = new AtomicInteger();
        var explorer = new FakeExplorer() {
            @Override
            public Answer masters(String accessToken, ExplorerPosition position) {
                explorerCalls.incrementAndGet();
                return super.masters(accessToken, position);
            }
        };
        MockMvc mvc = mvc(disconnected, explorer, noGames());

        for (String path : List.of("/api/explorer/masters", "/api/explorer/lichess", "/api/explorer/my-games")) {
            mvc.perform(get(path).param("fen", START))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("not_connected"))
                    .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        }
        assertThat(explorerCalls).hasValue(0);
    }

    @Test
    void mapsLichessFailuresToSafeErrors() throws Exception {
        var answer = new AtomicReference<OpeningExplorer.Answer>();
        var explorer = new FakeExplorer() {
            @Override
            public Answer masters(String accessToken, ExplorerPosition position) {
                return answer.get();
            }
        };
        MockMvc mvc = mvc(CONNECTED, explorer, noGames());

        answer.set(OpeningExplorer.Answer.failed(OpeningExplorer.Answer.Status.RATE_LIMITED, 75L));
        mvc.perform(get("/api/explorer/masters").param("fen", START).cookie(SESSION))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "75"))
                .andExpect(jsonPath("$.error").value("lichess_rate_limited"));
        answer.set(OpeningExplorer.Answer.failed(OpeningExplorer.Answer.Status.UNAVAILABLE, null));
        mvc.perform(get("/api/explorer/masters").param("fen", START).cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("lichess_unavailable"));
        answer.set(OpeningExplorer.Answer.failed(OpeningExplorer.Answer.Status.INVALID_RESPONSE, null));
        mvc.perform(get("/api/explorer/masters").param("fen", START).cookie(SESSION))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("invalid_lichess_response"));
        answer.set(OpeningExplorer.Answer.failed(OpeningExplorer.Answer.Status.UNAUTHORIZED, null));
        mvc.perform(get("/api/explorer/masters").param("fen", START).cookie(SESSION))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("lichess_explorer_refused"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void myGamesStaysAvailableWhenTheLichessExplorerIsLimited() throws Exception {
        var explorer = new FakeExplorer() {
            @Override
            public Answer masters(String accessToken, ExplorerPosition position) {
                return Answer.failed(Answer.Status.RATE_LIMITED, 60L);
            }
        };
        var seen = new AtomicReference<String>();
        MyGamesExplorer myGames = (accountId, position, filter) -> {
            seen.set(accountId + " " + filter);
            return new MyGamesExplorer.PositionStatistics(
                    new MyGamesExplorer.Results(2, 1, 0),
                    List.of(new MyGamesExplorer.NextMove("d2d4", "d4", new MyGamesExplorer.Results(2, 1, 0))),
                    List.of(new MyGamesExplorer.RecentGame(
                            GameRef.lichess("game0001"),
                            Instant.parse("2026-11-20T10:00:00Z"),
                            CatalogGame.Color.WHITE,
                            CatalogGame.Result.WIN,
                            "Rival",
                            1500,
                            CatalogGame.Speed.RAPID,
                            "d4")));
        };
        MockMvc mvc = mvc(CONNECTED, explorer, myGames);

        mvc.perform(get("/api/explorer/masters").param("fen", START).cookie(SESSION))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/explorer/my-games")
                        .param("fen", START)
                        .param("color", "white")
                        .param("speed", "rapid")
                        .param("source", "lichess")
                        .cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.white").value(2))
                .andExpect(jsonPath("$.moves[0].san").value("d4"))
                .andExpect(jsonPath("$.recentGames[0].source").value("lichess"))
                .andExpect(jsonPath("$.recentGames[0].id").value("game0001"))
                .andExpect(jsonPath("$.recentGames[0].ownerResult").value("win"))
                .andExpect(jsonPath("$.recentGames[0].nextSan").value("d4"));

        assertThat(seen).hasValue("owner-id Filter[ownerColor=WHITE, speed=RAPID, source=LICHESS]");
    }

    @Test
    void reportsMyGamesStorageFailureSafely() throws Exception {
        MyGamesExplorer failing = (accountId, position, filter) -> {
            throw new DataAccessResourceFailureException("disk /Users/private/path");
        };

        mvc(CONNECTED, new FakeExplorer(), failing)
                .perform(get("/api/explorer/my-games").param("fen", START).cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("my_games_unavailable"))
                .andExpect(content().string(not(containsString("/Users"))));
    }

    private static MockMvc mvc(
            LichessSessionAuthorizer authorizer, OpeningExplorer explorer, MyGamesExplorer myGames) {
        return MockMvcBuilders
                .standaloneSetup(new OpeningExplorerController(authorizer, explorer, myGames))
                .build();
    }

    private static MyGamesExplorer noGames() {
        return (accountId, position, filter) -> new MyGamesExplorer.PositionStatistics(
                new MyGamesExplorer.Results(0, 0, 0), List.of(), List.of());
    }

    private static OpeningExplorer.Statistics statistics() {
        return new OpeningExplorer.Statistics(
                new OpeningExplorer.Opening("A00", "Start"),
                new OpeningExplorer.Results(10, 5, 8),
                List.of(new OpeningExplorer.NextMove(
                        "e2e4", "e4", 2400, new OpeningExplorer.Results(6, 2, 4), null)),
                List.of(new OpeningExplorer.TopGame(
                        "abcdefgh",
                        "e2e4",
                        new OpeningExplorer.Player("White Player", 2700, "GM"),
                        new OpeningExplorer.Player("Black Player", 2650, null),
                        "white",
                        2020)),
                Instant.parse("2026-11-26T12:00:00Z"),
                false);
    }

    private static class FakeExplorer implements OpeningExplorer {

        @Override
        public Answer masters(String accessToken, ExplorerPosition position) {
            return Answer.success(statistics());
        }

        @Override
        public Answer lichess(
                String accessToken, ExplorerPosition position, Set<Speed> speeds, Set<RatingGroup> ratings) {
            return Answer.success(statistics());
        }
    }
}
