package com.chesstweek.composition.web;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.gamereview.GameRatingContext;
import com.chesstweek.gamereview.RatingContext;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;

@RestController
public final class DashboardRatingContextController {

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final GameRatingContext ratingContext;

    DashboardRatingContextController(
            LichessSessionAuthorizer sessionAuthorizer,
            GameRatingContext ratingContext) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.ratingContext = ratingContext;
    }

    @GetMapping("/api/dashboard/rating-context")
    public ResponseEntity<?> ratingContext(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        var authorization = sessionAuthorizer.authorize(sealedSession);
        return switch (authorization.type()) {
            case CONNECTED -> connected(authorization.accountId());
            case DISCONNECTED -> disconnected(authorization.clearCookie());
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "session_unavailable");
        };
    }

    private ResponseEntity<?> connected(String accountId) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(toResponse(ratingContext.ratingContext(accountId)));
        } catch (DataAccessException | IllegalArgumentException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "rating_context_unavailable");
        }
    }

    private static RatingContextResponse toResponse(RatingContext context) {
        List<SpeedRatingResponse> ratings = context.histories().stream()
                .map(history -> new SpeedRatingResponse(
                        history.speed().value(),
                        history.gamesCounted(),
                        history.latestChange(),
                        history.points().stream()
                                .map(point -> new RatingPointResponse(
                                        point.playedAt().toString(), point.rating()))
                                .toList()))
                .toList();
        RatingContext.BotRecord record = context.botRecord();
        List<BotGroupResponse> groups = record.groups().stream()
                .map(group -> new BotGroupResponse(
                        switch (group.kind()) {
                            case LICHESS_AI -> "lichess_ai";
                            case BOT_RATING_BAND -> "bot_rating_band";
                            case BOT_UNRATED -> "bot_unrated";
                        },
                        group.ratingFrom(),
                        group.ratingTo(),
                        group.aiLevel(),
                        group.tally().wins(),
                        group.tally().draws(),
                        group.tally().losses(),
                        group.tally().games()))
                .toList();
        return new RatingContextResponse(
                ratings,
                new BotRecordResponse(
                        record.total().wins(),
                        record.total().draws(),
                        record.total().losses(),
                        record.total().games(),
                        record.lowestRating(),
                        record.highestRating(),
                        groups));
    }

    private static ResponseEntity<RatingContextError> disconnected(boolean clearCookie) {
        var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore());
        if (clearCookie) {
            response.header(HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
        }
        return response.body(new RatingContextError("not_connected"));
    }

    private static ResponseEntity<RatingContextError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new RatingContextError(code));
    }

    public record RatingContextResponse(
            List<SpeedRatingResponse> ratings,
            BotRecordResponse botRecord) {}

    public record SpeedRatingResponse(
            String speed,
            int gamesCounted,
            Integer latestChange,
            List<RatingPointResponse> points) {}

    public record RatingPointResponse(String playedAt, int rating) {}

    public record BotRecordResponse(
            int wins,
            int draws,
            int losses,
            int games,
            Integer lowestRating,
            Integer highestRating,
            List<BotGroupResponse> groups) {}

    public record BotGroupResponse(
            String type,
            Integer ratingFrom,
            Integer ratingTo,
            Integer aiLevel,
            int wins,
            int draws,
            int losses,
            int games) {}

    public record RatingContextError(String error) {}
}
