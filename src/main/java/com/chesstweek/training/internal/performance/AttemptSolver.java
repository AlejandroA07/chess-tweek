package com.chesstweek.training.internal.performance;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.chesstweek.training.PuzzleTraining.HintOutcome;
import com.chesstweek.training.PuzzleTraining.MoveOutcome;
import com.chesstweek.training.PuzzleTraining.Performance;
import com.chesstweek.training.PuzzleTraining.RevealOutcome;
import com.chesstweek.training.PuzzleTraining.TrainingList;
import com.chesstweek.training.internal.persistence.JdbcPerformanceState;
import com.chesstweek.training.internal.persistence.JdbcPerformanceState.AttemptRow;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

/**
 * Server-checked solving shared by normal and Own Puzzles. An attempt's solving cursor, Wrong
 * Moves, and hint use are stored server-side; Solve Time runs from the start of the attempt until
 * the complete solution is played. Writes are guarded by the expected cursor and Wrong Move count,
 * so a repeated or concurrent request can never count a move or a completion twice. A repetition
 * attempt's demotion is stored as soon as its Wrong Moves or hint decide it. A practice attempt
 * never changes list state.
 */
public final class AttemptSolver {

    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    /** The stored bound; further Wrong Moves change nothing because two already decide. */
    public static final int MAX_WRONG_MOVES = 10_000;

    /**
     * A position with the owner to move and its solution line, which starts and ends with an owner
     * move and alternates with the opponent's replies.
     */
    public record SolutionLine(String positionFen, List<String> moves) {

        public SolutionLine {
            moves = List.copyOf(moves);
        }

        public int ownerMoves() {
            return (moves.size() + 1) / 2;
        }
    }

    /** A new attempt: its list before the attempt (null if new) and its fast-solve limit. */
    public record Started(long attemptId, TrainingList currentList, long fastSolveLimitMillis) {}

    /** The classification an attempt produced. */
    public record Finish(
            TrainingList previousList,
            Performance performance,
            int wrongMoves,
            boolean hintUsed,
            long elapsedMillis,
            long fastSolveLimitMillis) {}

    public record MoveStep(MoveOutcome outcome, String opponentReply, int wrongMoves, Finish finish) {}

    public record HintStep(HintOutcome outcome, String fromSquare) {}

    public record RevealStep(RevealOutcome outcome, List<String> remainingMoves, Finish finish) {}

    private final JdbcPerformanceState state;
    private final String practiceEntry;

    /** {@code practiceEntry} is the stored entry value whose attempts never change list state. */
    public AttemptSolver(JdbcPerformanceState state, String practiceEntry) {
        this.state = state;
        this.practiceEntry = practiceEntry;
    }

    public Started start(
            String accountId, long puzzleId, String entry, SolutionLine line, long nowMillis) {
        TrainingList current = state.performance(accountId, puzzleId)
                .map(Performance::list).orElse(null);
        long limit = PerformanceRules.fastSolveLimitMillis(line.moves().size());
        long attemptId = state.startAttempt(accountId, puzzleId, entry, current, limit, nowMillis);
        return new Started(attemptId, current, limit);
    }

    public MoveStep move(AttemptRow attempt, SolutionLine line, String moveUci, long nowMillis) {
        if (attempt.status() != JdbcPerformanceState.Status.ACTIVE) {
            return new MoveStep(MoveOutcome.FINISHED, null, attempt.wrongMoves(), null);
        }
        List<String> solution = line.moves();
        int ply = attempt.nextPly();
        ChessGame game = position(line, ply);
        Move move = legal(game, moveUci);
        if (move == null) {
            return new MoveStep(MoveOutcome.ILLEGAL, null, attempt.wrongMoves(), null);
        }
        boolean mates = mates(game, move);
        if (!moveUci.equals(solution.get(ply)) && !mates) {
            int wrongMoves = Math.min(attempt.wrongMoves() + 1, MAX_WRONG_MOVES);
            if (!progress(attempt, ply, wrongMoves, attempt.hintUsed(), nowMillis)) {
                return finished(attempt);
            }
            return new MoveStep(MoveOutcome.WRONG, null, wrongMoves, null);
        }
        // Solution lines end on an owner move; the opponent's reply follows each earlier owner
        // move.
        int replyPly = ply + 1;
        if (mates || replyPly >= solution.size() - 1) {
            Finish finish = complete(attempt, solution.size(), attempt.wrongMoves(),
                    attempt.hintUsed(), false, nowMillis);
            if (finish == null) {
                return finished(attempt);
            }
            return new MoveStep(MoveOutcome.SOLVED, null, attempt.wrongMoves(), finish);
        }
        if (!progress(attempt, replyPly + 1, attempt.wrongMoves(), attempt.hintUsed(), nowMillis)) {
            return finished(attempt);
        }
        return new MoveStep(MoveOutcome.CORRECT, solution.get(replyPly), attempt.wrongMoves(), null);
    }

    public HintStep hint(AttemptRow attempt, SolutionLine line, long nowMillis) {
        if (attempt.status() != JdbcPerformanceState.Status.ACTIVE
                || !progress(attempt, attempt.nextPly(), attempt.wrongMoves(), true, nowMillis)) {
            return new HintStep(HintOutcome.FINISHED, null);
        }
        return new HintStep(HintOutcome.SHOWN,
                line.moves().get(attempt.nextPly()).substring(0, 2));
    }

    public RevealStep reveal(AttemptRow attempt, SolutionLine line, long nowMillis) {
        if (attempt.status() != JdbcPerformanceState.Status.ACTIVE) {
            return new RevealStep(RevealOutcome.FINISHED, List.of(), null);
        }
        List<String> solution = line.moves();
        Finish finish = complete(
                attempt, solution.size(), attempt.wrongMoves(), true, true, nowMillis);
        if (finish == null) {
            return new RevealStep(RevealOutcome.FINISHED, List.of(), null);
        }
        return new RevealStep(
                RevealOutcome.REVEALED,
                List.copyOf(solution.subList(attempt.nextPly(), solution.size())),
                finish);
    }

    /**
     * Records progress and applies any repetition demotion the attempt has already decided.
     * Practice never moves a Puzzle.
     */
    private boolean progress(
            AttemptRow attempt, int nextPly, int wrongMoves, boolean hintUsed, long nowMillis) {
        boolean practice = practice(attempt);
        return state.progress(attempt, nextPly, wrongMoves, hintUsed,
                current -> practice
                        ? Optional.empty()
                        : PerformanceRules.immediate(current, wrongMoves, hintUsed),
                nowMillis);
    }

    private Finish complete(
            AttemptRow attempt,
            int finalPly,
            int wrongMoves,
            boolean hintUsed,
            boolean revealed,
            long nowMillis) {
        long elapsed = Math.max(0, nowMillis - attempt.startedAtMillis());
        PerformanceRules.Outcome outcome = new PerformanceRules.Outcome(
                wrongMoves, hintUsed, elapsed, attempt.fastLimitMillis());
        boolean practice = practice(attempt);
        return state.complete(
                        attempt,
                        new JdbcPerformanceState.Completed(
                                finalPly, wrongMoves, hintUsed, revealed, elapsed),
                        practice,
                        current -> practice
                                ? current.orElseThrow(() -> new IllegalStateException(
                                        "Mastered practice needs a Mastered Puzzle"))
                                : PerformanceRules.apply(current, outcome),
                        nowMillis)
                .map(performance -> new Finish(
                        attempt.listBefore(),
                        performance,
                        wrongMoves,
                        hintUsed,
                        elapsed,
                        attempt.fastLimitMillis()))
                .orElse(null);
    }

    private boolean practice(AttemptRow attempt) {
        return practiceEntry.equals(attempt.entry());
    }

    private MoveStep finished(AttemptRow attempt) {
        int wrongMoves = state.attempt(attempt.accountId(), attempt.id())
                .map(AttemptRow::wrongMoves).orElse(0);
        return new MoveStep(MoveOutcome.FINISHED, null, wrongMoves, null);
    }

    /** The position with the owner to move at {@code ply} of the solution line. */
    private static ChessGame position(SolutionLine line, int ply) {
        try {
            ChessGame game = new ChessGame(line.positionFen());
            if (ply > 0) {
                game.playMoves(NotationType.UCI, line.moves().subList(0, ply));
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
}
