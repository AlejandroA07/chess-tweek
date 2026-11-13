package com.chesstweek.composition.web;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.gamereview.EngineHealth;
import com.chesstweek.gamereview.EngineStatus;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EngineControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final String ORIGIN = "http://127.0.0.1:8080";

    private final AtomicInteger checks = new AtomicInteger();
    private final AtomicInteger statusReads = new AtomicInteger();

    @Test
    void reportsTheLocatedEngineWithoutStartingIt() throws Exception {
        mockMvc(connected(), new EngineStatus(
                        EngineStatus.State.NOT_CHECKED, "/opt/homebrew/bin/stockfish", null))
                .perform(get("/api/engine").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("not_checked"))
                .andExpect(jsonPath("$.path").value("/opt/homebrew/bin/stockfish"));

        assertThat(statusReads).hasValue(1);
        assertThat(checks).hasValue(0);
    }

    @Test
    void runsAHealthCheckForASameOriginConnectedRequest() throws Exception {
        mockMvc(connected(), new EngineStatus(
                        EngineStatus.State.READY, "/opt/homebrew/bin/stockfish", "Stockfish 17.1"))
                .perform(post("/api/engine/health-checks").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.engineName").value("Stockfish 17.1"));

        assertThat(checks).hasValue(1);
    }

    @Test
    void refusesCrossOriginHealthChecksBeforeAuthorizingOrStarting() throws Exception {
        var authorizations = new AtomicInteger();
        LichessSessionAuthorizer authorizer = sealed -> {
            authorizations.incrementAndGet();
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        };
        MockMvc mockMvc = mockMvc(authorizer, ready());

        mockMvc.perform(post("/api/engine/health-checks")
                        .cookie(SESSION)
                        .header("Origin", "https://example.com"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/engine/health-checks").cookie(SESSION))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/engine/health-checks")
                        .cookie(SESSION)
                        .header("Origin", ORIGIN)
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());

        assertThat(authorizations).hasValue(0);
        assertThat(checks).hasValue(0);
    }

    @Test
    void deniesDisconnectedCallersBeforeTouchingTheEngine() throws Exception {
        MockMvc mockMvc = mockMvc(
                sealed -> LichessSessionAuthorization.disconnected(true), ready());

        mockMvc.perform(get("/api/engine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"));
        mockMvc.perform(post("/api/engine/health-checks").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        assertThat(statusReads).hasValue(0);
        assertThat(checks).hasValue(0);
    }

    @Test
    void reportsAnUnavailableSessionBeforeTouchingTheEngine() throws Exception {
        mockMvc(sealed -> LichessSessionAuthorization.unavailable(), ready())
                .perform(post("/api/engine/health-checks").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("session_unavailable"));

        assertThat(checks).hasValue(0);
    }

    @Test
    void reportsABusyEngineAsAConflict() throws Exception {
        mockMvc(connected(), new EngineStatus(EngineStatus.State.BUSY, "/opt/homebrew/bin/stockfish", null))
                .perform(post("/api/engine/health-checks").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("engine_busy"));
    }

    private MockMvc mockMvc(LichessSessionAuthorizer authorizer, EngineStatus result) {
        EngineHealth health = new EngineHealth() {
            @Override
            public EngineStatus status() {
                statusReads.incrementAndGet();
                return result;
            }

            @Override
            public EngineStatus check() {
                checks.incrementAndGet();
                return result;
            }
        };
        return MockMvcBuilders.standaloneSetup(new EngineController(authorizer, health)).build();
    }

    private static LichessSessionAuthorizer connected() {
        return sealed -> LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
    }

    private static EngineStatus ready() {
        return new EngineStatus(EngineStatus.State.READY, "/opt/homebrew/bin/stockfish", "Stockfish 17.1");
    }
}
