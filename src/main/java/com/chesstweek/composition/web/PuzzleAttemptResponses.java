package com.chesstweek.composition.web;

import java.util.List;
import java.util.Locale;

import com.chesstweek.training.PuzzleTraining;

/**
 * The response shapes of a normal-Puzzle attempt and its completion. The Puzzle controller and the
 * guided-course controller both serve them, because a guided Exercise is a Puzzle attempt.
 */
final class PuzzleAttemptResponses {

    private PuzzleAttemptResponses() {}

    static AttemptResponse attempt(PuzzleTraining.Attempt value) {
        return new AttemptResponse(
                value.attemptId(),
                value.puzzleId(),
                value.externalId(),
                value.positionFen(),
                value.setupMove(),
                value.rating(),
                value.ownerMoves(),
                value.fastSolveLimitMillis(),
                value.currentList() == null ? null : lower(value.currentList()));
    }

    static CompletionResponse completion(PuzzleTraining.Completion completion) {
        if (completion == null) {
            return null;
        }
        return new CompletionResponse(
                completion.previousList() == null ? null : lower(completion.previousList()),
                lower(completion.performance().list()),
                completion.performance().successes(),
                completion.performance().strikes(),
                completion.wrongMoves(),
                completion.hintUsed(),
                completion.elapsedMillis(),
                completion.fastSolveLimitMillis(),
                completion.gameUrl(),
                completion.themes());
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    public record AttemptResponse(
            long attemptId,
            long puzzleId,
            String externalId,
            String positionFen,
            String setupMove,
            int rating,
            int ownerMoves,
            long fastSolveLimitMillis,
            String currentList) {}

    public record CompletionResponse(
            String previousList,
            String list,
            int successes,
            int strikes,
            int wrongMoves,
            boolean hintUsed,
            long elapsedMillis,
            long fastSolveLimitMillis,
            String gameUrl,
            List<String> themes) {}
}
