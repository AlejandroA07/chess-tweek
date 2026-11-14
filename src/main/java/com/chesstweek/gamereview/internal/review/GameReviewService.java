package com.chesstweek.gamereview.internal.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;
import org.springframework.stereotype.Service;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameReviews;
import com.chesstweek.gamereview.internal.analysis.ExpectedScore;
import com.chesstweek.gamereview.internal.analysis.GamePhases;
import com.chesstweek.gamereview.internal.analysis.ReplayedGame;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionReader;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionReader.StoredLine;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionReader.StoredMove;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionReader.StoredVersion;
import com.chesstweek.gamereview.internal.persistence.AnalysisVersionStore;
import com.chesstweek.gamereview.internal.persistence.GameLookup;

/** Builds a review from the stored game and the newest stored analysis of one side. */
@Service
class GameReviewService implements GameReviews {

    /** Centipawn losses are capped per move, and a mate counts as this many centipawns. */
    static final int CENTIPAWN_CAP = 1_000;
    private static final List<String> PHASES = List.of("opening", "middlegame", "endgame");

    private final GameLookup games;
    private final AnalysisVersionReader versions;

    GameReviewService(GameLookup games, AnalysisVersionReader versions) {
        this.games = games;
        this.versions = versions;
    }

    @Override
    public Optional<GameReview> review(String accountId, String gameId, Side side) {
        CatalogGame game = games.find(accountId, gameId).orElse(null);
        if (game == null) {
            return Optional.empty();
        }
        if (!game.analysisEligible()) {
            return Optional.of(new GameReview(game, null, List.of(), null, null, null, null));
        }
        ReplayedGame replay = ReplayedGame.fromSan(game.movesSan());
        StoredVersion mine = versions.newest(accountId, gameId, AnalysisVersionStore.Side.OWNER).orElse(null);
        StoredVersion theirs = versions.newest(accountId, gameId, AnalysisVersionStore.Side.OPPONENT).orElse(null);
        Side chosen = side != null ? side : mine != null ? Side.MY_MOVES : theirs != null ? Side.OPPONENT_MOVES : null;
        StoredVersion selected = chosen == Side.MY_MOVES ? mine : chosen == Side.OPPONENT_MOVES ? theirs : null;

        Phases phases = selected != null
                ? new Phases(selected.phaseSource(), selected.middlegameStart(), selected.endgameStart())
                : phases(GamePhases.of(game.phaseDivision(), replay));
        return Optional.of(new GameReview(
                game,
                replay.positions().getFirst().fen(),
                plies(replay),
                phases,
                info(mine),
                info(theirs),
                selected == null ? null : sideReview(chosen, selected, versions.moves(selected.id()))));
    }

    private static List<Ply> plies(ReplayedGame replay) {
        return replay.moves().stream()
                .map(move -> new Ply(
                        move.ply(),
                        move.mover() == ReplayedGame.Color.WHITE ? "white" : "black",
                        move.san(),
                        move.uci(),
                        replay.after(move.ply()).fen()))
                .toList();
    }

    private static Phases phases(GamePhases phases) {
        return new Phases(
                phases.source() == GamePhases.Source.SUPPLIED ? "supplied" : "inferred",
                phases.middlegameStart(),
                phases.endgameStart());
    }

    private static VersionInfo info(StoredVersion version) {
        return version == null ? null
                : new VersionInfo(version.id(), version.completedAt(), version.currentProfile(),
                        version.moveCount(), version.inaccuracies(), version.mistakes(), version.blunders());
    }

    private static SideReview sideReview(Side side, StoredVersion version, List<StoredMove> stored) {
        List<JudgedMove> moves = stored.stream().map(GameReviewService::judgedMove).toList();
        return new SideReview(
                side,
                version.id(),
                version.analyzedColor(),
                version.engineName(),
                version.quickNodes(),
                version.deepNodes(),
                version.completedAt(),
                version.currentProfile(),
                statistics(stored),
                evaluations(stored),
                moves);
    }

    private static JudgedMove judgedMove(StoredMove move) {
        return new JudgedMove(
                move.ply(),
                move.san(),
                move.uci(),
                move.fenBefore(),
                move.phase(),
                move.pass(),
                move.label(),
                move.mateEvent(),
                move.playedBest(),
                move.bestMove(),
                firstSan(move.fenBefore(), move.bestMove()),
                move.expectedBefore(),
                move.expectedAfter(),
                move.loss(),
                move.afterTerminal(),
                lines(move.fenBefore(), move.before()),
                move.after().isEmpty() ? List.of() : lines(fenAfter(move), move.after()));
    }

    private static List<Line> lines(String fen, List<StoredLine> stored) {
        return stored.stream()
                .map(line -> {
                    List<String> pvSan = san(fen, line.pv());
                    return new Line(
                            line.move(),
                            pvSan.isEmpty() ? line.move() : pvSan.getFirst(),
                            line.cp(),
                            line.mate(),
                            line.win(),
                            line.draw(),
                            line.loss(),
                            line.pv(),
                            pvSan);
                })
                .toList();
    }

    /** SAN for a UCI variation from {@code fen}, stopping at the first move that is not legal. */
    static List<String> san(String fen, List<String> uciMoves) {
        var game = new ChessGame(fen);
        var sans = new ArrayList<String>(uciMoves.size());
        for (String uci : uciMoves) {
            Move move;
            try {
                move = game.getMove(NotationType.UCI, uci);
            } catch (RuntimeException exception) {
                break;
            }
            if (move == null || !game.isLegalMove(move)) {
                break;
            }
            sans.add(game.getNotation(NotationType.SAN, move));
            game.playMove(move);
        }
        return List.copyOf(sans);
    }

    private static String firstSan(String fen, String uci) {
        List<String> sans = san(fen, List.of(uci));
        return sans.isEmpty() ? uci : sans.getFirst();
    }

    private static String fenAfter(StoredMove move) {
        var game = new ChessGame(move.fenBefore());
        game.playMove(NotationType.UCI, move.uci());
        return game.getFen();
    }

    static Statistics statistics(List<StoredMove> moves) {
        var byPhase = new LinkedHashMap<String, PhaseStatistics>();
        for (String phase : PHASES) {
            List<StoredMove> inPhase = moves.stream().filter(move -> move.phase().equals(phase)).toList();
            byPhase.put(phase, new PhaseStatistics(
                    inPhase.size(),
                    count(inPhase, "inaccuracy"),
                    count(inPhase, "mistake"),
                    count(inPhase, "blunder"),
                    averageLoss(inPhase)));
        }
        double centipawnLoss = moves.isEmpty() ? 0 : round(moves.stream()
                .mapToInt(GameReviewService::centipawnLoss)
                .average()
                .orElse(0));
        return new Statistics(
                moves.size(),
                count(moves, "inaccuracy"),
                count(moves, "mistake"),
                count(moves, "blunder"),
                averageLoss(moves),
                centipawnLoss,
                java.util.Collections.unmodifiableMap(byPhase));
    }

    /** The mover's centipawn drop from the best line before to the best line after, capped. */
    static int centipawnLoss(StoredMove move) {
        if (move.playedBest()) {
            return 0;
        }
        int before = centipawns(move.before().getFirst());
        int after = switch (move.afterTerminal()) {
            case "checkmate" -> CENTIPAWN_CAP;
            case "stalemate" -> 0;
            default -> -centipawns(move.after().getFirst());
        };
        return Math.min(CENTIPAWN_CAP, Math.max(0, before - after));
    }

    private static int centipawns(StoredLine line) {
        if (line.mate() != null) {
            return line.mate() > 0 ? CENTIPAWN_CAP : -CENTIPAWN_CAP;
        }
        return Math.clamp(line.cp(), -CENTIPAWN_CAP, CENTIPAWN_CAP);
    }

    /** White's view of each position the analysis searched, in position order. */
    static List<PositionEvaluation> evaluations(List<StoredMove> moves) {
        var byIndex = new TreeMap<Integer, PositionEvaluation>();
        for (StoredMove move : moves) {
            boolean whiteMoved = move.fenBefore().split(" ")[1].equals("w");
            int before = move.ply() - 1;
            byIndex.put(before, forWhite(before, move.before().getFirst(), whiteMoved));
            int after = move.ply();
            if (move.afterTerminal().equals("none")) {
                byIndex.put(after, forWhite(after, move.after().getFirst(), !whiteMoved));
            } else {
                int moverScore = move.afterTerminal().equals("checkmate") ? ExpectedScore.MAX : ExpectedScore.MAX / 2;
                byIndex.put(after, new PositionEvaluation(
                        after, whiteMoved ? moverScore : ExpectedScore.MAX - moverScore, null, null));
            }
        }
        return List.copyOf(byIndex.values());
    }

    private static PositionEvaluation forWhite(int index, StoredLine line, boolean whiteToMove) {
        int expected = 2 * line.win() + line.draw();
        int sign = whiteToMove ? 1 : -1;
        return new PositionEvaluation(
                index,
                whiteToMove ? expected : ExpectedScore.MAX - expected,
                line.cp() == null ? null : sign * line.cp(),
                line.mate() == null ? null : sign * line.mate());
    }

    private static int count(List<StoredMove> moves, String label) {
        return (int) moves.stream().filter(move -> move.label().equals(label)).count();
    }

    private static double averageLoss(List<StoredMove> moves) {
        if (moves.isEmpty()) {
            return 0;
        }
        double units = moves.stream().mapToInt(StoredMove::loss).average().orElse(0);
        return round(units / ExpectedScore.UNITS_PER_PERCENTAGE_POINT);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
