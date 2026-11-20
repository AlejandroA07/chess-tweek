package com.chesstweek.composition.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.PuzzleTraining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PuzzleTrainingControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final String BASE = "/api/training/puzzles";

    private final FakeTraining training = new FakeTraining();

    @Test
    void startsRandomAndCategoryAttemptsWithoutRevealingTheSolution() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        mockMvc.perform(post(BASE + "/random/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.attemptId").value(7))
                .andExpect(jsonPath("$.positionFen").value(FakeTraining.FEN))
                .andExpect(jsonPath("$.setupMove").value("g3f3"))
                .andExpect(jsonPath("$.ownerMoves").value(2))
                .andExpect(jsonPath("$.fastSolveLimitMillis").value(11_000))
                .andExpect(jsonPath("$.currentList").doesNotExist())
                .andExpect(content().string(not(containsString("b2g7"))));
        assertThat(training.accounts).containsExactly("owner-id");

        mockMvc.perform(post(BASE + "/categories/opening-variation/Caro-Kann_Defense_Advance_Variation/attempts")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentList").value("wrong_moves"));
        assertThat(training.categories).containsExactly(new PuzzleLibrary.Category(
                PuzzleLibrary.CategoryKind.OPENING_VARIATION, "Caro-Kann_Defense_Advance_Variation"));

        training.empty = true;
        mockMvc.perform(post(BASE + "/random/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no_puzzle_available"));
    }

    @Test
    void mapsMoveHintAndRevealOutcomesToSafeResponses() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        mockMvc.perform(move("/attempts/7/moves", "b2g7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("correct"))
                .andExpect(jsonPath("$.opponentReply").value("g8g7"))
                .andExpect(jsonPath("$.completion").doesNotExist());

        training.moveOutcome = PuzzleTraining.MoveOutcome.SOLVED;
        mockMvc.perform(move("/attempts/7/moves", "g2f3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("solved"))
                .andExpect(jsonPath("$.completion.previousList").doesNotExist())
                .andExpect(jsonPath("$.completion.list").value("retiring"))
                .andExpect(jsonPath("$.completion.elapsedMillis").value(4_000))
                .andExpect(jsonPath("$.completion.themes[0]").value("intermezzo"));

        for (var expected : List.of(
                new Object[] {PuzzleTraining.MoveOutcome.ILLEGAL, 400, "illegal_training_move"},
                new Object[] {PuzzleTraining.MoveOutcome.NOT_FOUND, 404, "attempt_not_found"},
                new Object[] {PuzzleTraining.MoveOutcome.FINISHED, 409, "attempt_finished"})) {
            training.moveOutcome = (PuzzleTraining.MoveOutcome) expected[0];
            mockMvc.perform(move("/attempts/7/moves", "a1a2"))
                    .andExpect(status().is((int) expected[1]))
                    .andExpect(jsonPath("$.error").value(expected[2]));
        }

        mockMvc.perform(post(BASE + "/attempts/7/hint").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromSquare").value("b2"));
        mockMvc.perform(post(BASE + "/attempts/7/reveal").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingMoves[0]").value("b2g7"))
                .andExpect(jsonPath("$.completion.list").value("hard_to_solve"))
                .andExpect(jsonPath("$.completion.hintUsed").value(true));

        training.finished = true;
        mockMvc.perform(post(BASE + "/attempts/7/hint").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("attempt_finished"));
        mockMvc.perform(post(BASE + "/attempts/7/reveal").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsInvalidInputBeforeAuthorizingOrActing() throws Exception {
        var authorizations = new AtomicInteger();
        MockMvc mockMvc = mockMvc(sealed -> {
            authorizations.incrementAndGet();
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        });
        mockMvc.perform(move("/attempts/7/moves", "b2g9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_training_request"));
        mockMvc.perform(post(BASE + "/attempts/7/moves").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isBadRequest());
        mockMvc.perform(move("/attempts/0/moves", "b2g7"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/categories/rating/fork/attempts")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/categories/theme/fork%20x/attempts")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isBadRequest());
        assertThat(authorizations).hasValue(0);
        assertThat(training.calls).hasValue(0);
    }

    @Test
    void refusesCrossOriginRequestsBeforeAuthorizingOrActing() throws Exception {
        var authorizations = new AtomicInteger();
        MockMvc mockMvc = mockMvc(sealed -> {
            authorizations.incrementAndGet();
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        });
        for (String path : List.of(
                "/random/attempts", "/categories/theme/fork/attempts",
                "/attempts/7/moves", "/attempts/7/hint", "/attempts/7/reveal")) {
            mockMvc.perform(post(BASE + path).cookie(SESSION).header("Origin", "https://example.com")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"move\":\"b2g7\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(BASE + path).cookie(SESSION)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"move\":\"b2g7\"}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(authorizations).hasValue(0);
        assertThat(training.calls).hasValue(0);
    }

    @Test
    void deniesDisconnectedAndUnavailableSessionsBeforeActing() throws Exception {
        mockMvc(sealed -> LichessSessionAuthorization.disconnected(true))
                .perform(post(BASE + "/random/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mockMvc(sealed -> LichessSessionAuthorization.unavailable())
                .perform(move("/attempts/7/moves", "b2g7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("session_unavailable"));
        assertThat(training.calls).hasValue(0);

        training.failure = new IllegalArgumentException("Account ID is invalid");
        mockMvc(connected())
                .perform(post(BASE + "/attempts/7/hint").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("training_unavailable"));
    }

    private static org.springframework.test.web.servlet.RequestBuilder move(String path, String uci) {
        return post(BASE + path).cookie(SESSION).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"move\":\"" + uci + "\"}");
    }

    private MockMvc mockMvc(LichessSessionAuthorizer authorizer) {
        return MockMvcBuilders.standaloneSetup(new PuzzleTrainingController(authorizer, training))
                .build();
    }

    private static LichessSessionAuthorizer connected() {
        return sealed -> LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
    }

    private static final class FakeTraining implements PuzzleTraining {
        static final String FEN = "5rk1/5pb1/B1p4p/2p5/8/5q1P/PQ4P1/R5K1 w - - 0 27";

        final AtomicInteger calls = new AtomicInteger();
        final List<String> accounts = new ArrayList<>();
        final List<PuzzleLibrary.Category> categories = new ArrayList<>();
        boolean empty;
        boolean finished;
        RuntimeException failure;
        MoveOutcome moveOutcome = MoveOutcome.CORRECT;

        @Override
        public Optional<Attempt> startRandom(String accountId) {
            act(accountId);
            return empty ? Optional.empty() : Optional.of(attempt(null));
        }

        @Override
        public Optional<Attempt> startInCategory(String accountId, PuzzleLibrary.Category category) {
            act(accountId);
            categories.add(category);
            return Optional.of(attempt(TrainingList.WRONG_MOVES));
        }

        @Override
        public MoveResult move(String accountId, long attemptId, String moveUci) {
            act(accountId);
            return switch (moveOutcome) {
                case CORRECT -> new MoveResult(MoveOutcome.CORRECT, "g8g7", 0, null);
                case SOLVED -> new MoveResult(MoveOutcome.SOLVED, null, 0,
                        completion(TrainingList.RETIRING, false));
                default -> new MoveResult(moveOutcome, null, 0, null);
            };
        }

        @Override
        public HintResult hint(String accountId, long attemptId) {
            act(accountId);
            return finished
                    ? new HintResult(HintOutcome.FINISHED, null)
                    : new HintResult(HintOutcome.SHOWN, "b2");
        }

        @Override
        public RevealResult reveal(String accountId, long attemptId) {
            act(accountId);
            return finished
                    ? new RevealResult(RevealOutcome.FINISHED, List.of(), null)
                    : new RevealResult(RevealOutcome.REVEALED, List.of("b2g7", "g8g7", "g2f3"),
                            completion(TrainingList.HARD_TO_SOLVE, true));
        }

        @Override
        public Optional<Performance> performance(String accountId, long puzzleId) {
            act(accountId);
            return Optional.empty();
        }

        private void act(String accountId) {
            calls.incrementAndGet();
            accounts.add(accountId);
            if (failure != null) {
                throw failure;
            }
        }

        private static Attempt attempt(TrainingList list) {
            return new Attempt(7, 42, "lDWH9", FEN, "g3f3", 1536, 2, 11_000, list);
        }

        private static Completion completion(TrainingList list, boolean hint) {
            return new Completion(null, new Performance(list, 0, 0), 0, hint, 4_000, 11_000,
                    "https://lichess.org/game/OHeXKNku/black#52", List.of("intermezzo", "short"));
        }
    }
}
