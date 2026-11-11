package com.chesstweek.composition.web;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.gamereview.CatalogGame;
import com.chesstweek.gamereview.GameCatalog;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;

@RestController
public final class GameCatalogController {

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final GameCatalog gameCatalog;

    GameCatalogController(
            LichessSessionAuthorizer sessionAuthorizer,
            GameCatalog gameCatalog) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.gameCatalog = gameCatalog;
    }

    @GetMapping("/api/games")
    public ResponseEntity<?> games(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "offset", defaultValue = "0") String offsetText,
            @RequestParam(name = "limit", defaultValue = "50") String limitText) {
        Integer offset = boundedInteger(offsetText, 0, 100_000);
        Integer limit = boundedInteger(limitText, 1, 50);
        if (offset == null || limit == null) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new CatalogError("invalid_catalog_page"));
        }
        var authorization = sessionAuthorizer.authorize(sealedSession);
        return switch (authorization.type()) {
            case CONNECTED -> connected(authorization.accountId(), offset, limit);
            case DISCONNECTED -> disconnected(authorization.clearCookie());
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new CatalogError("session_unavailable"));
        };
    }

    private ResponseEntity<?> connected(String accountId, int offset, int limit) {
        try {
            var page = gameCatalog.games(accountId, offset, limit);
            List<GameCardResponse> games = page.games().stream()
                    .map(GameCatalogController::toCard)
                    .toList();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new CatalogResponse(page.total(), offset, limit, games));
        } catch (DataAccessException | IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new CatalogError("catalog_unavailable"));
        }
    }

    private static Integer boundedInteger(String value, int min, int max) {
        if (value == null || value.isEmpty() || value.length() > 6) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static GameCardResponse toCard(CatalogGame game) {
        return new GameCardResponse(
                game.id(),
                game.playedAt().toString(),
                game.ownerResult().value(),
                game.ownerColor().value(),
                game.ownerRating(),
                opponent(game.opponent()),
                game.speed().value(),
                game.rated(),
                game.variant().value(),
                timeControl(game.timeControl()),
                opening(game.opening()),
                new AnalysisResponse(
                        game.analysisEligible(),
                        game.analysisEligible() ? "not_analyzed" : "unsupported_variant"));
    }

    private static OpponentResponse opponent(CatalogGame.Opponent opponent) {
        String type = switch (opponent.kind()) {
            case AI -> "lichess_ai";
            case ANONYMOUS -> "anonymous";
            case USER -> "BOT".equals(opponent.title()) ? "bot" : "human";
        };
        return new OpponentResponse(
                type,
                opponent.name(),
                opponent.rating(),
                opponent.title(),
                opponent.aiLevel());
    }

    private static TimeControlResponse timeControl(CatalogGame.TimeControl timeControl) {
        if (timeControl == null) {
            return new TimeControlResponse(null, null, null, "No clock");
        }
        String label = timeControl.daysPerTurn() == null
                ? clockLabel(timeControl.initialSeconds(), timeControl.incrementSeconds())
                : timeControl.daysPerTurn() + " day"
                        + (timeControl.daysPerTurn() == 1 ? "" : "s") + "/turn";
        return new TimeControlResponse(
                timeControl.initialSeconds(),
                timeControl.incrementSeconds(),
                timeControl.daysPerTurn(),
                label);
    }

    private static String clockLabel(Integer initialSeconds, Integer incrementSeconds) {
        if (initialSeconds == null || incrementSeconds == null) {
            return "No clock";
        }
        String initial = initialSeconds % 60 == 0
                ? Integer.toString(initialSeconds / 60)
                : (initialSeconds / 60) + ":" + String.format("%02d", initialSeconds % 60);
        return initial + "+" + incrementSeconds;
    }

    private static OpeningResponse opening(CatalogGame.Opening opening) {
        return opening == null ? null : new OpeningResponse(opening.eco(), opening.name());
    }

    private static ResponseEntity<CatalogError> disconnected(boolean clearCookie) {
        var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore());
        if (clearCookie) {
            response.header(HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
        }
        return response.body(new CatalogError("not_connected"));
    }

    public record CatalogResponse(
            int total,
            int offset,
            int limit,
            List<GameCardResponse> games) {}

    public record GameCardResponse(
            String id,
            String playedAt,
            String result,
            String ownerColor,
            Integer ownerRating,
            OpponentResponse opponent,
            String speed,
            boolean rated,
            String variant,
            TimeControlResponse timeControl,
            OpeningResponse opening,
            AnalysisResponse analysis) {}

    public record OpponentResponse(
            String type,
            String name,
            Integer rating,
            String title,
            Integer aiLevel) {}

    public record TimeControlResponse(
            Integer initialSeconds,
            Integer incrementSeconds,
            Integer daysPerTurn,
            String label) {}

    public record OpeningResponse(String eco, String name) {}

    public record AnalysisResponse(boolean eligible, String status) {}

    public record CatalogError(String error) {}
}
