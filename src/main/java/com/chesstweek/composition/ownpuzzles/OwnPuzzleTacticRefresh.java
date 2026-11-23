package com.chesstweek.composition.ownpuzzles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.chesstweek.gamereview.GameRef;
import com.chesstweek.gamereview.GameReviews;
import com.chesstweek.training.OwnPuzzles;

/**
 * Brings every stored Own Puzzle's tactic labels up to date with the current tactic rules when the
 * application starts. Labels are recomputed from the stored analysis the puzzle came from, so
 * nothing is reanalyzed; a puzzle whose game or analysis version is no longer available keeps its
 * labels. Training state is never touched. A failure on one game is logged and skips only that game.
 */
@Component
public class OwnPuzzleTacticRefresh implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(OwnPuzzleTacticRefresh.class);

    private final GameReviews reviews;
    private final OwnPuzzles ownPuzzles;

    OwnPuzzleTacticRefresh(GameReviews reviews, OwnPuzzles ownPuzzles) {
        this.reviews = reviews;
        this.ownPuzzles = ownPuzzles;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            int changed = refresh();
            if (changed > 0) {
                LOGGER.info("Refreshed the tactic labels of {} Own Puzzles", changed);
            }
        } catch (RuntimeException exception) {
            // Outdated labels only affect grouping; they must never stop the application starting.
            LOGGER.warn("Could not refresh the Own Puzzle tactic labels: {}", exception.getClass().getSimpleName());
        }
    }

    /** Returns how many Own Puzzles got new labels. */
    int refresh() {
        Map<GameKey, List<OwnPuzzles.StoredLabels>> byGame = new LinkedHashMap<>();
        for (OwnPuzzles.StoredLabels stored : ownPuzzles.labels()) {
            OwnPuzzles.PuzzleSource source = stored.source();
            byGame.computeIfAbsent(new GameKey(source.accountId(), source.gameSource(), source.gameId()),
                    ignored -> new ArrayList<>()).add(stored);
        }
        int changed = 0;
        for (Map.Entry<GameKey, List<OwnPuzzles.StoredLabels>> game : byGame.entrySet()) {
            try {
                changed += refresh(game.getKey(), game.getValue());
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not refresh the Own Puzzle tactic labels of one game: {}",
                        exception.getClass().getSimpleName());
            }
        }
        return changed;
    }

    private int refresh(GameKey key, List<OwnPuzzles.StoredLabels> puzzles) {
        GameRef ref = GameRef.parse(key.gameSource(), key.gameId()).orElse(null);
        if (ref == null) {
            return 0;
        }
        GameReviews.SideReview selected = reviews.review(key.accountId(), ref, GameReviews.Side.MY_MOVES)
                .map(GameReviews.GameReview::selected)
                .orElse(null);
        if (selected == null || selected.side() != GameReviews.Side.MY_MOVES) {
            return 0;
        }
        Map<Integer, GameReviews.JudgedMove> moves = new LinkedHashMap<>();
        for (GameReviews.JudgedMove move : selected.moves()) {
            moves.put(move.ply(), move);
        }
        int changed = 0;
        for (OwnPuzzles.StoredLabels stored : puzzles) {
            GameReviews.JudgedMove move = moves.get(stored.source().sourcePly());
            if (stored.analysisVersionId() != selected.versionId() || move == null) {
                continue;
            }
            List<OwnPuzzles.Tactic> current = OwnPuzzleCreation.ownTactics(move);
            if (!new HashSet<>(current).equals(new HashSet<>(stored.tactics()))
                    && ownPuzzles.replaceTactics(stored.source(), current)) {
                changed++;
            }
        }
        return changed;
    }

    private record GameKey(String accountId, String gameSource, String gameId) {}
}
