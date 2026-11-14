package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.gamereview.GameAnalysis;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameAnalysisControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final GameAnalysis.Job RUNNING = new GameAnalysis.Job(
            "abcd1234", GameAnalysis.Perspective.BOTH, GameAnalysis.State.RUNNING,
            GameAnalysis.Pass.QUICK, 24, 41, null, Instant.parse("2026-11-14T12:00:00Z"), null,
            List.of());

    private final FakeAnalysis analysis = new FakeAnalysis();

    @Test
    void startsTheRequestedPerspectiveForTheConnectedAccount() throws Exception {
        analysis.startResult = new GameAnalysis.StartResult(GameAnalysis.StartResult.Outcome.STARTED, RUNNING);

        mockMvc(connected())
                .perform(start("abcd1234", "{\"perspective\":\"both\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.job.gameId").value("abcd1234"))
                .andExpect(jsonPath("$.job.perspective").value("both"))
                .andExpect(jsonPath("$.job.state").value("running"))
                .andExpect(jsonPath("$.job.pass").value("quick"))
                .andExpect(jsonPath("$.job.judged").value(24))
                .andExpect(jsonPath("$.job.total").value(41));

        assertThat(analysis.started).containsExactly("owner-id abcd1234 BOTH");
    }

    @Test
    void mapsEachPerspective() throws Exception {
        analysis.startResult = new GameAnalysis.StartResult(GameAnalysis.StartResult.Outcome.STARTED, RUNNING);
        MockMvc mockMvc = mockMvc(connected());

        mockMvc.perform(start("abcd1234", "{\"perspective\":\"my_moves\"}")).andExpect(status().isAccepted());
        mockMvc.perform(start("abcd1234", "{\"perspective\":\"opponent_moves\"}")).andExpect(status().isAccepted());

        assertThat(analysis.started).containsExactly(
                "owner-id abcd1234 MY_MOVES", "owner-id abcd1234 OPPONENT_MOVES");
    }

    @Test
    void rejectsInvalidRequestsBeforeAuthorizingOrStarting() throws Exception {
        var authorizations = new ArrayList<String>();
        MockMvc mockMvc = mockMvc(sealed -> {
            authorizations.add(sealed);
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        });

        mockMvc.perform(start("abcd1234", "{\"perspective\":\"everything\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_analysis_request"));
        mockMvc.perform(start("abcd1234", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(start("abc", "{\"perspective\":\"both\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(start("abcd1234..", "{\"perspective\":\"both\"}"))
                .andExpect(status().isBadRequest());

        assertThat(authorizations).isEmpty();
        assertThat(analysis.started).isEmpty();
    }

    @Test
    void refusesCrossOriginStartsAndCancelsBeforeAuthorizing() throws Exception {
        var authorizations = new ArrayList<String>();
        MockMvc mockMvc = mockMvc(sealed -> {
            authorizations.add(sealed);
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        });

        mockMvc.perform(post("/api/games/abcd1234/analyses")
                        .cookie(SESSION)
                        .header("Origin", "https://example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perspective\":\"both\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/analysis/cancel").cookie(SESSION))
                .andExpect(status().isForbidden());

        assertThat(authorizations).isEmpty();
        assertThat(analysis.started).isEmpty();
        assertThat(analysis.cancels).isZero();
    }

    @Test
    void deniesDisconnectedOrUnavailableSessionsBeforeTouchingAnalysis() throws Exception {
        MockMvc disconnected = mockMvc(sealed -> LichessSessionAuthorization.disconnected(true));
        disconnected.perform(start("abcd1234", "{\"perspective\":\"both\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        disconnected.perform(get("/api/analysis"))
                .andExpect(status().isUnauthorized());

        mockMvc(sealed -> LichessSessionAuthorization.unavailable())
                .perform(post("/api/analysis/cancel").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("session_unavailable"));

        assertThat(analysis.started).isEmpty();
        assertThat(analysis.reads).isZero();
        assertThat(analysis.cancels).isZero();
    }

    @Test
    void reportsRefusedStartsWithFixedCodes() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        expectRefusal(mockMvc, GameAnalysis.StartResult.Outcome.GAME_NOT_FOUND, 404, "game_not_found");
        expectRefusal(mockMvc, GameAnalysis.StartResult.Outcome.NOT_ELIGIBLE, 409, "analysis_not_supported");
        expectRefusal(mockMvc, GameAnalysis.StartResult.Outcome.BUSY, 409, "analysis_busy");
        expectRefusal(mockMvc, GameAnalysis.StartResult.Outcome.NO_PROFILE, 409, "engine_profile_missing");
    }

    @Test
    void reportsTheCurrentAnalysisOrNone() throws Exception {
        MockMvc mockMvc = mockMvc(connected());

        mockMvc.perform(get("/api/analysis").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job").isEmpty());

        analysis.current = new GameAnalysis.Job(
                "abcd1234", GameAnalysis.Perspective.MY_MOVES, GameAnalysis.State.FAILED,
                GameAnalysis.Pass.QUICK, 3, 20, GameAnalysis.Failure.ENGINE_UNAVAILABLE,
                Instant.parse("2026-11-14T12:00:00Z"), Instant.parse("2026-11-14T12:00:05Z"),
                List.of());
        mockMvc.perform(get("/api/analysis").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.job.state").value("failed"))
                .andExpect(jsonPath("$.job.failure").value("engine_unavailable"))
                .andExpect(jsonPath("$.job.finishedAt").value("2026-11-14T12:00:05Z"));
        assertThat(analysis.readAccounts).containsOnly("owner-id");
    }

    @Test
    void cancelsTheRunningAnalysisOrReportsThatNoneIsRunning() throws Exception {
        MockMvc mockMvc = mockMvc(connected());

        mockMvc.perform(post("/api/analysis/cancel").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_running_analysis"));

        analysis.current = RUNNING;
        mockMvc.perform(post("/api/analysis/cancel").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.state").value("running"));
        assertThat(analysis.cancels).isEqualTo(2);
    }

    private void expectRefusal(
            MockMvc mockMvc, GameAnalysis.StartResult.Outcome outcome, int status, String code)
            throws Exception {
        analysis.startResult = new GameAnalysis.StartResult(outcome, null);
        mockMvc.perform(start("abcd1234", "{\"perspective\":\"my_moves\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error").value(code));
    }

    private static org.springframework.test.web.servlet.RequestBuilder start(String gameId, String body) {
        return post("/api/games/" + gameId + "/analyses")
                .cookie(SESSION)
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockMvc mockMvc(LichessSessionAuthorizer authorizer) {
        return MockMvcBuilders
                .standaloneSetup(new GameAnalysisController(authorizer, analysis))
                .build();
    }

    private static LichessSessionAuthorizer connected() {
        return sealed -> LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
    }

    private static final class FakeAnalysis implements GameAnalysis {

        final List<String> started = new ArrayList<>();
        final List<String> readAccounts = new ArrayList<>();
        StartResult startResult;
        Job current;
        int reads;
        int cancels;

        @Override
        public StartResult start(String accountId, String gameId, Perspective perspective) {
            started.add(accountId + " " + gameId + " " + perspective);
            return startResult;
        }

        @Override
        public Optional<Job> current(String accountId) {
            reads++;
            readAccounts.add(accountId);
            return Optional.ofNullable(current);
        }

        @Override
        public Optional<Job> cancel(String accountId) {
            cancels++;
            return Optional.ofNullable(current);
        }
    }
}
