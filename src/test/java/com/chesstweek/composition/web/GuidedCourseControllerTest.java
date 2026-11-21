package com.chesstweek.composition.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.training.GuidedCourses;
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

class GuidedCourseControllerTest {

    private static final String BASE = "/api/training/courses/grandmaster-thinking-method";
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final Cookie SESSION =
            new Cookie("__Host-chess_tweek_session", "sealed-session");

    @Test
    void servesOriginalCourseContentAnonymouslyWithoutSolutions() throws Exception {
        var authorizations = new AtomicInteger();
        FakeCourses courses = new FakeCourses();

        mockMvc(sealed -> {
                    authorizations.incrementAndGet();
                    return connected();
                }, courses)
                .perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.title").value("Grandmaster Thinking Method"))
                .andExpect(jsonPath("$.modules[0].status").value("available"))
                .andExpect(jsonPath("$.modules[0].lessons[0].exercises[0].fen")
                        .value("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1"))
                .andExpect(content().string(not(containsString("acceptedMoves"))))
                .andExpect(content().string(not(containsString("e1e2"))));

        assertThat(authorizations).hasValue(0);
    }

    @Test
    void scopesProgressAndAttemptsToTheAuthorizedAccount() throws Exception {
        FakeCourses courses = new FakeCourses();
        MockMvc mockMvc = mockMvc(sealed -> connected(), courses);

        mockMvc.perform(get(BASE + "/progress").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedLessonKey").value("read-the-last-move"));
        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts")
                        .cookie(SESSION)
                        .header("Origin", ORIGIN))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.attemptId").value(9))
                .andExpect(jsonPath("$.setupMove").value("e2e1"))
                .andExpect(jsonPath("$.ownerMoves").value(2))
                .andExpect(content().string(not(containsString("e1e2"))));
        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts/9/moves")
                        .cookie(SESSION)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e1e2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("solved"))
                .andExpect(jsonPath("$.feedback").value("Correct."))
                .andExpect(jsonPath("$.explanation").value("Checks come first."))
                .andExpect(jsonPath("$.completion.list").value("retiring"));
        assertThat(courses.attemptIds).containsExactly(9L);

        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts/9/hint")
                        .cookie(SESSION)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value(2))
                .andExpect(jsonPath("$.text").value("Use the king."));
        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts/9/reveal")
                        .cookie(SESSION)
                        .header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingMoves[0]").value("e1e2"))
                .andExpect(jsonPath("$.explanation").value("Checks come first."))
                .andExpect(jsonPath("$.completion.list").value("hard_to_solve"));
        mockMvc.perform(get(BASE + "/exercises/answer-the-check/answer").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solutionLine[0]").value("e1e2"));

        assertThat(courses.accountSeen).hasValue("owner-id");
    }

    @Test
    void mapsEndedForeignAndUnresolvedExerciseRequestsToSafeErrors() throws Exception {
        FakeCourses courses = new FakeCourses();
        MockMvc mockMvc = mockMvc(sealed -> connected(), courses);
        courses.moveOutcome = PuzzleTraining.MoveOutcome.NOT_FOUND;
        mockMvc.perform(move("/exercises/answer-the-check/attempts/9/moves", "e1e2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("attempt_not_found"));
        courses.moveOutcome = PuzzleTraining.MoveOutcome.FINISHED;
        mockMvc.perform(move("/exercises/answer-the-check/attempts/9/moves", "e1e2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("attempt_finished"));
        courses.moveOutcome = PuzzleTraining.MoveOutcome.ILLEGAL;
        mockMvc.perform(move("/exercises/answer-the-check/attempts/9/moves", "e1e3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("illegal_training_move"));

        courses.hintOutcome = GuidedCourses.HintOutcome.FINISHED;
        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts/9/hint")
                        .cookie(SESSION).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":1}"))
                .andExpect(status().isConflict());
        courses.revealOutcome = PuzzleTraining.RevealOutcome.NOT_FOUND;
        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts/9/reveal")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNotFound());
        courses.reviewOutcome = GuidedCourses.ReviewOutcome.NOT_RESOLVED;
        mockMvc.perform(get(BASE + "/exercises/answer-the-check/answer").cookie(SESSION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("exercise_not_resolved"))
                .andExpect(content().string(not(containsString("e1e2"))));
        courses.empty = true;
        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("exercise_not_found"));
        mockMvc(sealed -> LichessSessionAuthorization.disconnected(false), courses)
                .perform(get(BASE + "/exercises/answer-the-check/answer").cookie(SESSION))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deniesDisconnectedProgressAndClearsAnInvalidSession() throws Exception {
        FakeCourses courses = new FakeCourses();

        mockMvc(sealed -> LichessSessionAuthorization.disconnected(true), courses)
                .perform(get(BASE + "/progress").cookie(SESSION))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(jsonPath("$.error").value("not_connected"));

        assertThat(courses.accountSeen).hasValue(null);
    }

    @Test
    void rejectsCrossOriginAndMalformedMovesBeforeAuthorization() throws Exception {
        var authorizations = new AtomicInteger();
        FakeCourses courses = new FakeCourses();
        MockMvc mockMvc = mockMvc(sealed -> {
            authorizations.incrementAndGet();
            return connected();
        }, courses);

        for (String path : List.of(
                "/exercises/answer-the-check/attempts",
                "/exercises/answer-the-check/attempts/9/moves",
                "/exercises/answer-the-check/attempts/9/hint",
                "/exercises/answer-the-check/attempts/9/reveal")) {
            mockMvc.perform(post(BASE + path)
                            .cookie(SESSION)
                            .header("Origin", "https://example.com")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"move\":\"e1e2\",\"level\":1}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(move("/exercises/answer-the-check/attempts/9/moves", "../../secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_training_request"));
        mockMvc.perform(move("/exercises/answer-the-check/attempts/0/moves", "e1e2"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(move("/exercises/Bad_Key/attempts/9/moves", "e1e2"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts/9/hint")
                        .cookie(SESSION).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":3}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/exercises/Bad_Key/answer").cookie(SESSION))
                .andExpect(status().isBadRequest());

        assertThat(authorizations).hasValue(0);
        assertThat(courses.attempts).hasValue(0);
    }

    private static org.springframework.test.web.servlet.RequestBuilder move(String path, String uci) {
        return post(BASE + path).cookie(SESSION).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"move\":\"" + uci + "\"}");
    }

    private static MockMvc mockMvc(
            LichessSessionAuthorizer authorizer, GuidedCourses courses) {
        return MockMvcBuilders.standaloneSetup(new GuidedCourseController(authorizer, courses))
                .build();
    }

    private static LichessSessionAuthorization connected() {
        return LichessSessionAuthorization.connected(
                "owner-id", "ChessOwner", "liu_private_token_123");
    }

    private static final class FakeCourses implements GuidedCourses {
        final AtomicReference<String> accountSeen = new AtomicReference<>();
        final AtomicInteger attempts = new AtomicInteger();
        final List<Long> attemptIds = new java.util.ArrayList<>();
        PuzzleTraining.MoveOutcome moveOutcome = PuzzleTraining.MoveOutcome.SOLVED;
        HintOutcome hintOutcome = HintOutcome.SHOWN;
        PuzzleTraining.RevealOutcome revealOutcome = PuzzleTraining.RevealOutcome.REVEALED;
        ReviewOutcome reviewOutcome = ReviewOutcome.REVIEWED;
        boolean empty;

        @Override
        public Course course() {
            return new Course(
                    COURSE_KEY,
                    1,
                    "Grandmaster Thinking Method",
                    "A repeatable way to choose moves with purpose",
                    "Independent course",
                    List.of(new CourseModule(
                            "repeatable-move-routine",
                            "Repeatable Move Routine",
                            "Build one calm sequence.",
                            ModuleStatus.AVAILABLE,
                            List.of(new Lesson(
                                    "read-the-last-move",
                                    "Read the last move",
                                    "Identify what changed.",
                                    10,
                                    List.of("Read the position."),
                                    List.of("Am I in check?"),
                                    List.of(new Exercise(
                                            "answer-the-check",
                                            "4k3/8/8/8/8/8/4r3/4K3 w - - 0 1",
                                            Orientation.WHITE,
                                            DifficultyTier.FOUNDATION,
                                            1600,
                                            "Remove the checking piece.")))))));
        }

        @Override
        public Progress progress(String accountId) {
            accountSeen.set(accountId);
            return progress();
        }

        @Override
        public Optional<Progress> startLesson(String accountId, String lessonKey) {
            accountSeen.set(accountId);
            return Optional.of(progress());
        }

        @Override
        public Optional<PuzzleTraining.Attempt> startExercise(String accountId, String exerciseKey) {
            accountSeen.set(accountId);
            attempts.incrementAndGet();
            return empty ? Optional.empty() : Optional.of(new PuzzleTraining.Attempt(
                    9, 42, "lDWH9", "4k3/8/8/8/8/8/4r3/4K3 w - - 0 1", "e2e1",
                    1600, 2, 11_000, null));
        }

        @Override
        public ExerciseMoveResult exerciseMove(
                String accountId, String exerciseKey, long attemptId, String moveUci) {
            accountSeen.set(accountId);
            attempts.incrementAndGet();
            attemptIds.add(attemptId);
            return moveOutcome == PuzzleTraining.MoveOutcome.SOLVED
                    ? new ExerciseMoveResult(new PuzzleTraining.MoveResult(
                            moveOutcome, null, 0, completion(PuzzleTraining.TrainingList.RETIRING)),
                            "Correct.", "Checks come first.")
                    : new ExerciseMoveResult(
                            new PuzzleTraining.MoveResult(moveOutcome, null, 0, null), null, null);
        }

        @Override
        public HintResult exerciseHint(
                String accountId, String exerciseKey, long attemptId, int level) {
            accountSeen.set(accountId);
            return hintOutcome == HintOutcome.SHOWN
                    ? new HintResult(HintOutcome.SHOWN, level, "Use the king.")
                    : new HintResult(hintOutcome, 0, null);
        }

        @Override
        public ExerciseRevealResult exerciseReveal(
                String accountId, String exerciseKey, long attemptId) {
            accountSeen.set(accountId);
            return revealOutcome == PuzzleTraining.RevealOutcome.REVEALED
                    ? new ExerciseRevealResult(new PuzzleTraining.RevealResult(
                            revealOutcome, List.of("e1e2"),
                            completion(PuzzleTraining.TrainingList.HARD_TO_SOLVE)),
                            "Checks come first.")
                    : new ExerciseRevealResult(
                            new PuzzleTraining.RevealResult(revealOutcome, List.of(), null), null);
        }

        @Override
        public ReviewResult review(String accountId, String exerciseKey) {
            accountSeen.set(accountId);
            return reviewOutcome == ReviewOutcome.REVIEWED
                    ? new ReviewResult(reviewOutcome, List.of("e1e2"), "Checks come first.")
                    : new ReviewResult(reviewOutcome, List.of(), null);
        }

        private static PuzzleTraining.Completion completion(PuzzleTraining.TrainingList list) {
            return new PuzzleTraining.Completion(null, new PuzzleTraining.Performance(list, 0, 0),
                    0, list == PuzzleTraining.TrainingList.HARD_TO_SOLVE, 4_000, 11_000,
                    "https://lichess.org/game/OHeXKNku/black#52", List.of("intermezzo"));
        }

        @Override
        public CompletionResult completeLesson(String accountId, String lessonKey) {
            accountSeen.set(accountId);
            return new CompletionResult(CompletionOutcome.COMPLETED, progress());
        }

        private static Progress progress() {
            return new Progress(
                    COURSE_KEY,
                    1,
                    0,
                    4,
                    "read-the-last-move",
                    Map.of("read-the-last-move", new LessonProgress(
                            LessonState.NOT_STARTED,
                            Map.of("answer-the-check", new ExerciseProgress(
                                    ExerciseState.NOT_STARTED, 0, 0)))));
        }
    }
}
