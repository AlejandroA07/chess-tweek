package com.chesstweek.composition.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.training.PatternLadders;
import com.chesstweek.training.PuzzleTraining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatternLadderControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final String BASE = "/api/training/puzzles/ladders";
    private static final String FEN = "5rk1/5pb1/B1p4p/2p5/8/5RqP/PQ4P1/R5K1 b - - 0 26";

    private final FakeLadders ladders = new FakeLadders();

    @Test
    void listsPatternsAndOneLadderWithItsPackagesForTheConnectedOwner() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        mockMvc.perform(get(BASE).cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.patterns[0].key").value("operaMate"))
                .andExpect(jsonPath("$.patterns[0].name").value("Opera mate"))
                .andExpect(jsonPath("$.patterns[0].group").value("Mate themes"))
                .andExpect(jsonPath("$.patterns[0].puzzles").value(31_000));

        mockMvc.perform(get(BASE + "/operaMate").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.pattern.key").value("operaMate"))
                .andExpect(jsonPath("$.packages.length()").value(6))
                .andExpect(jsonPath("$.packages[0].key").value("up-to-1000"))
                .andExpect(jsonPath("$.packages[0].low").value(0))
                .andExpect(jsonPath("$.packages[0].high").value(1000))
                .andExpect(jsonPath("$.packages[0].progress").doesNotExist())
                .andExpect(jsonPath("$.packages[1].key").value("1000-1400"))
                .andExpect(jsonPath("$.packages[1].puzzles").value(6_140))
                .andExpect(jsonPath("$.packages[1].progress.presented").value(34))
                .andExpect(jsonPath("$.packages[1].progress.lastRating").value(1187))
                .andExpect(jsonPath("$.packages[5].key").value("2600-plus"));
        assertThat(ladders.accounts).containsExactly("owner-id");

        ladders.missing = true;
        mockMvc.perform(get(BASE + "/mateIn1").cookie(SESSION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("pattern_not_found"));
    }

    @Test
    void startsTheNextPuzzleWithoutRevealingTheSolutionAndRestartsAPackage() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        mockMvc.perform(post(BASE + "/operaMate/1000-1400/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.attempt.attemptId").value(7))
                .andExpect(jsonPath("$.attempt.rating").value(1187))
                .andExpect(jsonPath("$.progress.presented").value(35))
                .andExpect(jsonPath("$.progress.lastRating").value(1187))
                .andExpect(content().string(not(containsString("b2g7"))));
        assertThat(ladders.packages).containsExactly(PatternLadders.RatingPackage.FROM_1000);

        ladders.outcome = PatternLadders.NextOutcome.COMPLETE;
        mockMvc.perform(post(BASE + "/operaMate/1000-1400/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ladder_complete"));
        ladders.outcome = PatternLadders.NextOutcome.NOT_FOUND;
        mockMvc.perform(post(BASE + "/mateIn1/1000-1400/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("pattern_not_found"));

        mockMvc.perform(post(BASE + "/operaMate/2600-plus/restart").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNoContent());
        assertThat(ladders.packages).last().isEqualTo(PatternLadders.RatingPackage.FROM_2600);
        ladders.missing = true;
        mockMvc.perform(post(BASE + "/mateIn1/2600-plus/restart").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("pattern_not_found"));
    }

    @Test
    void rejectsInvalidInputBeforeAuthorizingOrActing() throws Exception {
        var authorizations = new AtomicInteger();
        MockMvc mockMvc = mockMvc(counting(authorizations));
        for (String path : List.of(
                "/operaMate/1000-1500/attempts", "/operaMate/UP-TO-1000/attempts",
                "/opera%20mate/1000-1400/attempts", "/operaMate/1000-1400%27/restart")) {
            mockMvc.perform(post(BASE + path).cookie(SESSION).header("Origin", ORIGIN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_training_request"));
        }
        mockMvc.perform(get(BASE + "/opera%20mate").cookie(SESSION))
                .andExpect(status().isBadRequest());
        assertThat(authorizations).hasValue(0);
        assertThat(ladders.calls).hasValue(0);
    }

    @Test
    void refusesCrossOriginWritesAndDisconnectedSessionsBeforeActing() throws Exception {
        var authorizations = new AtomicInteger();
        MockMvc mockMvc = mockMvc(counting(authorizations));
        for (String path : List.of("/operaMate/1000-1400/attempts", "/operaMate/1000-1400/restart")) {
            mockMvc.perform(post(BASE + path).cookie(SESSION).header("Origin", "https://example.com"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(BASE + path).cookie(SESSION))
                    .andExpect(status().isForbidden());
        }
        assertThat(authorizations).hasValue(0);

        MockMvc disconnected = mockMvc(sealed -> LichessSessionAuthorization.disconnected(false));
        disconnected.perform(get(BASE).cookie(SESSION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"));
        disconnected.perform(get(BASE + "/operaMate").cookie(SESSION))
                .andExpect(status().isUnauthorized());
        disconnected.perform(post(BASE + "/operaMate/1000-1400/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized());
        assertThat(ladders.calls).hasValue(0);

        ladders.failure = new IllegalStateException("storage");
        mockMvc(connected()).perform(post(BASE + "/operaMate/1000-1400/attempts")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("training_unavailable"));
    }

    private MockMvc mockMvc(LichessSessionAuthorizer authorizer) {
        return MockMvcBuilders.standaloneSetup(new PatternLadderController(authorizer, ladders)).build();
    }

    private static LichessSessionAuthorizer connected() {
        return sealed -> LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
    }

    private static LichessSessionAuthorizer counting(AtomicInteger authorizations) {
        return sealed -> {
            authorizations.incrementAndGet();
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        };
    }

    private static final class FakeLadders implements PatternLadders {

        private static final Pattern OPERA = new Pattern("operaMate", "Opera mate", "Mate themes", 31_000);

        final List<String> accounts = new ArrayList<>();
        final List<RatingPackage> packages = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        NextOutcome outcome = NextOutcome.STARTED;
        boolean missing;
        RuntimeException failure;

        @Override
        public List<Pattern> patterns() {
            calls.incrementAndGet();
            return List.of(OPERA);
        }

        @Override
        public Optional<Ladder> ladder(String accountId, String patternKey) {
            act(accountId);
            if (missing) {
                return Optional.empty();
            }
            List<PackageEntry> entries = new ArrayList<>();
            for (RatingPackage value : RatingPackage.values()) {
                entries.add(new PackageEntry(value, value == RatingPackage.FROM_1000 ? 6_140 : 10,
                        value == RatingPackage.FROM_1000 ? new Progress(34, 1187) : null));
            }
            return Optional.of(new Ladder(OPERA, entries));
        }

        @Override
        public Next next(String accountId, String patternKey, RatingPackage ratingPackage) {
            act(accountId);
            packages.add(ratingPackage);
            if (outcome != NextOutcome.STARTED) {
                return new Next(outcome, null, null);
            }
            return new Next(NextOutcome.STARTED,
                    new PuzzleTraining.Attempt(7, 11, "L0001", FEN, "g3f3", 1187, 2, 11_000, null),
                    new Progress(35, 1187));
        }

        @Override
        public boolean restart(String accountId, String patternKey, RatingPackage ratingPackage) {
            act(accountId);
            packages.add(ratingPackage);
            return !missing;
        }

        private void act(String accountId) {
            calls.incrementAndGet();
            accounts.add(accountId);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
