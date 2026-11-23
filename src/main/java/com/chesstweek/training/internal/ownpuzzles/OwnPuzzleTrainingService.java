package com.chesstweek.training.internal.ownpuzzles;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.training.OwnPuzzleTraining;
import com.chesstweek.training.PuzzleTraining.HintOutcome;
import com.chesstweek.training.PuzzleTraining.HintResult;
import com.chesstweek.training.PuzzleTraining.ListCount;
import com.chesstweek.training.PuzzleTraining.MoveOutcome;
import com.chesstweek.training.PuzzleTraining.Performance;
import com.chesstweek.training.PuzzleTraining.RevealOutcome;
import com.chesstweek.training.PuzzleTraining.Session;
import com.chesstweek.training.PuzzleTraining.SessionOutcome;
import com.chesstweek.training.PuzzleTraining.TrainingList;
import com.chesstweek.training.internal.performance.AttemptSolver;
import com.chesstweek.training.internal.persistence.JdbcOwnPuzzleTrainingStore;
import com.chesstweek.training.internal.persistence.JdbcOwnPuzzleTrainingStore.PuzzleRow;
import com.chesstweek.training.internal.persistence.JdbcPerformanceState;
import com.chesstweek.training.internal.persistence.JdbcPerformanceState.AttemptRow;

/**
 * Performance training of Own Puzzles. Solving is the shared {@link AttemptSolver} over Own
 * Puzzle tables, so the rules match normal Puzzles while attempts, lists, and sessions stay
 * separate. A first attempt starts only from New Own Puzzles; later attempts come from sessions.
 */
@Service
class OwnPuzzleTrainingService implements OwnPuzzleTraining {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern THEME = Pattern.compile("[a-z_]{1,40}");
    private static final List<String> PHASES = List.of("opening", "middlegame", "endgame");
    private static final int MAX_OPENING_FAMILY = 160;
    static final int MAX_PAGE = 50;
    private static final String NEW = "new";
    private static final String REPETITION = "repetition";
    private static final String MASTERED_PRACTICE = "mastered_practice";

    private final JdbcOwnPuzzleTrainingStore store;
    private final JdbcPerformanceState state;
    private final AttemptSolver solver;
    private final Clock clock;
    private final Random shuffle = new SecureRandom();

    @Autowired
    OwnPuzzleTrainingService(JdbcOwnPuzzleTrainingStore store) {
        this(store, Clock.systemUTC());
    }

    OwnPuzzleTrainingService(JdbcOwnPuzzleTrainingStore store, Clock clock) {
        this.store = store;
        this.state = store.state();
        this.solver = new AttemptSolver(state, MASTERED_PRACTICE);
        this.clock = clock;
    }

    @Override
    public Overview overview(String accountId) {
        requireAccount(accountId);
        Map<TrainingList, Integer> counts = state.listCounts(accountId);
        List<ListCount> lists = new ArrayList<>();
        for (TrainingList list : TrainingList.values()) {
            lists.add(new ListCount(list, counts.getOrDefault(list, 0)));
        }
        return new Overview(store.countNew(accountId), store.countDiscarded(accountId),
                List.copyOf(lists), groups(store.kept(accountId)));
    }

    @Override
    public Page newPuzzles(String accountId, int offset, int limit) {
        requireAccount(accountId);
        requirePage(offset, limit);
        return new Page(store.countNew(accountId),
                store.newPage(accountId, offset, limit).stream().map(OwnPuzzleTrainingService::summary).toList());
    }

    @Override
    public Page discarded(String accountId, int offset, int limit) {
        requireAccount(accountId);
        requirePage(offset, limit);
        return new Page(store.countDiscarded(accountId),
                store.discardedPage(accountId, offset, limit).stream().map(OwnPuzzleTrainingService::summary).toList());
    }

    @Override
    public ChangeOutcome discard(String accountId, long ownPuzzleId) {
        requireAccount(accountId);
        return store.discard(accountId, ownPuzzleId, now());
    }

    @Override
    public ChangeOutcome restore(String accountId, long ownPuzzleId) {
        requireAccount(accountId);
        return store.restore(accountId, ownPuzzleId);
    }

    @Override
    public Optional<Attempt> startNext(String accountId) {
        requireAccount(accountId);
        return store.newestNew(accountId).flatMap(id -> start(accountId, id));
    }

    @Override
    public Optional<Attempt> start(String accountId, long ownPuzzleId) {
        requireAccount(accountId);
        // Checked in the attempt's transaction, so a concurrent Discard cannot slip between.
        return store.inTransaction(() -> store.isNew(accountId, ownPuzzleId)
                ? store.puzzle(accountId, ownPuzzleId).map(row -> begin(accountId, row, NEW))
                : Optional.<Attempt>empty());
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
        PuzzleRow puzzle = puzzle(attempt);
        AttemptSolver.MoveStep step = solver.move(attempt, line(puzzle), moveUci, now());
        return new MoveResult(step.outcome(), step.opponentReply(), step.wrongMoves(),
                completion(step.finish(), puzzle));
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
        PuzzleRow puzzle = puzzle(attempt);
        AttemptSolver.RevealStep step = solver.reveal(attempt, line(puzzle), now());
        return new RevealResult(step.outcome(), step.remainingMoves(),
                completion(step.finish(), puzzle));
    }

    @Override
    public Optional<Performance> performance(String accountId, long ownPuzzleId) {
        requireAccount(accountId);
        return state.performance(accountId, ownPuzzleId);
    }

    @Override
    public Groups mastered(String accountId) {
        requireAccount(accountId);
        return groups(store.inList(accountId, TrainingList.MASTERED));
    }

    @Override
    public Optional<Session> startSession(String accountId, TrainingList list) {
        requireAccount(accountId);
        if (list == null || list == TrainingList.MASTERED) {
            return Optional.empty();
        }
        return state.startSession(accountId, list, null, state.puzzlesInList(accountId, list),
                        shuffle, now())
                .map(row -> new Session(row.id(), row.list(), row.total()));
    }

    @Override
    public Optional<Session> startMasteredSession(String accountId, GroupKind kind, String key) {
        requireAccount(accountId);
        if (!validGroup(kind, key)) {
            return Optional.empty();
        }
        List<Long> puzzles = store.inList(accountId, TrainingList.MASTERED).stream()
                .filter(row -> keys(kind, row).contains(key))
                .map(PuzzleRow::id)
                .toList();
        String group = kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + key;
        return state.startSession(accountId, TrainingList.MASTERED, group, puzzles, shuffle, now())
                .map(row -> new Session(row.id(), row.list(), row.total()));
    }

    @Override
    public SessionAttempt nextInSession(String accountId, long sessionId) {
        requireAccount(accountId);
        var session = state.session(accountId, sessionId).orElse(null);
        if (session == null) {
            return new SessionAttempt(SessionOutcome.NOT_FOUND, null, 0, 0);
        }
        String entry = session.list() == TrainingList.MASTERED ? MASTERED_PRACTICE : REPETITION;
        while (true) {
            var item = state.presentNext(accountId, session, now()).orElse(null);
            if (item == null) {
                return new SessionAttempt(SessionOutcome.COMPLETE, null, 0, session.total());
            }
            Optional<PuzzleRow> puzzle = store.puzzle(accountId, item.puzzleId());
            if (puzzle.isPresent()) {
                return new SessionAttempt(SessionOutcome.STARTED,
                        begin(accountId, puzzle.get(), entry), item.position(), item.total());
            }
        }
    }

    private Attempt begin(String accountId, PuzzleRow puzzle, String entry) {
        AttemptSolver.SolutionLine line = line(puzzle);
        AttemptSolver.Started started = solver.start(accountId, puzzle.id(), entry, line, now());
        return new Attempt(
                started.attemptId(),
                summary(puzzle),
                puzzle.positionFen(),
                puzzle.previousMoveUci(),
                line.ownerMoves(),
                started.fastSolveLimitMillis(),
                started.currentList());
    }

    /** An attempted Own Puzzle is never deleted, so a missing one is an internal failure. */
    private PuzzleRow puzzle(AttemptRow attempt) {
        return store.puzzle(attempt.accountId(), attempt.puzzleId())
                .orElseThrow(() -> new IllegalStateException("Attempted Own Puzzle is missing"));
    }

    private static AttemptSolver.SolutionLine line(PuzzleRow puzzle) {
        return new AttemptSolver.SolutionLine(puzzle.positionFen(), puzzle.solution());
    }

    private static Completion completion(AttemptSolver.Finish finish, PuzzleRow puzzle) {
        return finish == null ? null : new Completion(
                finish.previousList(),
                finish.performance(),
                finish.wrongMoves(),
                finish.hintUsed(),
                finish.elapsedMillis(),
                finish.fastSolveLimitMillis(),
                source(puzzle));
    }

    private static OwnPuzzle summary(PuzzleRow row) {
        return new OwnPuzzle(
                row.id(),
                row.label(),
                row.phase(),
                OpeningFamily.of(row.openingName()),
                row.openingName(),
                row.tactics().stream().map(OwnPuzzleTrainingService::tactic).toList(),
                source(row),
                Instant.ofEpochMilli(row.gamePlayedAtMillis()));
    }

    private static SourceGame source(PuzzleRow row) {
        return new SourceGame(row.gameSource(), row.gameId(), row.sourcePly());
    }

    private static Tactic tactic(String stored) {
        int slash = stored.indexOf('/');
        return new Tactic(stored.substring(0, slash), stored.substring(slash + 1));
    }

    /** Opening families by size, phases in game order, and tactic themes by size. */
    private static Groups groups(List<PuzzleRow> rows) {
        return new Groups(
                rows.size(),
                count(rows, row -> keys(GroupKind.OPENING, row), true),
                count(rows, row -> keys(GroupKind.PHASE, row), false),
                count(rows, row -> keys(GroupKind.TACTIC, row), true));
    }

    private static List<GroupCount> count(
            List<PuzzleRow> rows, Function<PuzzleRow, Set<String>> keys, boolean bySize) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (!bySize) {
            PHASES.forEach(phase -> counts.put(phase, 0));
        }
        for (PuzzleRow row : rows) {
            for (String key : keys.apply(row)) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        List<GroupCount> groups = new ArrayList<>();
        counts.forEach((key, puzzles) -> {
            if (puzzles > 0) {
                groups.add(new GroupCount(key, puzzles));
            }
        });
        if (bySize) {
            groups.sort(Comparator.comparingInt(GroupCount::puzzles).reversed()
                    .thenComparing(GroupCount::key));
        }
        return List.copyOf(groups);
    }

    private static Set<String> keys(GroupKind kind, PuzzleRow row) {
        return switch (kind) {
            case OPENING -> Set.of(OpeningFamily.of(row.openingName()));
            case PHASE -> Set.of(row.phase());
            case TACTIC -> {
                Set<String> themes = new LinkedHashSet<>();
                row.tactics().forEach(stored -> themes.add(tactic(stored).theme()));
                yield themes;
            }
        };
    }

    private static boolean validGroup(GroupKind kind, String key) {
        if (kind == null || key == null) {
            return false;
        }
        return switch (kind) {
            case PHASE -> PHASES.contains(key);
            case TACTIC -> THEME.matcher(key).matches();
            case OPENING -> !key.isBlank() && key.length() <= MAX_OPENING_FAMILY
                    && key.equals(key.strip())
                    && key.chars().noneMatch(Character::isISOControl);
        };
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("Page is invalid");
        }
    }

    private static void requireAccount(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("Account ID is invalid");
        }
    }

    private long now() {
        return clock.instant().toEpochMilli();
    }
}
