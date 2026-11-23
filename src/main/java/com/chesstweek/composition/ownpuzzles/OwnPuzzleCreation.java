package com.chesstweek.composition.ownpuzzles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.chesstweek.gamereview.AnalysisCompletionListener;
import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameAnalysis;
import com.chesstweek.gamereview.GameRef;
import com.chesstweek.gamereview.GameReviews;
import com.chesstweek.training.OwnPuzzles;

/**
 * Turns every owner inaccuracy, mistake, and blunder of a just-completed My Moves analysis into an
 * Own Puzzle. Opponent Moves findings never create one. It reads only the public game review, so
 * the game-review and training modules stay independent.
 */
@Component
public class OwnPuzzleCreation implements AnalysisCompletionListener {

    private static final Pattern ECO = Pattern.compile("[A-E][0-9]{2}");
    private static final int MAX_OPENING_NAME = 160;

    private final GameReviews reviews;
    private final OwnPuzzles ownPuzzles;

    OwnPuzzleCreation(GameReviews reviews, OwnPuzzles ownPuzzles) {
        this.reviews = reviews;
        this.ownPuzzles = ownPuzzles;
    }

    @Override
    public void completed(String accountId, GameRef game, GameAnalysis.Perspective perspective) {
        if (perspective == GameAnalysis.Perspective.OPPONENT_MOVES) {
            return;
        }
        GameReviews.GameReview review =
                reviews.review(accountId, game, GameReviews.Side.MY_MOVES).orElse(null);
        if (review == null || review.selected() == null
                || review.selected().side() != GameReviews.Side.MY_MOVES) {
            return;
        }
        List<OwnPuzzles.NewOwnPuzzle> puzzles = puzzles(review);
        if (!puzzles.isEmpty()) {
            ownPuzzles.create(accountId, puzzles);
        }
    }

    private static List<OwnPuzzles.NewOwnPuzzle> puzzles(GameReviews.GameReview review) {
        CatalogGame game = review.game();
        Map<Integer, GameReviews.Ply> plies = new HashMap<>();
        for (GameReviews.Ply ply : review.plies()) {
            plies.put(ply.ply(), ply);
        }
        String eco = null;
        String openingName = null;
        if (game.opening() != null) {
            eco = game.opening().eco() != null && ECO.matcher(game.opening().eco()).matches()
                    ? game.opening().eco() : null;
            String name = game.opening().name();
            openingName = name != null && !name.isBlank() && name.length() <= MAX_OPENING_NAME
                    ? name : null;
        }
        List<OwnPuzzles.NewOwnPuzzle> puzzles = new ArrayList<>();
        for (GameReviews.JudgedMove move : review.selected().moves()) {
            if ("none".equals(move.label())) {
                continue;
            }
            String previousFen = null;
            String previousMove = null;
            if (move.ply() > 1) {
                GameReviews.Ply previous = plies.get(move.ply() - 1);
                if (previous == null) {
                    continue;
                }
                previousMove = previous.uci();
                previousFen = move.ply() == 2
                        ? review.initialFen()
                        : plies.containsKey(move.ply() - 2) ? plies.get(move.ply() - 2).fen() : null;
                if (previousFen == null) {
                    continue;
                }
            }
            puzzles.add(new OwnPuzzles.NewOwnPuzzle(
                    game.ref().source().value(),
                    game.ref().id(),
                    move.ply(),
                    review.selected().versionId(),
                    move.fenBefore(),
                    previousFen,
                    previousMove,
                    move.before().isEmpty() ? List.of() : move.before().getFirst().pv(),
                    move.label(),
                    move.phase(),
                    eco,
                    openingName,
                    game.playedAt(),
                    move.tactics().stream()
                            .map(tactic -> new OwnPuzzles.Tactic(tactic.theme(), tactic.kind()))
                            .toList()));
        }
        return puzzles;
    }
}
