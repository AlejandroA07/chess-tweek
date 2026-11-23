package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.training.OwnPuzzleTraining;
import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.PuzzleTraining.TrainingList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OwnPuzzleTrainingControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final String BASE = "/api/training/own-puzzles";
    private static final String FEN =
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3";

    private final FakeTraining training = new FakeTraining();

    @Test
    void reportsTheOverviewAndPagesWithoutSolutions() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        mockMvc.perform(get(BASE).cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.newPuzzles").value(4))
                .andExpect(jsonPath("$.discarded").value(1))
                .andExpect(jsonPath("$.lists[3].list").value("retiring"))
                .andExpect(jsonPath("$.lists[3].puzzles").value(2))
                .andExpect(jsonPath("$.groups.total").value(6))
                .andExpect(jsonPath("$.groups.openings[0].key").value("Indian Game"))
                .andExpect(jsonPath("$.groups.phases[0].key").value("endgame"))
                .andExpect(jsonPath("$.groups.tactics[0].key").value("fork"));

        mockMvc.perform(get(BASE + "/new").param("offset", "10").param("limit", "20").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.puzzles[0].id").value(41))
                .andExpect(jsonPath("$.puzzles[0].label").value("blunder"))
                .andExpect(jsonPath("$.puzzles[0].openingFamily").value("Indian Game"))
                .andExpect(jsonPath("$.puzzles[0].tactics[0].theme").value("fork"))
                .andExpect(jsonPath("$.puzzles[0].sourceGame.source").value("lichess"))
                .andExpect(jsonPath("$.puzzles[0].sourceGame.gameId").value("abcd1234"))
                .andExpect(jsonPath("$.puzzles[0].sourceGame.ply").value(47))
                .andExpect(jsonPath("$.puzzles[0].gamePlayedAt").value("2026-11-20T18:00:00Z"))
                .andExpect(content().string(not(containsString("f1b5"))));
        assertThat(training.pages).containsExactly("new 10 20");
        mockMvc.perform(get(BASE + "/discarded").cookie(SESSION))
                .andExpect(status().isOk());
        assertThat(training.pages).containsExactly("new 10 20", "discarded 0 20");

        for (String[] invalid : new String[][] {{"-1", "20"}, {"0", "0"}, {"0", "51"}}) {
            mockMvc.perform(get(BASE + "/new").param("offset", invalid[0])
                            .param("limit", invalid[1]).cookie(SESSION))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_training_request"));
        }
        assertThat(training.pages).hasSize(2);
    }

    @Test
    void discardsAndRestoresWithFixedOutcomes() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        mockMvc.perform(post(BASE + "/41/discard").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(post(BASE + "/41/restore").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNoContent());
        assertThat(training.changes).containsExactly("discard 41", "restore 41");

        training.change = OwnPuzzleTraining.ChangeOutcome.CLASSIFIED;
        mockMvc.perform(post(BASE + "/41/discard").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("own_puzzle_classified"));
        training.change = OwnPuzzleTraining.ChangeOutcome.NOT_DISCARDED;
        mockMvc.perform(post(BASE + "/41/restore").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("own_puzzle_not_discarded"));
        training.change = OwnPuzzleTraining.ChangeOutcome.NOT_FOUND;
        mockMvc.perform(post(BASE + "/41/discard").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("own_puzzle_not_found"));
        mockMvc.perform(post(BASE + "/0/discard").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startsNewAttemptsAndSolvesWithoutRevealingTheSolution() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        mockMvc.perform(post(BASE + "/new/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.attemptId").value(9))
                .andExpect(jsonPath("$.positionFen").value(FEN))
                .andExpect(jsonPath("$.previousMove").value("b8c6"))
                .andExpect(jsonPath("$.ownerMoves").value(2))
                .andExpect(jsonPath("$.fastSolveLimitMillis").value(11_000))
                .andExpect(jsonPath("$.currentList").doesNotExist())
                .andExpect(jsonPath("$.puzzle.id").value(41))
                .andExpect(content().string(not(containsString("f1b5"))));
        mockMvc.perform(post(BASE + "/41/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE + "/attempts/9/moves").cookie(SESSION).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"move\":\"b5a4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("solved"))
                .andExpect(jsonPath("$.completion.list").value("retiring"))
                .andExpect(jsonPath("$.completion.previousList").doesNotExist())
                .andExpect(jsonPath("$.completion.sourceGame.ply").value(47));
        mockMvc.perform(post(BASE + "/attempts/9/hint").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromSquare").value("f1"));
        mockMvc.perform(post(BASE + "/attempts/9/reveal").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingMoves[0]").value("f1b5"))
                .andExpect(jsonPath("$.completion.list").value("hard_to_solve"));

        training.empty = true;
        mockMvc.perform(post(BASE + "/new/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no_new_own_puzzles"));
        mockMvc.perform(post(BASE + "/41/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("own_puzzle_not_found"));
        training.moveOutcome = PuzzleTraining.MoveOutcome.FINISHED;
        mockMvc.perform(post(BASE + "/attempts/9/moves").cookie(SESSION).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"move\":\"b5a4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("attempt_finished"));
        training.moveOutcome = PuzzleTraining.MoveOutcome.ILLEGAL;
        mockMvc.perform(post(BASE + "/attempts/9/moves").cookie(SESSION).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"move\":\"b5a4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("illegal_training_move"));
        mockMvc.perform(post(BASE + "/attempts/9/moves").cookie(SESSION).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"move\":\"castle\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_training_request"));
    }

    @Test
    void startsRepetitionAndMasteredSessionsByGroup() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        mockMvc.perform(get(BASE + "/repetition").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lists[0].list").value("hard_to_solve"));
        mockMvc.perform(get(BASE + "/lists/retiring").param("limit", "5").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.puzzles[0].id").value(41));
        mockMvc.perform(get(BASE + "/lists/mastered").cookie(SESSION))
                .andExpect(status().isOk());
        assertThat(training.pages).containsExactly("list RETIRING 0 5", "list MASTERED 0 20");
        mockMvc.perform(get(BASE + "/lists/lost").cookie(SESSION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_training_request"));
        mockMvc.perform(get(BASE + "/repetition/mastered").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.phases[0].key").value("endgame"));

        mockMvc.perform(post(BASE + "/repetition/retiring/sessions").cookie(SESSION)
                        .header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(5))
                .andExpect(jsonPath("$.list").value("retiring"));
        mockMvc.perform(post(BASE + "/repetition/mastered/opening/Indian Game/sessions")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isCreated());
        mockMvc.perform(post(BASE + "/repetition/mastered/tactic/fork/sessions")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isCreated());
        assertThat(training.sessions).containsExactly(
                "list RETIRING", "mastered OPENING Indian Game", "mastered TACTIC fork");

        for (String invalid : new String[] {
                "/repetition/mastered/sessions", "/repetition/lost/sessions",
                "/repetition/mastered/colour/white/sessions"}) {
            mockMvc.perform(post(BASE + invalid).cookie(SESSION).header("Origin", ORIGIN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_training_request"));
        }

        mockMvc.perform(post(BASE + "/sessions/5/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attempt.attemptId").value(9))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.total").value(2));
        training.sessionOutcome = PuzzleTraining.SessionOutcome.COMPLETE;
        mockMvc.perform(post(BASE + "/sessions/5/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("session_complete"));
    }

    @Test
    void refusesCrossOriginWritesAndDisconnectedSessionsBeforeTouchingTraining() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        for (String path : new String[] {
                "/41/discard", "/41/restore", "/new/attempts", "/41/attempts",
                "/attempts/9/hint", "/attempts/9/reveal", "/repetition/retiring/sessions",
                "/sessions/5/attempts"}) {
            mockMvc.perform(post(BASE + path).cookie(SESSION).header("Origin", "https://evil.example"))
                    .andExpect(status().isForbidden());
        }
        MockMvc disconnected = mockMvc(sealed -> LichessSessionAuthorization.disconnected(true));
        disconnected.perform(get(BASE).cookie(SESSION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        disconnected.perform(post(BASE + "/new/attempts").cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized());
        assertThat(training.calls).isZero();

        training.failure = true;
        mockMvc.perform(get(BASE).cookie(SESSION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("training_unavailable"));
    }

    private MockMvc mockMvc(LichessSessionAuthorizer authorizer) {
        return MockMvcBuilders.standaloneSetup(new OwnPuzzleTrainingController(authorizer, training))
                .build();
    }

    private static LichessSessionAuthorizer connected() {
        return sealed -> LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
    }

    private static final class FakeTraining implements OwnPuzzleTraining {

        static final SourceGame GAME = new SourceGame("lichess", "abcd1234", 47);
        static final OwnPuzzle PUZZLE = new OwnPuzzle(41, "blunder", "middlegame", "Indian Game",
                "Indian Game: London System", List.of(new Tactic("fork", "missed")), GAME,
                Instant.parse("2026-11-20T18:00:00Z"));
        static final Groups GROUPS = new Groups(6,
                List.of(new GroupCount("Indian Game", 6)),
                List.of(new GroupCount("endgame", 4), new GroupCount("middlegame", 2)),
                List.of(new GroupCount("fork", 2)));

        final List<String> pages = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        final List<String> sessions = new ArrayList<>();
        int calls;
        boolean empty;
        boolean failure;
        ChangeOutcome change = ChangeOutcome.DONE;
        PuzzleTraining.MoveOutcome moveOutcome = PuzzleTraining.MoveOutcome.SOLVED;
        PuzzleTraining.SessionOutcome sessionOutcome = PuzzleTraining.SessionOutcome.STARTED;

        @Override
        public Overview overview(String accountId) {
            touch(accountId);
            if (failure) {
                throw new IllegalStateException("storage");
            }
            return new Overview(4, 1, lists(), GROUPS);
        }

        @Override
        public Page newPuzzles(String accountId, int offset, int limit) {
            touch(accountId);
            pages.add("new " + offset + " " + limit);
            return new Page(4, List.of(PUZZLE));
        }

        @Override
        public Page discarded(String accountId, int offset, int limit) {
            touch(accountId);
            pages.add("discarded " + offset + " " + limit);
            return new Page(1, List.of(PUZZLE));
        }

        @Override
        public Page inList(String accountId, TrainingList list, int offset, int limit) {
            touch(accountId);
            pages.add("list " + list + " " + offset + " " + limit);
            return new Page(2, List.of(PUZZLE));
        }

        @Override
        public List<PuzzleTraining.ListCount> listCounts(String accountId) {
            touch(accountId);
            return lists();
        }

        @Override
        public ChangeOutcome discard(String accountId, long ownPuzzleId) {
            touch(accountId);
            changes.add("discard " + ownPuzzleId);
            return change;
        }

        @Override
        public ChangeOutcome restore(String accountId, long ownPuzzleId) {
            touch(accountId);
            changes.add("restore " + ownPuzzleId);
            return change;
        }

        @Override
        public Optional<Attempt> startNext(String accountId) {
            touch(accountId);
            return empty ? Optional.empty() : Optional.of(attempt());
        }

        @Override
        public Optional<Attempt> start(String accountId, long ownPuzzleId) {
            touch(accountId);
            return empty ? Optional.empty() : Optional.of(attempt());
        }

        @Override
        public MoveResult move(String accountId, long attemptId, String moveUci) {
            touch(accountId);
            return new MoveResult(moveOutcome, null, 0, moveOutcome == PuzzleTraining.MoveOutcome.SOLVED
                    ? completion(TrainingList.RETIRING) : null);
        }

        @Override
        public PuzzleTraining.HintResult hint(String accountId, long attemptId) {
            touch(accountId);
            return new PuzzleTraining.HintResult(PuzzleTraining.HintOutcome.SHOWN, "f1");
        }

        @Override
        public RevealResult reveal(String accountId, long attemptId) {
            touch(accountId);
            return new RevealResult(PuzzleTraining.RevealOutcome.REVEALED,
                    List.of("f1b5", "a7a6", "b5a4"), completion(TrainingList.HARD_TO_SOLVE));
        }

        @Override
        public Optional<PuzzleTraining.Performance> performance(String accountId, long ownPuzzleId) {
            touch(accountId);
            return Optional.empty();
        }

        @Override
        public Groups mastered(String accountId) {
            touch(accountId);
            return GROUPS;
        }

        @Override
        public Optional<PuzzleTraining.Session> startSession(String accountId, TrainingList list) {
            touch(accountId);
            sessions.add("list " + list);
            return Optional.of(new PuzzleTraining.Session(5, list, 2));
        }

        @Override
        public Optional<PuzzleTraining.Session> startMasteredSession(
                String accountId, GroupKind kind, String key) {
            touch(accountId);
            sessions.add("mastered " + kind + " " + key);
            return Optional.of(new PuzzleTraining.Session(6, TrainingList.MASTERED, 1));
        }

        @Override
        public SessionAttempt nextInSession(String accountId, long sessionId) {
            touch(accountId);
            return sessionOutcome == PuzzleTraining.SessionOutcome.STARTED
                    ? new SessionAttempt(sessionOutcome, attempt(), 1, 2)
                    : new SessionAttempt(sessionOutcome, null, 0, 2);
        }

        private void touch(String accountId) {
            assertThat(accountId).isEqualTo("owner-id");
            calls++;
        }

        private static Attempt attempt() {
            return new Attempt(9, PUZZLE, FEN, "b8c6", 2, 11_000, null);
        }

        private static Completion completion(TrainingList list) {
            return new Completion(null, new PuzzleTraining.Performance(list, 0, 0), 0, false,
                    4_000, 11_000, GAME);
        }

        private static List<PuzzleTraining.ListCount> lists() {
            List<PuzzleTraining.ListCount> lists = new ArrayList<>();
            for (TrainingList list : TrainingList.values()) {
                lists.add(new PuzzleTraining.ListCount(list, list == TrainingList.RETIRING ? 2 : 0));
            }
            return lists;
        }
    }
}
