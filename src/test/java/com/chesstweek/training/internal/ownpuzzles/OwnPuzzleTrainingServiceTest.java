package com.chesstweek.training.internal.ownpuzzles;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.training.OwnPuzzleTraining;
import com.chesstweek.training.OwnPuzzleTraining.ChangeOutcome;
import com.chesstweek.training.OwnPuzzleTraining.GroupCount;
import com.chesstweek.training.OwnPuzzleTraining.GroupKind;
import com.chesstweek.training.OwnPuzzles;
import com.chesstweek.training.OwnPuzzles.NewOwnPuzzle;
import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.PuzzleTraining.HintOutcome;
import com.chesstweek.training.PuzzleTraining.MoveOutcome;
import com.chesstweek.training.PuzzleTraining.RevealOutcome;
import com.chesstweek.training.PuzzleTraining.SessionOutcome;
import com.chesstweek.training.PuzzleTraining.TrainingList;
import com.chesstweek.training.internal.persistence.JdbcOwnPuzzleTrainingStore;

import static org.assertj.core.api.Assertions.assertThat;

class OwnPuzzleTrainingServiceTest {

    private static final String OWNER = "owner-id";
    private static final String OTHER = "other-id";
    /** After 1.e4 e5 2.Nf3: Black to move. */
    private static final String BEFORE_NC6 =
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2";
    /** After 1.e4 e5 2.Nf3 Nc6: White to move. */
    private static final String ITALIAN =
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3";
    /** Ra8 and Re8 both mate. */
    private static final String BACK_RANK = "6k1/5ppp/8/8/8/8/8/R3R1K1 w - - 0 1";
    private static final Instant OLDER = Instant.parse("2026-11-10T18:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-11-20T18:00:00Z");

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock();

    @Test
    void newOwnPuzzlesAreNewestGameFirstAndAnUnfinishedAttemptKeepsThemNew() {
        try (var application = start()) {
            create(application, OWNER, italian("abcd1234", 5, OLDER), backRank("efgh5678", 41, NEWER));
            OwnPuzzleTraining training = training(application);

            assertThat(training.newPuzzles(OWNER, 0, 50).puzzles())
                    .extracting(puzzle -> puzzle.sourceGame().gameId())
                    .containsExactly("efgh5678", "abcd1234");

            OwnPuzzleTraining.Attempt first = training.startNext(OWNER).orElseThrow();
            assertThat(first.puzzle().sourceGame().gameId()).isEqualTo("efgh5678");
            assertThat(first.currentList()).isNull();
            // Leaving it unfinished keeps it New, and it is offered again.
            assertThat(training.startNext(OWNER).orElseThrow().puzzle().id())
                    .isEqualTo(first.puzzle().id());
            assertThat(training.move(OWNER, first.attemptId(), "a1a8").outcome())
                    .isEqualTo(MoveOutcome.FINISHED);
            assertThat(training.overview(OWNER).newPuzzles()).isEqualTo(2);
        }
    }

    @Test
    void theFirstCompletedAttemptClassifiesByTheSharedRules() {
        try (var application = start()) {
            create(application, OWNER, backRank("game0001", 1, NEWER), backRank("game0002", 1, NEWER),
                    backRank("game0003", 1, NEWER), backRank("game0004", 1, NEWER),
                    backRank("game0005", 1, NEWER));
            OwnPuzzleTraining training = training(application);

            assertThat(classify(training, "fast")).isEqualTo(TrainingList.RETIRING);
            assertThat(classify(training, "slow")).isEqualTo(TrainingList.WORTH_REPEATING);
            assertThat(classify(training, "one wrong")).isEqualTo(TrainingList.WRONG_MOVES);
            assertThat(classify(training, "two wrong")).isEqualTo(TrainingList.HARD_TO_SOLVE);
            assertThat(classify(training, "hint")).isEqualTo(TrainingList.HARD_TO_SOLVE);

            OwnPuzzleTraining.Overview overview = training.overview(OWNER);
            assertThat(overview.newPuzzles()).isZero();
            assertThat(overview.lists()).extracting(PuzzleTraining.ListCount::puzzles)
                    .containsExactly(2, 1, 1, 1, 0);
            assertThat(training.startNext(OWNER)).isEmpty();
        }
    }

    @Test
    void aThreeMoveSolutionPlaysTheOpponentsReplyAndAnyMatingMoveSolves() {
        try (var application = start()) {
            create(application, OWNER, italian("abcd1234", 5, OLDER), backRank("efgh5678", 41, NEWER));
            OwnPuzzleTraining training = training(application);
            long italian = idOf(training, "abcd1234");

            OwnPuzzleTraining.Attempt attempt = training.start(OWNER, italian).orElseThrow();
            assertThat(attempt.positionFen()).isEqualTo(ITALIAN);
            assertThat(attempt.previousMove()).isEqualTo("b8c6");
            assertThat(attempt.ownerMoves()).isEqualTo(2);
            assertThat(attempt.fastSolveLimitMillis()).isEqualTo(11_000);
            assertThat(attempt.puzzle().openingFamily()).isEqualTo("Italian Game");
            assertThat(attempt.puzzle().tactics())
                    .containsExactly(new OwnPuzzleTraining.Tactic("fork", "missed"));

            OwnPuzzleTraining.MoveResult first = training.move(OWNER, attempt.attemptId(), "f1b5");
            assertThat(first.outcome()).isEqualTo(MoveOutcome.CORRECT);
            assertThat(first.opponentReply()).isEqualTo("a7a6");
            assertThat(first.completion()).isNull();
            OwnPuzzleTraining.MoveResult solved = training.move(OWNER, attempt.attemptId(), "b5a4");
            assertThat(solved.outcome()).isEqualTo(MoveOutcome.SOLVED);
            assertThat(solved.completion().performance().list()).isEqualTo(TrainingList.RETIRING);
            assertThat(solved.completion().sourceGame())
                    .isEqualTo(new OwnPuzzleTraining.SourceGame("lichess", "abcd1234", 5));

            // The stored solution is Ra8#, but Re8# mates too.
            OwnPuzzleTraining.Attempt mate = training.startNext(OWNER).orElseThrow();
            assertThat(training.move(OWNER, mate.attemptId(), "e1e8").outcome())
                    .isEqualTo(MoveOutcome.SOLVED);
        }
    }

    @Test
    void aHintOrShowSolutionPlacesThePuzzleInHardToSolve() {
        try (var application = start()) {
            create(application, OWNER, italian("abcd1234", 5, OLDER), italian("abcd1234", 9, OLDER));
            OwnPuzzleTraining training = training(application);

            OwnPuzzleTraining.Attempt hinted = training.startNext(OWNER).orElseThrow();
            PuzzleTraining.HintResult hint = training.hint(OWNER, hinted.attemptId());
            assertThat(hint).isEqualTo(new PuzzleTraining.HintResult(HintOutcome.SHOWN, "f1"));
            training.move(OWNER, hinted.attemptId(), "f1b5");
            assertThat(training.move(OWNER, hinted.attemptId(), "b5a4").completion().performance()
                    .list()).isEqualTo(TrainingList.HARD_TO_SOLVE);

            OwnPuzzleTraining.Attempt revealed = training.startNext(OWNER).orElseThrow();
            OwnPuzzleTraining.RevealResult reveal = training.reveal(OWNER, revealed.attemptId());
            assertThat(reveal.outcome()).isEqualTo(RevealOutcome.REVEALED);
            assertThat(reveal.remainingMoves()).containsExactly("f1b5", "a7a6", "b5a4");
            assertThat(reveal.completion().performance().list()).isEqualTo(TrainingList.HARD_TO_SOLVE);
            assertThat(training.reveal(OWNER, revealed.attemptId()).outcome())
                    .isEqualTo(RevealOutcome.FINISHED);
        }
    }

    @Test
    void discardIsOnlyBeforeClassificationAndRestoreReturnsThePuzzleNew() {
        try (var application = start()) {
            create(application, OWNER, italian("abcd1234", 5, OLDER), backRank("efgh5678", 41, NEWER));
            OwnPuzzleTraining training = training(application);
            long italian = idOf(training, "abcd1234");
            long backRank = idOf(training, "efgh5678");

            // Discarding abandons the attempt in progress, so it can never classify the puzzle.
            OwnPuzzleTraining.Attempt active = training.start(OWNER, backRank).orElseThrow();
            assertThat(training.discard(OWNER, backRank)).isEqualTo(ChangeOutcome.DONE);
            assertThat(training.move(OWNER, active.attemptId(), "a1a8").outcome())
                    .isEqualTo(MoveOutcome.FINISHED);
            assertThat(training.performance(OWNER, backRank)).isEmpty();
            assertThat(training.start(OWNER, backRank)).isEmpty();
            assertThat(training.newPuzzles(OWNER, 0, 50).puzzles())
                    .extracting(OwnPuzzleTraining.OwnPuzzle::id).containsExactly(italian);
            assertThat(training.discarded(OWNER, 0, 50).puzzles())
                    .extracting(OwnPuzzleTraining.OwnPuzzle::id).containsExactly(backRank);
            assertThat(training.discard(OWNER, backRank)).isEqualTo(ChangeOutcome.DONE);
            assertThat(training.overview(OWNER).discarded()).isEqualTo(1);

            assertThat(training.restore(OWNER, backRank)).isEqualTo(ChangeOutcome.DONE);
            assertThat(training.restore(OWNER, backRank)).isEqualTo(ChangeOutcome.NOT_DISCARDED);
            assertThat(training.startNext(OWNER).orElseThrow().puzzle().id()).isEqualTo(backRank);

            solveFast(training, training.start(OWNER, italian).orElseThrow(), "f1b5", "b5a4");
            assertThat(training.discard(OWNER, italian)).isEqualTo(ChangeOutcome.CLASSIFIED);
            assertThat(training.discard(OWNER, 999_999)).isEqualTo(ChangeOutcome.NOT_FOUND);
        }
    }

    @Test
    void repetitionSessionsServeOnlyClassifiedPuzzlesOnceEach() {
        try (var application = start()) {
            create(application, OWNER, backRank("game0001", 1, NEWER), backRank("game0002", 1, NEWER),
                    backRank("game0003", 1, NEWER), backRank("game0004", 1, NEWER));
            OwnPuzzleTraining training = training(application);
            for (int index = 0; index < 3; index++) {
                solveFast(training, training.startNext(OWNER).orElseThrow(), "a1a8");
            }

            PuzzleTraining.Session session =
                    training.startSession(OWNER, TrainingList.RETIRING).orElseThrow();
            assertThat(session.total()).isEqualTo(3);
            Set<Long> seen = new HashSet<>();
            for (int position = 1; position <= 3; position++) {
                OwnPuzzleTraining.SessionAttempt next = training.nextInSession(OWNER, session.sessionId());
                assertThat(next.outcome()).isEqualTo(SessionOutcome.STARTED);
                assertThat(next.position()).isEqualTo(position);
                assertThat(next.attempt().currentList()).isEqualTo(TrainingList.RETIRING);
                assertThat(seen.add(next.attempt().puzzle().id())).isTrue();
            }
            assertThat(training.nextInSession(OWNER, session.sessionId()).outcome())
                    .isEqualTo(SessionOutcome.COMPLETE);
            OwnPuzzleTraining.Page retiring = training.inList(OWNER, TrainingList.RETIRING, 1, 50);
            assertThat(retiring.total()).isEqualTo(3);
            assertThat(retiring.puzzles()).hasSize(2);
            assertThat(training.inList(OWNER, TrainingList.HARD_TO_SOLVE, 0, 50).total()).isZero();
            assertThat(training.inList(OTHER, TrainingList.RETIRING, 0, 50).total()).isZero();
            assertThat(training.listCounts(OWNER)).extracting(PuzzleTraining.ListCount::puzzles)
                    .containsExactly(0, 0, 0, 3, 0);
            assertThat(training.startSession(OWNER, TrainingList.HARD_TO_SOLVE)).isEmpty();
            assertThat(training.startSession(OWNER, TrainingList.MASTERED)).isEmpty();
            assertThat(training.nextInSession(OTHER, session.sessionId()).outcome())
                    .isEqualTo(SessionOutcome.NOT_FOUND);
            // The New puzzle is never part of a repetition session.
            assertThat(training.overview(OWNER).newPuzzles()).isEqualTo(1);
        }
    }

    @Test
    void masteredPracticeByGroupNeverChangesState() {
        try (var application = start()) {
            create(application, OWNER, italian("abcd1234", 5, OLDER), backRank("efgh5678", 41, NEWER));
            OwnPuzzleTraining training = training(application);
            long backRank = idOf(training, "efgh5678");
            solveFast(training, training.start(OWNER, backRank).orElseThrow(), "a1a8");
            jdbc(application).update("""
                    UPDATE training_own_puzzle_performance SET list = 'mastered'
                    WHERE account_id = ? AND puzzle_id = ?
                    """, OWNER, backRank);

            OwnPuzzleTraining.Groups mastered = training.mastered(OWNER);
            assertThat(mastered.total()).isEqualTo(1);
            assertThat(mastered.phases()).containsExactly(new GroupCount("endgame", 1));
            assertThat(mastered.openings()).containsExactly(new GroupCount("Unknown opening", 1));
            assertThat(mastered.tactics()).containsExactly(new GroupCount("back_rank", 1));

            assertThat(training.startMasteredSession(OWNER, GroupKind.PHASE, "opening")).isEmpty();
            assertThat(training.startMasteredSession(OWNER, GroupKind.TACTIC, "Bad Key!")).isEmpty();
            PuzzleTraining.Session session = training
                    .startMasteredSession(OWNER, GroupKind.PHASE, "endgame").orElseThrow();
            OwnPuzzleTraining.Attempt practice =
                    training.nextInSession(OWNER, session.sessionId()).attempt();
            training.hint(OWNER, practice.attemptId());
            training.move(OWNER, practice.attemptId(), "g1h1");
            training.move(OWNER, practice.attemptId(), "g1h2");
            assertThat(training.performance(OWNER, backRank).orElseThrow().list())
                    .isEqualTo(TrainingList.MASTERED);
            assertThat(training.startMasteredSession(OWNER, GroupKind.OPENING, "Unknown opening"))
                    .isPresent();
        }
    }

    @Test
    void ownPuzzlesBelongToOneAccountAndNeverMixWithNormalPuzzles() {
        try (var application = start()) {
            create(application, OWNER, italian("abcd1234", 5, OLDER));
            OwnPuzzleTraining training = training(application);
            long italian = idOf(training, "abcd1234");
            OwnPuzzleTraining.Attempt attempt = training.start(OWNER, italian).orElseThrow();

            assertThat(training.newPuzzles(OTHER, 0, 50).total()).isZero();
            assertThat(training.start(OTHER, italian)).isEmpty();
            assertThat(training.discard(OTHER, italian)).isEqualTo(ChangeOutcome.NOT_FOUND);
            assertThat(training.restore(OTHER, italian)).isEqualTo(ChangeOutcome.NOT_FOUND);
            assertThat(training.move(OTHER, attempt.attemptId(), "f1b5").outcome())
                    .isEqualTo(MoveOutcome.NOT_FOUND);
            assertThat(training.hint(OTHER, attempt.attemptId()).outcome())
                    .isEqualTo(HintOutcome.NOT_FOUND);

            solveFast(training, attempt, "f1b5", "b5a4");
            PuzzleTraining normal = application.getBean(PuzzleTraining.class);
            assertThat(normal.listCounts(OWNER)).extracting(PuzzleTraining.ListCount::puzzles)
                    .containsOnly(0);
            assertThat(normal.startSession(OWNER, TrainingList.RETIRING)).isEmpty();
            // A normal attempt and an Own attempt never see each other's IDs.
            PuzzleTraining.Attempt random = normal.startRandom(OWNER).orElseThrow();
            assertThat(training.move(OWNER, random.attemptId(), "a1a8").outcome())
                    .isIn(MoveOutcome.NOT_FOUND, MoveOutcome.FINISHED);
            assertThat(training.overview(OWNER).lists())
                    .extracting(PuzzleTraining.ListCount::puzzles).containsExactly(0, 0, 0, 1, 0);
        }
    }

    @Test
    void progressSurvivesARestart() {
        long italian;
        try (var application = start()) {
            create(application, OWNER, italian("abcd1234", 5, OLDER), backRank("efgh5678", 41, NEWER));
            OwnPuzzleTraining training = training(application);
            italian = idOf(training, "abcd1234");
            solveFast(training, training.start(OWNER, italian).orElseThrow(), "f1b5", "b5a4");
            training.discard(OWNER, idOf(training, "efgh5678"));
        }
        try (var application = start()) {
            OwnPuzzleTraining training = training(application);
            assertThat(training.performance(OWNER, italian).orElseThrow().list())
                    .isEqualTo(TrainingList.RETIRING);
            assertThat(training.overview(OWNER).discarded()).isEqualTo(1);
            assertThat(training.overview(OWNER).groups().phases())
                    .contains(new GroupCount("opening", 1));
        }
    }

    /** Classifies the next New back-rank puzzle the given way and returns its list. */
    private TrainingList classify(OwnPuzzleTraining training, String how) {
        OwnPuzzleTraining.Attempt attempt = training.startNext(OWNER).orElseThrow();
        switch (how) {
            case "slow" -> clock.advance(Duration.ofSeconds(7));
            case "one wrong" -> training.move(OWNER, attempt.attemptId(), "g1h1");
            case "two wrong" -> {
                training.move(OWNER, attempt.attemptId(), "g1h1");
                training.move(OWNER, attempt.attemptId(), "g1h2");
            }
            case "hint" -> training.hint(OWNER, attempt.attemptId());
            default -> { }
        }
        return training.move(OWNER, attempt.attemptId(), "a1a8").completion().performance().list();
    }

    private void solveFast(OwnPuzzleTraining training, OwnPuzzleTraining.Attempt attempt, String... moves) {
        OwnPuzzleTraining.MoveResult result = null;
        for (String move : moves) {
            result = training.move(OWNER, attempt.attemptId(), move);
        }
        assertThat(result.outcome()).isEqualTo(MoveOutcome.SOLVED);
    }

    private static long idOf(OwnPuzzleTraining training, String gameId) {
        return training.newPuzzles(OWNER, 0, 50).puzzles().stream()
                .filter(puzzle -> puzzle.sourceGame().gameId().equals(gameId))
                .findFirst().orElseThrow().id();
    }

    private static NewOwnPuzzle italian(String gameId, int ply, Instant played) {
        return new NewOwnPuzzle("lichess", gameId, ply, 3, ITALIAN, BEFORE_NC6, "b8c6",
                List.of("f1b5", "a7a6", "b5a4", "g8f6"), "mistake", "opening", "C50",
                "Italian Game: Two Knights Defense", played,
                List.of(new OwnPuzzles.Tactic("fork", "missed")));
    }

    private static NewOwnPuzzle backRank(String gameId, int ply, Instant played) {
        return new NewOwnPuzzle("lichess", gameId, ply, 4, BACK_RANK, null, null,
                List.of("a1a8"), "blunder", "endgame", null, null, played,
                List.of(new OwnPuzzles.Tactic("back_rank", "missed")));
    }

    private static void create(
            ConfigurableApplicationContext application, String account, NewOwnPuzzle... puzzles) {
        application.getBean(OwnPuzzles.class).create(account, new ArrayList<>(List.of(puzzles)));
    }

    private OwnPuzzleTraining training(ConfigurableApplicationContext application) {
        return new OwnPuzzleTrainingService(
                application.getBean(JdbcOwnPuzzleTrainingStore.class), clock);
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext application) {
        return application.getBean(JdbcTemplate.class);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + directory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-24T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
