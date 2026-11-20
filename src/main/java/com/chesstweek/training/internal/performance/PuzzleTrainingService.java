package com.chesstweek.training.internal.performance;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.internal.library.PuzzleImportBatch;
import com.chesstweek.training.internal.persistence.JdbcPuzzlePerformanceStore;
import com.chesstweek.training.internal.persistence.JdbcPuzzlePerformanceStore.AttemptRow;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

/**
 * Server-checked solving of normal Puzzles. An attempt's solving cursor, Wrong Moves, and hint use
 * are stored server-side; Solve Time runs from the start of the attempt on the server clock until
 * the complete solution is played. Writes are guarded by the expected cursor, so a repeated or
 * concurrent request can never count a move or a completion twice.
 */
@Service
public class PuzzleTrainingService implements PuzzleTraining {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern CATEGORY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,120}");

    private final PuzzleLibrary library;
    private final JdbcPuzzlePerformanceStore store;
    private final Clock clock;

    @Autowired
    public PuzzleTrainingService(PuzzleLibrary library, JdbcPuzzlePerformanceStore store) {
        this(library, store, Clock.systemUTC());
    }

    PuzzleTrainingService(PuzzleLibrary library, JdbcPuzzlePerformanceStore store, Clock clock) {
        this.library = library;
        this.store = store;
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
        if (category == null || category.kind() == null || category.key() == null
                || !CATEGORY_KEY.matcher(category.key()).matches()) {
            return Optional.empty();
        }
        PuzzleImportBatch.Kind kind = switch (category.kind()) {
            case THEME -> PuzzleImportBatch.Kind.THEME;
            case OPENING_FAMILY -> PuzzleImportBatch.Kind.OPENING_FAMILY;
            case OPENING_VARIATION -> PuzzleImportBatch.Kind.OPENING_VARIATION;
        };
        return store.randomInCategory(accountId, kind, category.key(), ThreadLocalRandom.current())
                .flatMap(puzzleId -> start(accountId, puzzleId, Entry.CATEGORY));
    }

    @Override
    public MoveResult move(String accountId, long attemptId, String moveUci) {
        requireAccount(accountId);
        AttemptRow attempt = store.attempt(accountId, attemptId).orElse(null);
        if (attempt == null) {
            return new MoveResult(MoveOutcome.NOT_FOUND, null, 0, null);
        }
        if (attempt.status() != JdbcPuzzlePerformanceStore.Status.ACTIVE) {
            return new MoveResult(MoveOutcome.FINISHED, null, attempt.wrongMoves(), null);
        }
        PuzzleLibrary.Puzzle puzzle = library.puzzle(attempt.puzzleId()).orElseThrow();
        List<String> solution = puzzle.solutionMoves();
        int ply = attempt.nextPly();
        ChessGame game = position(puzzle, ply);
        Move move = legal(game, moveUci);
        if (move == null) {
            return new MoveResult(MoveOutcome.ILLEGAL, null, attempt.wrongMoves(), null);
        }
        boolean correct = moveUci.equals(solution.get(ply)) || mates(game, move);
        if (!correct) {
            int wrongMoves = attempt.wrongMoves() + 1;
            if (!store.progress(attempt.id(), ply, ply, wrongMoves, attempt.hintUsed())) {
                return finished(accountId, attemptId);
            }
            return new MoveResult(MoveOutcome.WRONG, null, wrongMoves, null);
        }
        int afterOwner = ply + 1;
        if (mates(game, move) || afterOwner + 1 >= solution.size()) {
            Completion completion = complete(
                    attempt, puzzle, ply, solution.size(), attempt.wrongMoves(),
                    attempt.hintUsed(), false);
            if (completion == null) {
                return finished(accountId, attemptId);
            }
            return new MoveResult(MoveOutcome.SOLVED, null, attempt.wrongMoves(), completion);
        }
        String reply = solution.get(afterOwner);
        if (!store.progress(
                attempt.id(), ply, afterOwner + 1, attempt.wrongMoves(), attempt.hintUsed())) {
            return finished(accountId, attemptId);
        }
        return new MoveResult(MoveOutcome.CORRECT, reply, attempt.wrongMoves(), null);
    }

    @Override
    public HintResult hint(String accountId, long attemptId) {
        requireAccount(accountId);
        AttemptRow attempt = store.attempt(accountId, attemptId).orElse(null);
        if (attempt == null) {
            return new HintResult(HintOutcome.NOT_FOUND, null);
        }
        if (attempt.status() != JdbcPuzzlePerformanceStore.Status.ACTIVE
                || !store.progress(attempt.id(), attempt.nextPly(), attempt.nextPly(),
                        attempt.wrongMoves(), true)) {
            return new HintResult(HintOutcome.FINISHED, null);
        }
        PuzzleLibrary.Puzzle puzzle = library.puzzle(attempt.puzzleId()).orElseThrow();
        return new HintResult(
                HintOutcome.SHOWN, puzzle.solutionMoves().get(attempt.nextPly()).substring(0, 2));
    }

    @Override
    public RevealResult reveal(String accountId, long attemptId) {
        requireAccount(accountId);
        AttemptRow attempt = store.attempt(accountId, attemptId).orElse(null);
        if (attempt == null) {
            return new RevealResult(RevealOutcome.NOT_FOUND, List.of(), null);
        }
        if (attempt.status() != JdbcPuzzlePerformanceStore.Status.ACTIVE) {
            return new RevealResult(RevealOutcome.FINISHED, List.of(), null);
        }
        PuzzleLibrary.Puzzle puzzle = library.puzzle(attempt.puzzleId()).orElseThrow();
        List<String> solution = puzzle.solutionMoves();
        Completion completion = complete(
                attempt, puzzle, attempt.nextPly(), solution.size(), attempt.wrongMoves(),
                true, true);
        if (completion == null) {
            return new RevealResult(RevealOutcome.FINISHED, List.of(), null);
        }
        return new RevealResult(
                RevealOutcome.REVEALED,
                List.copyOf(solution.subList(attempt.nextPly(), solution.size())),
                completion);
    }

    @Override
    public Optional<Performance> performance(String accountId, long puzzleId) {
        requireAccount(accountId);
        return store.performance(accountId, puzzleId);
    }

    private Optional<Attempt> start(String accountId, long puzzleId, Entry entry) {
        PuzzleLibrary.Puzzle puzzle = library.puzzle(puzzleId).orElse(null);
        if (puzzle == null) {
            return Optional.empty();
        }
        TrainingList current = store.performance(accountId, puzzleId)
                .map(Performance::list).orElse(null);
        int plies = puzzle.solutionMoves().size();
        long limit = PerformanceRules.fastSolveLimitMillis(plies);
        long attemptId = store.startAttempt(accountId, puzzleId, entry, current, limit, now());
        return Optional.of(new Attempt(
                attemptId,
                puzzle.id(),
                puzzle.externalId(),
                puzzle.positionFen(),
                puzzle.setupMove(),
                puzzle.rating(),
                (plies + 1) / 2,
                limit,
                current));
    }

    private Completion complete(
            AttemptRow attempt,
            PuzzleLibrary.Puzzle puzzle,
            int expectedPly,
            int finalPly,
            int wrongMoves,
            boolean hintUsed,
            boolean revealed) {
        long now = now();
        long elapsed = Math.max(0, now - attempt.startedAtMillis());
        Optional<Performance> previous = store.performance(attempt.accountId(), attempt.puzzleId());
        PerformanceRules.State next = PerformanceRules.apply(
                previous.map(value -> new PerformanceRules.State(
                        value.list(), value.successes(), value.strikes())),
                new PerformanceRules.Outcome(
                        wrongMoves, hintUsed, elapsed, attempt.fastLimitMillis()));
        Performance performance = new Performance(next.list(), next.successes(), next.strikes());
        if (!store.complete(attempt, expectedPly, finalPly, wrongMoves, hintUsed, revealed,
                elapsed, performance, now)) {
            return null;
        }
        return new Completion(
                previous.map(Performance::list).orElse(null),
                performance,
                wrongMoves,
                hintUsed,
                elapsed,
                attempt.fastLimitMillis(),
                puzzle.gameUrl(),
                puzzle.themes());
    }

    private MoveResult finished(String accountId, long attemptId) {
        int wrongMoves = store.attempt(accountId, attemptId).map(AttemptRow::wrongMoves).orElse(0);
        return new MoveResult(MoveOutcome.FINISHED, null, wrongMoves, null);
    }

    /** The position with the owner to move at {@code ply} of the solution line. */
    private static ChessGame position(PuzzleLibrary.Puzzle puzzle, int ply) {
        ChessGame game = new ChessGame(puzzle.positionFen());
        if (ply > 0) {
            game.playMoves(NotationType.UCI, puzzle.solutionMoves().subList(0, ply));
        }
        return game;
    }

    private static Move legal(ChessGame game, String moveUci) {
        if (moveUci == null || !UCI.matcher(moveUci).matches()) {
            return null;
        }
        try {
            Move move = game.getMove(NotationType.UCI, moveUci);
            return game.getLegalMoves().contains(move) ? move : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Any mating move solves the Puzzle, as on Lichess. */
    private static boolean mates(ChessGame game, Move move) {
        ChessGame after = game.clone();
        after.playMove(move);
        return after.isKingAttacked() && after.getLegalMoves().isEmpty();
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
