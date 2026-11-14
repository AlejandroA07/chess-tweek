package com.chesstweek.composition.web;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.gamereview.GameAnalysisSummaries;
import com.chesstweek.gamereview.GameReviews;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;

/** Reads one stored game and the newest analysis of one side for review. */
@RestController
public final class GameReviewController {

    private static final Pattern GAME_ID = Pattern.compile("[A-Za-z0-9]{8,32}");

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final GameReviews gameReviews;

    GameReviewController(LichessSessionAuthorizer sessionAuthorizer, GameReviews gameReviews) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.gameReviews = gameReviews;
    }

    @GetMapping("/api/games/{gameId}/review")
    public ResponseEntity<?> review(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @PathVariable("gameId") String gameId,
            @RequestParam(name = "side", defaultValue = "") String sideText) {
        GameReviews.Side side = switch (sideText) {
            case "" -> null;
            case "my_moves" -> GameReviews.Side.MY_MOVES;
            case "opponent_moves" -> GameReviews.Side.OPPONENT_MOVES;
            default -> null;
        };
        if (!GAME_ID.matcher(gameId).matches() || (side == null && !sideText.isEmpty())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_review_request");
        }
        LichessSessionAuthorization authorization = sessionAuthorizer.authorize(sealedSession);
        switch (authorization.type()) {
            case CONNECTED -> {
                // Continue below.
            }
            case DISCONNECTED -> {
                var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore());
                if (authorization.clearCookie()) {
                    response.header(HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
                }
                return response.body(new ReviewError("not_connected"));
            }
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> {
                return error(HttpStatus.SERVICE_UNAVAILABLE, "session_unavailable");
            }
        }
        GameReviews.GameReview review;
        try {
            review = gameReviews.review(authorization.accountId(), gameId, side).orElse(null);
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "review_unavailable");
        }
        if (review == null) {
            return error(HttpStatus.NOT_FOUND, "game_not_found");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response(review));
    }

    private static ReviewResponse response(GameReviews.GameReview review) {
        var summary = review.myMoves() == null && review.opponentMoves() == null
                ? null
                : new GameAnalysisSummaries.Summary(
                        summaryVersion(review.myMoves()), summaryVersion(review.opponentMoves()));
        return new ReviewResponse(
                GameCatalogController.toCard(review.game(), summary),
                review.initialFen(),
                review.plies(),
                review.phases(),
                version(review.myMoves()),
                version(review.opponentMoves()),
                selected(review.selected()));
    }

    private static GameAnalysisSummaries.Version summaryVersion(GameReviews.VersionInfo info) {
        return info == null ? null : new GameAnalysisSummaries.Version(
                info.id(), info.completedAt(), info.currentProfile(),
                info.moves(), info.inaccuracies(), info.mistakes(), info.blunders());
    }

    private static VersionResponse version(GameReviews.VersionInfo info) {
        return info == null ? null
                : new VersionResponse(info.id(), info.completedAt().toString(), info.currentProfile());
    }

    private static SelectedResponse selected(GameReviews.SideReview side) {
        if (side == null) {
            return null;
        }
        return new SelectedResponse(
                side.side() == GameReviews.Side.MY_MOVES ? "my_moves" : "opponent_moves",
                side.versionId(),
                side.analyzedColor(),
                side.engineName(),
                side.quickNodes(),
                side.deepNodes(),
                side.completedAt().toString(),
                side.currentProfile(),
                side.statistics(),
                side.evaluations(),
                side.moves());
    }

    private static ResponseEntity<ReviewError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ReviewError(code));
    }

    public record ReviewResponse(
            GameCatalogController.GameCardResponse game,
            String initialFen,
            List<GameReviews.Ply> plies,
            GameReviews.Phases phases,
            VersionResponse myMoves,
            VersionResponse opponentMoves,
            SelectedResponse selected) {}

    public record VersionResponse(long id, String completedAt, boolean currentProfile) {}

    public record SelectedResponse(
            String side,
            long versionId,
            String analyzedColor,
            String engineName,
            long quickNodes,
            long deepNodes,
            String completedAt,
            boolean currentProfile,
            GameReviews.Statistics statistics,
            List<GameReviews.PositionEvaluation> evaluations,
            List<GameReviews.JudgedMove> moves) {}

    public record ReviewError(String error) {}
}
