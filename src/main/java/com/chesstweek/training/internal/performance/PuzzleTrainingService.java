package com.chesstweek.training.internal.performance;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.internal.library.PuzzleImportBatch;
import com.chesstweek.training.internal.persistence.JdbcPerformanceState;
import com.chesstweek.training.internal.persistence.JdbcPerformanceState.AttemptRow;
import com.chesstweek.training.internal.persistence.JdbcPuzzlePerformanceStore;

/**
 * Performance training of normal Puzzles: selection, sessions, and the Mastered view. Solving is
 * the shared {@link AttemptSolver}, so normal and Own Puzzles apply the same rules to separate
 * state.
 */
@Service
public class PuzzleTrainingService implements PuzzleTraining {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern CATEGORY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,120}");

    private static final List<TrainingList> ACTIVE_LISTS = List.of(
            TrainingList.HARD_TO_SOLVE,
            TrainingList.WRONG_MOVES,
            TrainingList.WORTH_REPEATING,
            TrainingList.RETIRING);

    private final PuzzleLibrary library;
    private final JdbcPuzzlePerformanceStore store;
    private final JdbcPerformanceState state;
    private final AttemptSolver solver;
    private final Clock clock;
    private final Random shuffle = new SecureRandom();

    @Autowired
    public PuzzleTrainingService(PuzzleLibrary library, JdbcPuzzlePerformanceStore store) {
        this(library, store, Clock.systemUTC());
    }

    PuzzleTrainingService(PuzzleLibrary library, JdbcPuzzlePerformanceStore store, Clock clock) {
        this.library = library;
        this.store = store;
        this.state = store.state();
        this.solver = new AttemptSolver(state, databaseValue(Entry.MASTERED_PRACTICE));
        this.clock = clock;
    }

    @Override
    public Optional<Attempt> startRandom(String accountId) {
        requireAccount(accountId);
        return store.randomUnclassified(accountId, ThreadLocalRandom.current())
                .flatMap(puzzleId -> start(accountId, puzzleId, Entry.RANDOM));
    }

    @Override
    public Optional<Attempt> startInCategory(String accountId, PuzzleLibrary.Category category) {
        requireAccount(accountId);
        if (!validCategory(category)) {
            return Optional.empty();
        }
        return store.randomInCategory(
                        accountId, kind(category), category.key(), ThreadLocalRandom.current())
                .flatMap(puzzleId -> start(accountId, puzzleId, Entry.CATEGORY));
    }

    @Override
    public Optional<Attempt> startForMethod(String accountId, long puzzleId) {
        requireAccount(accountId);
        return start(accountId, puzzleId, Entry.METHOD);
    }

    @Override
    public Optional<Long> methodAttemptPuzzle(String accountId, long attemptId) {
        requireAccount(accountId);
        return state.attempt(accountId, attemptId)
                .filter(attempt -> attempt.entry().equals(databaseValue(Entry.METHOD)))
                .map(AttemptRow::puzzleId);
    }

    @Override
    public List<ListCount> listCounts(String accountId) {
        requireAccount(accountId);
        Map<TrainingList, Integer> counts = state.listCounts(accountId);
        List<ListCount> lists = new ArrayList<>();
        for (TrainingList list : TrainingList.values()) {
            lists.add(new ListCount(list, counts.getOrDefault(list, 0)));
        }
        return List.copyOf(lists);
    }

    @Override
    public MasteredSummary mastered(String accountId) {
        requireAccount(accountId);
        var counts = store.masteredCategoryCounts(accountId);
        List<PuzzleLibrary.CategoryEntry> categories = new ArrayList<>();
        if (!counts.isEmpty()) {
            for (PuzzleLibrary.CategoryEntry entry : library.categories()) {
                Integer count = counts.getOrDefault(kind(entry.category()), Map.of())
                        .get(entry.category().key());
                if (count != null) {
                    categories.add(new PuzzleLibrary.CategoryEntry(
                            entry.category(), entry.displayName(), entry.groupName(),
                            entry.parentKey(), count));
                }
            }
        }
        return new MasteredSummary(
                state.count(accountId, TrainingList.MASTERED), List.copyOf(categories));
    }

    @Override
    public Optional<Session> startSession(String accountId, TrainingList list) {
        requireAccount(accountId);
        if (!ACTIVE_LISTS.contains(list)) {
            return Optional.empty();
        }
        return store.startSession(accountId, list, null, null, shuffle, now())
                .map(row -> new Session(row.id(), row.list(), row.total()));
    }

    @Override
    public Optional<Session> startMasteredSession(
            String accountId, PuzzleLibrary.Category category) {
        requireAccount(accountId);
        if (!validCategory(category)) {
            return Optional.empty();
        }
        return store.startSession(accountId, TrainingList.MASTERED, kind(category),
                        category.key(), shuffle, now())
                .map(row -> new Session(row.id(), row.list(), row.total()));
    }

    @Override
    public SessionAttempt nextInSession(String accountId, long sessionId) {
        requireAccount(accountId);
        var session = state.session(accountId, sessionId).orElse(null);
        if (session == null) {
            return new SessionAttempt(SessionOutcome.NOT_FOUND, null, 0, 0);
        }
        Entry entry = session.list() == TrainingList.MASTERED
                ? Entry.MASTERED_PRACTICE
                : Entry.REPETITION;
        while (true) {
            var item = state.presentNext(accountId, session, now()).orElse(null);
            if (item == null) {
                return new SessionAttempt(SessionOutcome.COMPLETE, null, 0, session.total());
            }
            Optional<Attempt> attempt = start(accountId, item.puzzleId(), entry);
            if (attempt.isPresent()) {
                return new SessionAttempt(
                        SessionOutcome.STARTED, attempt.get(), item.position(), item.total());
            }
        }
    }

    @Override
    public MoveResult move(String accountId, long attemptId, String moveUci) {
        requireAccount(accountId);
        AttemptRow attempt = state.attempt(accountId, attemptId).orElse(null);
        if (attempt == null) {
            return new MoveResult(MoveOutcome.NOT_FOUND, null, 0, null);
        }
        if (attempt.status() != JdbcPerformanceState.Status.ACTIVE) {
            return new MoveResult(MoveOutcome.FINISHED, null, attempt.wrongMoves(), null);
        }
        PuzzleLibrary.Puzzle puzzle = puzzle(attempt);
        AttemptSolver.MoveStep step = solver.move(attempt, line(puzzle), moveUci, now());
        return new MoveResult(
                step.outcome(), step.opponentReply(), step.wrongMoves(), completion(step.finish(), puzzle));
    }

    @Override
    public HintResult hint(String accountId, long attemptId) {
        requireAccount(accountId);
        AttemptRow attempt = state.attempt(accountId, attemptId).orElse(null);
        if (attempt == null) {
            return new HintResult(HintOutcome.NOT_FOUND, null);
        }
        if (attempt.status() != JdbcPerformanceState.Status.ACTIVE) {
            return new HintResult(HintOutcome.FINISHED, null);
        }
        AttemptSolver.HintStep step = solver.hint(attempt, line(puzzle(attempt)), now());
        return new HintResult(step.outcome(), step.fromSquare());
    }

    @Override
    public RevealResult reveal(String accountId, long attemptId) {
        requireAccount(accountId);
        AttemptRow attempt = state.attempt(accountId, attemptId).orElse(null);
        if (attempt == null) {
            return new RevealResult(RevealOutcome.NOT_FOUND, List.of(), null);
        }
        if (attempt.status() != JdbcPerformanceState.Status.ACTIVE) {
            return new RevealResult(RevealOutcome.FINISHED, List.of(), null);
        }
        PuzzleLibrary.Puzzle puzzle = puzzle(attempt);
        AttemptSolver.RevealStep step = solver.reveal(attempt, line(puzzle), now());
        return new RevealResult(
                step.outcome(), step.remainingMoves(), completion(step.finish(), puzzle));
    }

    @Override
    public Optional<Performance> performance(String accountId, long puzzleId) {
        requireAccount(accountId);
        return state.performance(accountId, puzzleId);
    }

    /** Starts a category attempt on one chosen Puzzle; used by the pattern ladders. */
    Optional<Attempt> startInCategory(String accountId, long puzzleId) {
        requireAccount(accountId);
        return start(accountId, puzzleId, Entry.CATEGORY);
    }

    private Optional<Attempt> start(String accountId, long puzzleId, Entry entry) {
        PuzzleLibrary.Puzzle puzzle = library.puzzle(puzzleId).orElse(null);
        if (puzzle == null) {
            return Optional.empty();
        }
        AttemptSolver.SolutionLine line = line(puzzle);
        AttemptSolver.Started started =
                solver.start(accountId, puzzleId, databaseValue(entry), line, now());
        return Optional.of(new Attempt(
                started.attemptId(),
                puzzle.id(),
                puzzle.externalId(),
                puzzle.positionFen(),
                puzzle.setupMove(),
                puzzle.rating(),
                line.ownerMoves(),
                started.fastSolveLimitMillis(),
                started.currentList()));
    }

    private static AttemptSolver.SolutionLine line(PuzzleLibrary.Puzzle puzzle) {
        return new AttemptSolver.SolutionLine(puzzle.positionFen(), puzzle.solutionMoves());
    }

    private static Completion completion(AttemptSolver.Finish finish, PuzzleLibrary.Puzzle puzzle) {
        return finish == null ? null : new Completion(
                finish.previousList(),
                finish.performance(),
                finish.wrongMoves(),
                finish.hintUsed(),
                finish.elapsedMillis(),
                finish.fastSolveLimitMillis(),
                puzzle.gameUrl(),
                puzzle.themes());
    }

    /** Retention keeps every attempted Puzzle, so a missing one is an internal failure. */
    private PuzzleLibrary.Puzzle puzzle(AttemptRow attempt) {
        return library.puzzle(attempt.puzzleId())
                .orElseThrow(() -> new IllegalStateException("Attempted Puzzle is missing"));
    }

    private static boolean validCategory(PuzzleLibrary.Category category) {
        return category != null && category.kind() != null && category.key() != null
                && CATEGORY_KEY.matcher(category.key()).matches();
    }

    private static PuzzleImportBatch.Kind kind(PuzzleLibrary.Category category) {
        return switch (category.kind()) {
            case THEME -> PuzzleImportBatch.Kind.THEME;
            case OPENING_FAMILY -> PuzzleImportBatch.Kind.OPENING_FAMILY;
            case OPENING_VARIATION -> PuzzleImportBatch.Kind.OPENING_VARIATION;
        };
    }

    private static void requireAccount(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Account ID is invalid");
        }
    }

    private static String databaseValue(Entry entry) {
        return JdbcPerformanceState.databaseValue(entry);
    }

    private long now() {
        return clock.instant().toEpochMilli();
    }
}
