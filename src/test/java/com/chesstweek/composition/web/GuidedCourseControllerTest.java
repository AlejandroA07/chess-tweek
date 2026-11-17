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
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e1e2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("correct"))
                .andExpect(jsonPath("$.solutionMove").value("e1e2"));

        assertThat(courses.accountSeen).hasValue("owner-id");
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

        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts")
                        .cookie(SESSION)
                        .header("Origin", "https://example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e1e2\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/exercises/answer-the-check/attempts")
                        .cookie(SESSION)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"../../secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_training_request"));

        assertThat(authorizations).hasValue(0);
        assertThat(courses.attempts).hasValue(0);
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
        public AttemptResult attempt(String accountId, String exerciseKey, String moveUci) {
            accountSeen.set(accountId);
            attempts.incrementAndGet();
            return new AttemptResult(
                    AttemptOutcome.CORRECT,
                    "Correct.",
                    "e1e2",
                    "Checks come first.");
        }

        @Override
        public HintResult hint(String accountId, String exerciseKey, int level) {
            accountSeen.set(accountId);
            return new HintResult(HintOutcome.SHOWN, level, "Use the king.");
        }

        @Override
        public RevealResult reveal(String accountId, String exerciseKey) {
            accountSeen.set(accountId);
            return new RevealResult(RevealOutcome.REVEALED, "e1e2", "Checks come first.");
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
