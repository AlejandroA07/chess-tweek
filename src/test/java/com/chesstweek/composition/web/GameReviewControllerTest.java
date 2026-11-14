package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameReviews;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameReviewControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");

    private final List<String> requests = new ArrayList<>();
    private GameReviews.GameReview review;
    private RuntimeException failure;

    @Test
    void returnsTheReviewForTheConnectedAccountAndRequestedSide() throws Exception {
        review = review();

        mockMvc(connected())
                .perform(get("/api/games/game0001/review").queryParam("side", "opponent_moves").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.game.id").value("game0001"))
                .andExpect(jsonPath("$.game.analysis.status").value("analyzed"))
                .andExpect(jsonPath("$.game.analysis.myMoves.blunders").value(2))
                .andExpect(jsonPath("$.initialFen").value("start-fen"))
                .andExpect(jsonPath("$.plies[0].san").value("e4"))
                .andExpect(jsonPath("$.phases.source").value("inferred"))
                .andExpect(jsonPath("$.myMoves.completedAt").value("2026-11-14T12:00:00Z"))
                .andExpect(jsonPath("$.opponentMoves").isEmpty())
                .andExpect(jsonPath("$.selected.side").value("my_moves"))
                .andExpect(jsonPath("$.selected.statistics.averageLoss").value(4.5))
                .andExpect(jsonPath("$.selected.statistics.byPhase.opening.moves").value(1))
                .andExpect(jsonPath("$.selected.evaluations[0].whiteExpected").value(1_050))
                .andExpect(jsonPath("$.selected.moves[0].bestMoveSan").value("d4"))
                .andExpect(jsonPath("$.selected.moves[0].before[0].pvSan[0]").value("d4"))
                .andExpect(content().string(not(containsString("e4 e5"))));

        assertThat(requests).containsExactly("owner-id game0001 OPPONENT_MOVES");
    }

    @Test
    void choosesTheDefaultSideWhenNoneIsRequested() throws Exception {
        review = review();

        mockMvc(connected()).perform(get("/api/games/game0001/review").cookie(SESSION))
                .andExpect(status().isOk());

        assertThat(requests).containsExactly("owner-id game0001 null");
    }

    @Test
    void rejectsInvalidRequestsBeforeAuthorizing() throws Exception {
        var authorizations = new ArrayList<String>();
        MockMvc mockMvc = mockMvc(sealed -> {
            authorizations.add(sealed);
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        });

        mockMvc.perform(get("/api/games/game0001/review").queryParam("side", "both").cookie(SESSION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_review_request"));
        mockMvc.perform(get("/api/games/short/review").cookie(SESSION))
                .andExpect(status().isBadRequest());

        assertThat(authorizations).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void deniesDisconnectedOrUnavailableSessionsBeforeReading() throws Exception {
        mockMvc(sealed -> LichessSessionAuthorization.disconnected(true))
                .perform(get("/api/games/game0001/review").cookie(SESSION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mockMvc(sealed -> LichessSessionAuthorization.unavailable())
                .perform(get("/api/games/game0001/review").cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("session_unavailable"));

        assertThat(requests).isEmpty();
    }

    @Test
    void reportsMissingGamesAndStorageFailuresWithFixedCodes() throws Exception {
        mockMvc(connected()).perform(get("/api/games/game0001/review").cookie(SESSION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("game_not_found"));

        failure = new DataAccessResourceFailureException("private database detail");
        mockMvc(connected()).perform(get("/api/games/game0001/review").cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("review_unavailable"))
                .andExpect(content().string(not(containsString("private database detail"))));
    }

    private MockMvc mockMvc(LichessSessionAuthorizer authorizer) {
        GameReviews reviews = (accountId, gameId, side) -> {
            requests.add(accountId + " " + gameId + " " + side);
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(review);
        };
        return MockMvcBuilders.standaloneSetup(new GameReviewController(authorizer, reviews)).build();
    }

    private static LichessSessionAuthorizer connected() {
        return sealed -> LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
    }

    private static GameReviews.GameReview review() {
        var line = new GameReviews.Line("d2d4", "d4", 20, null, 50, 900, 50, List.of("d2d4"), List.of("d4"));
        var move = new GameReviews.JudgedMove(1, "e4", "e2e4", "start-fen", "opening", "quick", "none", "none",
                false, "d2d4", "d4", 1_050, 960, 90, "none", List.of(line), List.of(line));
        var statistics = new GameReviews.Statistics(1, 0, 0, 0, 4.5, 30.0, Map.of(
                "opening", new GameReviews.PhaseStatistics(1, 0, 0, 0, 4.5)));
        return new GameReviews.GameReview(
                game(),
                "start-fen",
                List.of(new GameReviews.Ply(1, "white", "e4", "e2e4", "after-fen")),
                new GameReviews.Phases("inferred", 20, null),
                new GameReviews.VersionInfo(3, Instant.parse("2026-11-14T12:00:00Z"), true, 1, 0, 1, 2),
                null,
                new GameReviews.SideReview(GameReviews.Side.MY_MOVES, 3, "white", "Stockfish 19", 880_000,
                        3_540_000, Instant.parse("2026-11-14T12:00:00Z"), true, statistics,
                        List.of(new GameReviews.PositionEvaluation(0, 1_050, 20, null)), List.of(move)));
    }

    private static CatalogGame game() {
        return new CatalogGame(
                "game0001",
                Instant.parse("2026-11-13T10:00:00Z"),
                Instant.parse("2026-11-13T10:05:00Z"),
                CatalogGame.Status.RESIGN,
                CatalogGame.Result.WIN,
                CatalogGame.Color.WHITE,
                1500,
                null,
                new CatalogGame.Opponent(CatalogGame.OpponentKind.USER, "rival", "Rival", 1480, null, null),
                CatalogGame.Speed.BLITZ,
                true,
                CatalogGame.Variant.STANDARD,
                new CatalogGame.TimeControl(300, 0, null),
                null,
                null,
                "e4 e5",
                null,
                List.of(),
                true);
    }
}
