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
 * the complete solution is played. Writes are guarded by the expected cursor and Wrong Move count,
 * so a repeated or concurrent request can never count a move or a completion twice. A repetition
 * attempt's demotion is stored as soon as its Wrong Moves or hint decide it.
 */
@Service
public class PuzzleTrainingService implements PuzzleTraining {

    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern CATEGORY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,120}");
    /** The stored bound; further Wrong Moves change nothing because two already decide. */
    static final int MAX_WRONG_MOVES = 10_000;

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
        PuzzleLibrary.Puzzle puzzle = puzzle(attempt);
        List<String> solution = puzzle.solutionMoves();
        int ply = attempt.nextPly();
        ChessGame game = position(puzzle, ply);
        Move move = legal(game, moveUci);
        if (move == null) {
            return new MoveResult(MoveOutcome.ILLEGAL, null, attempt.wrongMoves(), null);
        }
        boolean mates = mates(game, move);
        if (!moveUci.equals(solution.get(ply)) && !mates) {
            int wrongMoves = Math.min(attempt.wrongMoves() + 1, MAX_WRONG_MOVES);
            if (!progress(attempt, ply, wrongMoves, attempt.hintUsed())) {
                return finished(accountId, attemptId);
            }
            return new MoveResult(MoveOutcome.WRONG, null, wrongMoves, null);
        }
        // Lichess solution lines end on an owner move; the opponent's reply follows each
        // earlier owner move.
        int replyPly = ply + 1;
        if (mates || replyPly >= solution.size() - 1) {
            Completion completion = complete(attempt, puzzle, solution.size(),
                    attempt.wrongMoves(), attempt.hintUsed(), false);
            if (completion == null) {
                return finished(accountId, attemptId);
            }
            return new MoveResult(MoveOutcome.SOLVED, null, attempt.wrongMoves(), completion);
        }
        if (!progress(attempt, replyPly + 1, attempt.wrongMoves(), attempt.hintUsed())) {
            return finished(accountId, attemptId);
        }
        return new MoveResult(
                MoveOutcome.CORRECT, solution.get(replyPly), attempt.wrongMoves(), null);
    }

    @Override
    public HintResult hint(String accountId, long attemptId) {
        requireAccount(accountId);
        AttemptRow attempt = store.attempt(accountId, attemptId).orElse(null);
        if (attempt == null) {
            return new HintResult(HintOutcome.NOT_FOUND, null);
        }
        if (attempt.status() != JdbcPuzzlePerformanceStore.Status.ACTIVE
                || !progress(attempt, attempt.nextPly(), attempt.wrongMoves(), true)) {
            return new HintResult(HintOutcome.FINISHED, null);
        }
        return new HintResult(HintOutcome.SHOWN,
                puzzle(attempt).solutionMoves().get(attempt.nextPly()).substring(0, 2));
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
        PuzzleLibrary.Puzzle puzzle = puzzle(attempt);
        List<String> solution = puzzle.solutionMoves();
        Completion completion = complete(
                attempt, puzzle, solution.size(), attempt.wrongMoves(), true, true);
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

    /** Records progress and applies any repetition demotion the attempt has already decided. */
    private boolean progress(AttemptRow attempt, int nextPly, int wrongMoves, boolean hintUsed) {
        return store.progress(attempt, nextPly, wrongMoves, hintUsed,
                current -> PerformanceRules.immediate(current, wrongMoves, hintUsed), now());
    }

    private Completion complete(
            AttemptRow attempt,
            PuzzleLibrary.Puzzle puzzle,
            int finalPly,
            int wrongMoves,
            boolean hintUsed,
            boolean revealed) {
        long now = now();
        long elapsed = Math.max(0, now - attempt.startedAtMillis());
        PerformanceRules.Outcome outcome = new PerformanceRules.Outcome(
                wrongMoves, hintUsed, elapsed, attempt.fastLimitMillis());
        return store.complete(
                        attempt,
                        new JdbcPuzzlePerformanceStore.Completed(
                                finalPly, wrongMoves, hintUsed, revealed, elapsed),
                        current -> PerformanceRules.apply(current, outcome),
                        now)
                .map(performance -> new Completion(
                        attempt.listBefore(),
                        performance,
                        wrongMoves,
                        hintUsed,
                        elapsed,
                        attempt.fastLimitMillis(),
                        puzzle.gameUrl(),
                        puzzle.themes()))
                .orElse(null);
    }

    /** Retention keeps every attempted Puzzle, so a missing one is an internal failure. */
    private PuzzleLibrary.Puzzle puzzle(AttemptRow attempt) {
        return library.puzzle(attempt.puzzleId())
                .orElseThrow(() -> new IllegalStateException("Attempted Puzzle is missing"));
    }

    private MoveResult finished(String accountId, long attemptId) {
        int wrongMoves = store.attempt(accountId, attemptId).map(AttemptRow::wrongMoves).orElse(0);
        return new MoveResult(MoveOutcome.FINISHED, null, wrongMoves, null);
    }

    /** The position with the owner to move at {@code ply} of the solution line. */
    private static ChessGame position(PuzzleLibrary.Puzzle puzzle, int ply) {
        try {
            ChessGame game = new ChessGame(puzzle.positionFen());
            if (ply > 0) {
                game.playMoves(NotationType.UCI, puzzle.solutionMoves().subList(0, ply));
            }
            return game;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored Puzzle line is invalid", exception);
        }
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
