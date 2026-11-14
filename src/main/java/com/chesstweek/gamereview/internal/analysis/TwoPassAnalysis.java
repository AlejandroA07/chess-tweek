package com.chesstweek.gamereview.internal.analysis;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Judges every move of the requested sides in two passes. The quick pass searches each position
 * once at the quick node budget. The deep pass searches both positions around every move that
 * lost at least 5 percentage points or changed the forced-mate state again at the deep budget,
 * and that move's verdict then comes from the deep searches alone. A position shared by two
 * moves is searched once per budget. Any failed search fails the whole analysis.
 */
public final class TwoPassAnalysis {

    private final PositionSearch search;
    private final long quickNodes;
    private final long deepNodes;

    public TwoPassAnalysis(PositionSearch search, long quickNodes, long deepNodes) {
        this.search = Objects.requireNonNull(search);
        if (quickNodes <= 0 || deepNodes < quickNodes) {
            throw new IllegalArgumentException("Node budgets must be positive with deep at least quick");
        }
        this.quickNodes = quickNodes;
        this.deepNodes = deepNodes;
    }

    public List<MoveFinding> analyze(
            ReplayedGame game, GamePhases phases, Set<ReplayedGame.Color> sides)
            throws SearchFailedException {
        return analyze(game, phases, sides, Progress.IGNORED);
    }

    /**
     * Reports progress as moves judged: first through the quick pass, then through the moves
     * the deep pass checks again.
     */
    public List<MoveFinding> analyze(
            ReplayedGame game, GamePhases phases, Set<ReplayedGame.Color> sides, Progress progress)
            throws SearchFailedException {
        Objects.requireNonNull(game);
        Objects.requireNonNull(phases);
        Objects.requireNonNull(progress);
        if (sides == null || sides.isEmpty()) {
            throw new IllegalArgumentException("At least one side is analyzed");
        }
        Set<ReplayedGame.Color> requested = EnumSet.copyOf(sides);
        List<ReplayedGame.PlayedMove> moves = game.moves().stream()
                .filter(move -> requested.contains(move.mover()))
                .toList();
        var quick = new Searches(quickNodes);
        var findings = new ArrayList<MoveFinding>(moves.size());
        progress.quick(0, moves.size());
        for (ReplayedGame.PlayedMove move : moves) {
            findings.add(judge(game, phases, move, quick, Pass.QUICK));
            progress.quick(findings.size(), moves.size());
        }
        List<Integer> flagged = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            if (findings.get(i).verdict().needsDeepPass()) {
                flagged.add(i);
            }
        }
        var deep = new Searches(deepNodes);
        progress.deep(0, flagged.size());
        for (int done = 0; done < flagged.size(); done++) {
            int index = flagged.get(done);
            findings.set(index, judge(game, phases, findings.get(index).move(), deep, Pass.DEEP));
            progress.deep(done + 1, flagged.size());
        }
        return List.copyOf(findings);
    }

    private MoveFinding judge(
            ReplayedGame game,
            GamePhases phases,
            ReplayedGame.PlayedMove move,
            Searches searches,
            Pass pass) throws SearchFailedException {
        ReplayedGame.Position beforePosition = game.before(move.ply());
        Evaluation before = searches.of(beforePosition);
        ReplayedGame.Position afterPosition = game.after(move.ply());
        MoveRules.After after = afterPosition.terminal() == ReplayedGame.Terminal.NONE
                ? new MoveRules.After.Searched(searches.of(afterPosition))
                : new MoveRules.After.Finished(afterPosition.terminal());
        return new MoveFinding(
                move,
                beforePosition.fen(),
                phases.phaseOfMove(move.ply()),
                pass,
                before,
                after,
                MoveRules.judge(move.uci(), before, after));
    }

    /** One budget's searches, cached by position so shared positions are searched once. */
    private final class Searches {

        private final long nodes;
        private final Map<Integer, Evaluation> byPosition = new HashMap<>();

        Searches(long nodes) {
            this.nodes = nodes;
        }

        Evaluation of(ReplayedGame.Position position) throws SearchFailedException {
            Evaluation cached = byPosition.get(position.index());
            if (cached != null) {
                return cached;
            }
            Evaluation evaluation = search.search(position, nodes);
            if (evaluation == null) {
                throw new SearchFailedException("Search returned no evaluation");
            }
            byPosition.put(position.index(), evaluation);
            return evaluation;
        }
    }

    /** Runs one fixed-node search of a position that has legal moves. */
    @FunctionalInterface
    public interface PositionSearch {

        Evaluation search(ReplayedGame.Position position, long nodes) throws SearchFailedException;
    }

    public static final class SearchFailedException extends Exception {

        public SearchFailedException(String message) {
            super(message);
        }
    }

    /** Receives moves judged so far out of the moves in the current pass. */
    public interface Progress {

        Progress IGNORED = new Progress() {
            @Override
            public void quick(int judged, int total) {}

            @Override
            public void deep(int judged, int total) {}
        };

        void quick(int judged, int total);

        void deep(int judged, int total);
    }

    public enum Pass {
        QUICK,
        DEEP
    }

    /**
     * The verdict for one played move and the searches it came from. {@code after} is a finished
     * game when the move ended it on the board.
     */
    public record MoveFinding(
            ReplayedGame.PlayedMove move,
            String fenBefore,
            GamePhases.Phase phase,
            Pass pass,
            Evaluation before,
            MoveRules.After after,
            MoveRules.Verdict verdict) {

        public MoveFinding {
            Objects.requireNonNull(move);
            Objects.requireNonNull(fenBefore);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(pass);
            Objects.requireNonNull(before);
            Objects.requireNonNull(after);
            Objects.requireNonNull(verdict);
        }
    }
}
