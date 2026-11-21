package com.chesstweek.training.internal.performance;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.PuzzleTraining.MoveOutcome;
import com.chesstweek.training.PuzzleTraining.TrainingList;
import com.chesstweek.training.internal.persistence.JdbcPuzzlePerformanceStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PuzzleTrainingServiceTest {

    private static final String OWNER = "owner-id";
    private static final String OTHER = "other-id";
    /** The built-in Puzzle Library installs 12 Puzzles in every database. */
    private static final int BUILT_IN = 12;

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock();

    @Test
    void randomClassifiesEachPuzzleOnceAndNeverServesAClassifiedPuzzleAgain() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            Set<Long> seen = new HashSet<>();
            for (int index = 0; index < BUILT_IN; index++) {
                PuzzleTraining.Attempt attempt = training.startRandom(OWNER).orElseThrow();
                assertThat(attempt.currentList()).isNull();
                assertThat(seen.add(attempt.puzzleId())).isTrue();
                PuzzleTraining.MoveResult solved = solve(application, training, attempt, 0);
                assertThat(solved.outcome()).isEqualTo(MoveOutcome.SOLVED);
                assertThat(solved.completion().previousList()).isNull();
                assertThat(solved.completion().performance())
                        .isEqualTo(new PuzzleTraining.Performance(TrainingList.RETIRING, 0, 0));
            }
            assertThat(training.startRandom(OWNER)).isEmpty();
            // Classification is per account.
            assertThat(training.startRandom(OTHER)).isPresent();
        }
    }

    @Test
    void countsWrongMovesKeepsThePositionAndIgnoresIllegalMoves() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            PuzzleTraining.Attempt attempt = training.startRandom(OWNER).orElseThrow();
            List<String> solution = solution(application, attempt);

            assertThat(training.move(OWNER, attempt.attemptId(), "a1a1").outcome())
                    .isEqualTo(MoveOutcome.ILLEGAL);
            String wrong = wrongMove(application, attempt, 0);
            PuzzleTraining.MoveResult first = training.move(OWNER, attempt.attemptId(), wrong);
            assertThat(first.outcome()).isEqualTo(MoveOutcome.WRONG);
            assertThat(first.wrongMoves()).isEqualTo(1);

            PuzzleTraining.MoveResult solved = solve(application, training, attempt, 0);
            assertThat(solved.outcome()).isEqualTo(MoveOutcome.SOLVED);
            assertThat(solved.completion().wrongMoves()).isEqualTo(1);
            assertThat(solved.completion().performance().list()).isEqualTo(TrainingList.WRONG_MOVES);
            assertThat(solution).isNotEmpty();

            assertThat(training.move(OWNER, attempt.attemptId(), solution.getFirst()).outcome())
                    .isEqualTo(MoveOutcome.FINISHED);
        }
    }

    @Test
    void twoWrongMovesOrASlowSolveClassifyByPriority() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            PuzzleTraining.Attempt twice = training.startRandom(OWNER).orElseThrow();
            String wrong = wrongMove(application, twice, 0);
            training.move(OWNER, twice.attemptId(), wrong);
            training.move(OWNER, twice.attemptId(), wrong);
            assertThat(solve(application, training, twice, 0).completion().performance().list())
                    .isEqualTo(TrainingList.HARD_TO_SOLVE);

            PuzzleTraining.Attempt slow = training.startRandom(OWNER).orElseThrow();
            clock.advance(Duration.ofMillis(slow.fastSolveLimitMillis() + 1));
            PuzzleTraining.Completion completion = solve(application, training, slow, 0).completion();
            assertThat(completion.performance().list()).isEqualTo(TrainingList.WORTH_REPEATING);
            assertThat(completion.elapsedMillis()).isEqualTo(slow.fastSolveLimitMillis() + 1);
        }
    }

    @Test
    void aHintShowsThePieceToMoveAndShowSolutionCountsAsAHint() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            PuzzleTraining.Attempt hinted = training.startRandom(OWNER).orElseThrow();
            PuzzleTraining.HintResult hint = training.hint(OWNER, hinted.attemptId());
            assertThat(hint.outcome()).isEqualTo(PuzzleTraining.HintOutcome.SHOWN);
            assertThat(hint.fromSquare()).isEqualTo(solution(application, hinted).getFirst().substring(0, 2));
            assertThat(solve(application, training, hinted, 0).completion().performance().list())
                    .isEqualTo(TrainingList.HARD_TO_SOLVE);

            PuzzleTraining.Attempt revealed = training.startRandom(OWNER).orElseThrow();
            List<String> solution = solution(application, revealed);
            PuzzleTraining.RevealResult reveal = training.reveal(OWNER, revealed.attemptId());
            assertThat(reveal.outcome()).isEqualTo(PuzzleTraining.RevealOutcome.REVEALED);
            assertThat(reveal.remainingMoves()).isEqualTo(solution);
            assertThat(reveal.completion().hintUsed()).isTrue();
            assertThat(reveal.completion().performance().list()).isEqualTo(TrainingList.HARD_TO_SOLVE);
            assertThat(training.reveal(OWNER, revealed.attemptId()).outcome())
                    .isEqualTo(PuzzleTraining.RevealOutcome.FINISHED);
            assertThat(training.hint(OWNER, revealed.attemptId()).outcome())
                    .isEqualTo(PuzzleTraining.HintOutcome.FINISHED);
        }
    }

    @Test
    void anUnfinishedAttemptLeavesThePuzzleUnclassifiedAndIsAbandonedByTheNextStart() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            PuzzleTraining.Attempt unfinished = training.startRandom(OWNER).orElseThrow();
            training.move(OWNER, unfinished.attemptId(), wrongMove(application, unfinished, 0));
            training.hint(OWNER, unfinished.attemptId());

            PuzzleTraining.Attempt next = training.startRandom(OWNER).orElseThrow();
            assertThat(training.performance(OWNER, unfinished.puzzleId())).isEmpty();
            assertThat(training.move(OWNER, unfinished.attemptId(),
                    solution(application, unfinished).getFirst()).outcome())
                    .isEqualTo(MoveOutcome.FINISHED);
            assertThat(training.move(OWNER, next.attemptId(),
                    solution(application, next).getFirst()).outcome())
                    .isIn(MoveOutcome.CORRECT, MoveOutcome.SOLVED);
        }
    }

    @Test
    void attemptsAreScopedToTheirAccount() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            PuzzleTraining.Attempt attempt = training.startRandom(OWNER).orElseThrow();
            String move = solution(application, attempt).getFirst();
            assertThat(training.move(OTHER, attempt.attemptId(), move).outcome())
                    .isEqualTo(MoveOutcome.NOT_FOUND);
            assertThat(training.hint(OTHER, attempt.attemptId()).outcome())
                    .isEqualTo(PuzzleTraining.HintOutcome.NOT_FOUND);
            assertThat(training.reveal(OTHER, attempt.attemptId()).outcome())
                    .isEqualTo(PuzzleTraining.RevealOutcome.NOT_FOUND);
            assertThatThrownBy(() -> training.startRandom("bad account"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void repetitionThroughByCategoryAppliesTheCurrentListAndSkipsMasteredPuzzles() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            PuzzleLibrary.Category intermezzo = new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.THEME, "intermezzo");
            List<Long> members = jdbc.queryForList("""
                    SELECT m.puzzle_id FROM training_puzzle_category_member m
                    JOIN training_puzzle_category c ON c.id = m.category_id
                    WHERE c.kind = 'theme' AND c.classification_key = 'intermezzo'
                    """, Long.class);
            assertThat(members).hasSizeGreaterThan(1);

            PuzzleTraining.Attempt first = training.startInCategory(OWNER, intermezzo).orElseThrow();
            assertThat(members).contains(first.puzzleId());
            solve(application, training, first, 0);
            jdbc.update("""
                    UPDATE training_puzzle_performance SET list = 'retiring', successes = 4
                    WHERE account_id = ? AND puzzle_id = ?
                    """, OWNER, first.puzzleId());
            for (long member : members) {
                if (member != first.puzzleId()) {
                    jdbc.update("""
                            INSERT INTO training_puzzle_performance
                            VALUES (?, ?, 'mastered', 0, 0, 0, 0)
                            """, OWNER, member);
                }
            }

            PuzzleTraining.Attempt repetition = training.startInCategory(OWNER, intermezzo).orElseThrow();
            assertThat(repetition.puzzleId()).isEqualTo(first.puzzleId());
            assertThat(repetition.currentList()).isEqualTo(TrainingList.RETIRING);
            PuzzleTraining.Completion completion =
                    solve(application, training, repetition, 0).completion();
            assertThat(completion.previousList()).isEqualTo(TrainingList.RETIRING);
            assertThat(completion.performance())
                    .isEqualTo(new PuzzleTraining.Performance(TrainingList.MASTERED, 0, 0));

            assertThat(training.startInCategory(OWNER, intermezzo)).isEmpty();
            assertThat(training.startInCategory(OWNER, new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.THEME, "notATheme"))).isEmpty();
            assertThat(training.startInCategory(OWNER, new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.THEME, "bad key!"))).isEmpty();
        }
    }

    @Test
    void aRepetitionDemotionIsStoredImmediatelyAndCannotBeDodgedByLeaving() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            PuzzleLibrary.Category intermezzo = new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.THEME, "intermezzo");
            // Every other intermezzo Puzzle is Mastered, so By Category repeats one Puzzle.
            PuzzleTraining.Attempt first = training.startInCategory(OWNER, intermezzo).orElseThrow();
            solve(application, training, first, 0);
            jdbc.update("""
                    INSERT INTO training_puzzle_performance
                    SELECT ?, m.puzzle_id, 'mastered', 0, 0, 0, 0
                    FROM training_puzzle_category_member m
                    JOIN training_puzzle_category c ON c.id = m.category_id
                    WHERE c.kind = 'theme' AND c.classification_key = 'intermezzo'
                      AND m.puzzle_id <> ?
                    """, OWNER, first.puzzleId());
            jdbc.update("""
                    UPDATE training_puzzle_performance
                    SET list = 'worth_repeating', successes = 6
                    WHERE account_id = ? AND puzzle_id = ?
                    """, OWNER, first.puzzleId());

            PuzzleTraining.Attempt wrong = training.startInCategory(OWNER, intermezzo).orElseThrow();
            training.move(OWNER, wrong.attemptId(), wrongMove(application, wrong, 0));
            assertThat(training.performance(OWNER, first.puzzleId()))
                    .contains(new PuzzleTraining.Performance(TrainingList.WRONG_MOVES, 0, 0));

            PuzzleTraining.Attempt hinted = training.startInCategory(OWNER, intermezzo).orElseThrow();
            assertThat(hinted.currentList()).isEqualTo(TrainingList.WRONG_MOVES);
            training.hint(OWNER, hinted.attemptId());
            assertThat(training.performance(OWNER, first.puzzleId()))
                    .contains(new PuzzleTraining.Performance(TrainingList.HARD_TO_SOLVE, 0, 0));

            // Leaving does not undo it; completing applies Hard to Solve's rules to the attempt.
            PuzzleTraining.Attempt again = training.startInCategory(OWNER, intermezzo).orElseThrow();
            assertThat(training.move(OWNER, hinted.attemptId(),
                    solution(application, hinted).getFirst()).outcome())
                    .isEqualTo(MoveOutcome.FINISHED);
            PuzzleTraining.Completion completion =
                    solve(application, training, again, 0).completion();
            assertThat(completion.previousList()).isEqualTo(TrainingList.HARD_TO_SOLVE);
            assertThat(completion.performance())
                    .isEqualTo(new PuzzleTraining.Performance(TrainingList.HARD_TO_SOLVE, 1, 0));
        }
    }

    @Test
    void listStateSurvivesRestart() {
        long puzzleId;
        try (var application = start()) {
            PuzzleTraining training = training(application);
            PuzzleTraining.Attempt attempt = training.startRandom(OWNER).orElseThrow();
            puzzleId = attempt.puzzleId();
            training.move(OWNER, attempt.attemptId(), wrongMove(application, attempt, 0));
            solve(application, training, attempt, 0);
        }
        try (var restarted = start()) {
            assertThat(training(restarted).performance(OWNER, puzzleId))
                    .contains(new PuzzleTraining.Performance(TrainingList.WRONG_MOVES, 0, 0));
        }
    }

    @Test
    void acceptsAnyMatingMoveAsOnLichess() {
        try (var application = start()) {
            PuzzleLibrary real = application.getBean(PuzzleLibrary.class);
            long puzzleId = real.randomPuzzle().orElseThrow().id();
            PuzzleLibrary.Puzzle backRank = new PuzzleLibrary.Puzzle(
                    puzzleId, "lichess-puzzle-database", "MATE1", "7k/6pp/8/8/8/8/8/RR4K1 b - - 0 1",
                    List.of("h8g8", "a1a8"), "h8g8", "7k/6pp/8/8/8/8/8/RR4K1 w - - 0 1",
                    List.of("a1a8"), "https://lichess.org/mate0001", "CC0-1.0", null,
                    1000, 70, 90, 100, List.of("mateIn1"), List.of());
            PuzzleTraining training = new PuzzleTrainingService(
                    new SinglePuzzleLibrary(backRank),
                    application.getBean(JdbcPuzzlePerformanceStore.class),
                    clock);
            // Leave only the synthetic Puzzle's ID unclassified so Random selects it.
            application.getBean(JdbcTemplate.class).update("""
                    INSERT INTO training_puzzle_performance
                    SELECT ?, puzzle_id, 'mastered', 0, 0, 0, 0 FROM training_puzzle_current
                    WHERE puzzle_id <> ?
                    """, OWNER, puzzleId);

            PuzzleTraining.Attempt attempt = training.startRandom(OWNER).orElseThrow();
            assertThat(attempt.puzzleId()).isEqualTo(puzzleId);
            PuzzleTraining.MoveResult mate = training.move(OWNER, attempt.attemptId(), "b1b8");
            assertThat(mate.outcome()).isEqualTo(MoveOutcome.SOLVED);
            assertThat(mate.completion().performance().list()).isEqualTo(TrainingList.RETIRING);
        }
    }

    @Test
    void aPracticeSessionPresentsEveryPuzzleInItsListOnceInAFreshOrder() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            assertThat(training.startSession(OWNER, TrainingList.WORTH_REPEATING)).isEmpty();
            jdbc.update("""
                    INSERT INTO training_puzzle_performance
                    SELECT ?, puzzle_id, 'worth_repeating', 3, 0, 0, 0 FROM training_puzzle_current
                    """, OWNER);
            assertThat(training.startSession(OWNER, TrainingList.MASTERED)).isEmpty();
            assertThat(training.listCounts(OWNER)).containsExactly(
                    new PuzzleTraining.ListCount(TrainingList.HARD_TO_SOLVE, 0),
                    new PuzzleTraining.ListCount(TrainingList.WRONG_MOVES, 0),
                    new PuzzleTraining.ListCount(TrainingList.WORTH_REPEATING, BUILT_IN),
                    new PuzzleTraining.ListCount(TrainingList.RETIRING, 0),
                    new PuzzleTraining.ListCount(TrainingList.MASTERED, 0));

            PuzzleTraining.Session session =
                    training.startSession(OWNER, TrainingList.WORTH_REPEATING).orElseThrow();
            assertThat(session.total()).isEqualTo(BUILT_IN);
            assertThat(training.nextInSession(OTHER, session.sessionId()).outcome())
                    .isEqualTo(PuzzleTraining.SessionOutcome.NOT_FOUND);
            List<Long> order = new java.util.ArrayList<>();
            for (int index = 1; index <= BUILT_IN; index++) {
                PuzzleTraining.SessionAttempt next = training.nextInSession(OWNER, session.sessionId());
                assertThat(next.outcome()).isEqualTo(PuzzleTraining.SessionOutcome.STARTED);
                assertThat(next.position()).isEqualTo(index);
                assertThat(next.total()).isEqualTo(BUILT_IN);
                assertThat(next.attempt().currentList()).isEqualTo(TrainingList.WORTH_REPEATING);
                order.add(next.attempt().puzzleId());
            }
            assertThat(new HashSet<>(order)).hasSize(BUILT_IN);
            assertThat(training.nextInSession(OWNER, session.sessionId()).outcome())
                    .isEqualTo(PuzzleTraining.SessionOutcome.COMPLETE);

            // Opening the list again starts a new session with a fresh order; the old one is gone.
            boolean reshuffled = false;
            long previous = session.sessionId();
            for (int round = 0; round < 5 && !reshuffled; round++) {
                PuzzleTraining.Session again =
                        training.startSession(OWNER, TrainingList.WORTH_REPEATING).orElseThrow();
                assertThat(training.nextInSession(OWNER, previous).outcome())
                        .isEqualTo(PuzzleTraining.SessionOutcome.NOT_FOUND);
                previous = again.sessionId();
                List<Long> next = new java.util.ArrayList<>();
                for (int index = 0; index < BUILT_IN; index++) {
                    next.add(training.nextInSession(OWNER, again.sessionId()).attempt().puzzleId());
                }
                reshuffled = !next.equals(order);
            }
            assertThat(reshuffled).isTrue();
        }
    }

    @Test
    void aSessionAppliesTheListRulesAndPassesOverPuzzlesThatLeftTheList() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            List<Long> puzzles = jdbc.queryForList("""
                    SELECT puzzle_id FROM training_puzzle_current ORDER BY puzzle_id LIMIT 3
                    """, Long.class);
            for (long puzzle : puzzles) {
                jdbc.update("""
                        INSERT INTO training_puzzle_performance
                        VALUES (?, ?, 'hard_to_solve', 2, 0, 0, 0)
                        """, OWNER, puzzle);
            }
            PuzzleTraining.Session session =
                    training.startSession(OWNER, TrainingList.HARD_TO_SOLVE).orElseThrow();
            assertThat(session.total()).isEqualTo(3);

            PuzzleTraining.SessionAttempt first = training.nextInSession(OWNER, session.sessionId());
            PuzzleTraining.Attempt attempt = first.attempt();
            // One Wrong Move still counts as a Successful Repetition in Hard to Solve.
            training.move(OWNER, attempt.attemptId(), wrongMove(application, attempt, 0));
            PuzzleTraining.Completion completion = solve(application, training, attempt, 0).completion();
            assertThat(completion.previousList()).isEqualTo(TrainingList.HARD_TO_SOLVE);
            assertThat(completion.performance())
                    .isEqualTo(new PuzzleTraining.Performance(TrainingList.HARD_TO_SOLVE, 3, 0));
            assertThat(jdbc.queryForObject("""
                    SELECT entry FROM training_puzzle_attempt WHERE id = ?
                    """, String.class, attempt.attemptId())).isEqualTo("repetition");

            // A Puzzle that left the list since the session started is passed over.
            long remaining = puzzles.stream().filter(id -> id != attempt.puzzleId())
                    .findFirst().orElseThrow();
            jdbc.update("""
                    UPDATE training_puzzle_performance SET list = 'retiring', successes = 0
                    WHERE account_id = ? AND puzzle_id = ?
                    """, OWNER, remaining);
            PuzzleTraining.SessionAttempt second = training.nextInSession(OWNER, session.sessionId());
            assertThat(second.outcome()).isEqualTo(PuzzleTraining.SessionOutcome.STARTED);
            assertThat(second.attempt().puzzleId()).isNotIn(attempt.puzzleId(), remaining);
            assertThat(training.nextInSession(OWNER, session.sessionId()).outcome())
                    .isEqualTo(PuzzleTraining.SessionOutcome.COMPLETE);
        }
    }

    @Test
    void masteredPracticeIsGroupedByCategoryAndNeverChangesState() {
        try (var application = start()) {
            PuzzleTraining training = training(application);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            assertThat(training.mastered(OWNER))
                    .isEqualTo(new PuzzleTraining.MasteredSummary(0, List.of()));
            List<Long> intermezzo = jdbc.queryForList("""
                    SELECT m.puzzle_id FROM training_puzzle_category_member m
                    JOIN training_puzzle_category c ON c.id = m.category_id
                    WHERE c.kind = 'theme' AND c.classification_key = 'intermezzo'
                    """, Long.class);
            for (long puzzle : intermezzo) {
                jdbc.update("""
                        INSERT INTO training_puzzle_performance
                        VALUES (?, ?, 'mastered', 0, 0, 0, 0)
                        """, OWNER, puzzle);
            }

            PuzzleTraining.MasteredSummary summary = training.mastered(OWNER);
            assertThat(summary.total()).isEqualTo(intermezzo.size());
            assertThat(summary.categories())
                    .anySatisfy(entry -> {
                        assertThat(entry.category()).isEqualTo(new PuzzleLibrary.Category(
                                PuzzleLibrary.CategoryKind.THEME, "intermezzo"));
                        assertThat(entry.puzzles()).isEqualTo(intermezzo.size());
                    })
                    .allSatisfy(entry -> assertThat(entry.puzzles())
                            .isBetween(1, intermezzo.size()));
            assertThat(training.mastered(OTHER).total()).isZero();
            assertThat(training.startMasteredSession(OWNER, new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.THEME, "notATheme"))).isEmpty();
            assertThat(training.startMasteredSession(OWNER, new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.THEME, "bad key!"))).isEmpty();
            assertThat(training.startMasteredSession(OTHER, new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.THEME, "intermezzo"))).isEmpty();

            PuzzleTraining.Session session = training.startMasteredSession(OWNER,
                    new PuzzleLibrary.Category(PuzzleLibrary.CategoryKind.THEME, "intermezzo"))
                    .orElseThrow();
            assertThat(session.list()).isEqualTo(TrainingList.MASTERED);
            assertThat(session.total()).isEqualTo(intermezzo.size());

            PuzzleTraining.Attempt wrong = training.nextInSession(OWNER, session.sessionId()).attempt();
            assertThat(wrong.currentList()).isEqualTo(TrainingList.MASTERED);
            String move = wrongMove(application, wrong, 0);
            training.move(OWNER, wrong.attemptId(), move);
            training.move(OWNER, wrong.attemptId(), move);
            training.hint(OWNER, wrong.attemptId());
            assertThat(training.performance(OWNER, wrong.puzzleId()))
                    .contains(new PuzzleTraining.Performance(TrainingList.MASTERED, 0, 0));
            PuzzleTraining.Completion completion =
                    training.reveal(OWNER, wrong.attemptId()).completion();
            assertThat(completion.previousList()).isEqualTo(TrainingList.MASTERED);
            assertThat(completion.performance())
                    .isEqualTo(new PuzzleTraining.Performance(TrainingList.MASTERED, 0, 0));

            PuzzleTraining.Attempt fast = training.nextInSession(OWNER, session.sessionId()).attempt();
            assertThat(solve(application, training, fast, 0).completion().performance())
                    .isEqualTo(new PuzzleTraining.Performance(TrainingList.MASTERED, 0, 0));
            assertThat(jdbc.queryForList("""
                    SELECT DISTINCT updated_at_ms FROM training_puzzle_performance
                    WHERE account_id = ?
                    """, Long.class, OWNER)).containsExactly(0L);
            assertThat(jdbc.queryForList("""
                    SELECT DISTINCT entry || '/' || list_after FROM training_puzzle_attempt
                    WHERE status = 'completed'
                    """, String.class)).containsExactly("mastered_practice/mastered");
        }
    }

    /** Plays the remaining solution from {@code ply}; returns the final result. */
    private PuzzleTraining.MoveResult solve(
            ConfigurableApplicationContext application,
            PuzzleTraining training,
            PuzzleTraining.Attempt attempt,
            int ply) {
        List<String> solution = solution(application, attempt);
        PuzzleTraining.MoveResult result = null;
        for (int index = ply; index < solution.size(); index += 2) {
            result = training.move(OWNER, attempt.attemptId(), solution.get(index));
            if (index + 2 < solution.size()) {
                assertThat(result.outcome()).isEqualTo(MoveOutcome.CORRECT);
                assertThat(result.opponentReply()).isEqualTo(solution.get(index + 1));
            }
        }
        return result;
    }

    private static List<String> solution(
            ConfigurableApplicationContext application, PuzzleTraining.Attempt attempt) {
        return application.getBean(PuzzleLibrary.class).puzzle(attempt.puzzleId())
                .orElseThrow().solutionMoves();
    }

    /** A legal move at {@code ply} that neither matches the solution nor mates. */
    private static String wrongMove(
            ConfigurableApplicationContext application, PuzzleTraining.Attempt attempt, int ply) {
        PuzzleLibrary.Puzzle puzzle =
                application.getBean(PuzzleLibrary.class).puzzle(attempt.puzzleId()).orElseThrow();
        var game = new io.github.wolfraam.chessgame.ChessGame(puzzle.positionFen());
        for (var move : game.getLegalMoves()) {
            String uci = game.getNotation(io.github.wolfraam.chessgame.notation.NotationType.UCI, move);
            var after = game.clone();
            after.playMove(move);
            boolean mates = after.isKingAttacked() && after.getLegalMoves().isEmpty();
            if (!uci.equals(puzzle.solutionMoves().get(ply)) && !mates) {
                return uci;
            }
        }
        throw new AssertionError("No wrong move available");
    }

    private PuzzleTraining training(ConfigurableApplicationContext application) {
        return new PuzzleTrainingService(
                application.getBean(PuzzleLibrary.class),
                application.getBean(JdbcPuzzlePerformanceStore.class),
                clock);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + directory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** Serves one synthetic Puzzle under a real stored Puzzle ID. */
    private record SinglePuzzleLibrary(PuzzleLibrary.Puzzle puzzle) implements PuzzleLibrary {
        @Override
        public Optional<Puzzle> puzzle(long puzzleId) {
            return puzzleId == puzzle.id() ? Optional.of(puzzle) : Optional.empty();
        }

        @Override
        public Optional<Puzzle> puzzle(String provider, String externalId) {
            return Optional.empty();
        }

        @Override
        public Optional<Puzzle> exercisePuzzle(String methodKey, int contentVersion, String exerciseKey) {
            return Optional.empty();
        }

        @Override
        public Optional<Puzzle> randomPuzzle() {
            return Optional.of(puzzle);
        }

        @Override
        public Optional<Puzzle> randomPuzzle(Category category) {
            return Optional.empty();
        }

        @Override
        public List<CategoryEntry> categories() {
            return List.of();
        }

        @Override
        public CatalogSummary summary() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ImportResult importSnapshot(ImportRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
