package com.chesstweek.composition.web;

import java.time.DateTimeException;
import java.time.LocalDate;
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
import com.chesstweek.gamereview.GameCatalogQuery;
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
            @RequestParam(name = "limit", defaultValue = "50") String limitText,
            @RequestParam(name = "result", defaultValue = "") String result,
            @RequestParam(name = "color", defaultValue = "") String color,
            @RequestParam(name = "opening", defaultValue = "") String opening,
            @RequestParam(name = "opponent", defaultValue = "") String opponent,
            @RequestParam(name = "speed", defaultValue = "") String speed,
            @RequestParam(name = "from", defaultValue = "") String from,
            @RequestParam(name = "to", defaultValue = "") String to,
            @RequestParam(name = "variant", defaultValue = "") String variant,
            @RequestParam(name = "analysis", defaultValue = "") String analysis,
            @RequestParam(name = "sort", defaultValue = "newest") String sort) {
        Integer offset = boundedInteger(offsetText, 0, 100_000);
        Integer limit = boundedInteger(limitText, 1, 50);
        if (offset == null || limit == null) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new CatalogError("invalid_catalog_page"));
        }
        if (!allowed(result, "", "win", "loss", "draw")
                || !allowed(color, "", "white", "black")
                || !validOpening(opening)
                || !allowed(opponent, "", "human", "lichess_ai", "bot", "anonymous")
                || !allowed(
                        speed,
                        "",
                        "ultraBullet",
                        "bullet",
                        "blitz",
                        "rapid",
                        "classical",
                        "correspondence")
                || !allowed(
                        variant,
                        "",
                        "standard",
                        "chess960",
                        "crazyhouse",
                        "antichess",
                        "atomic",
                        "horde",
                        "kingOfTheHill",
                        "racingKings",
                        "threeCheck",
                        "fromPosition")
                || !allowed(analysis, "", "eligible", "catalog_only")
                || !allowed(sort, "newest", "oldest")) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new CatalogError("invalid_catalog_filter"));
        }
        LocalDate playedFrom;
        LocalDate playedTo;
        try {
            playedFrom = optionalDate(from);
            playedTo = optionalDate(to);
        } catch (DateTimeException exception) {
            return invalidFilter();
        }
        if (playedFrom != null && playedTo != null && playedFrom.isAfter(playedTo)) {
            return invalidFilter();
        }
        var query = new GameCatalogQuery(
                offset,
                limit,
                result.isEmpty() ? null : CatalogGame.Result.fromValue(result),
                color.isEmpty() ? null : CatalogGame.Color.fromValue(color),
                opening.isEmpty() ? null : opening,
                opponentType(opponent),
                speed.isEmpty() ? null : CatalogGame.Speed.fromValue(speed),
                playedFrom,
                playedTo,
                variant.isEmpty() ? null : CatalogGame.Variant.fromValue(variant),
                analysisState(analysis),
                "oldest".equals(sort)
                        ? GameCatalogQuery.Sort.OLDEST
                        : GameCatalogQuery.Sort.NEWEST);
        var authorization = sessionAuthorizer.authorize(sealedSession);
        return switch (authorization.type()) {
            case CONNECTED -> connected(authorization.accountId(), query);
            case DISCONNECTED -> disconnected(authorization.clearCookie());
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new CatalogError("session_unavailable"));
        };
    }

    private static boolean allowed(String value, String... allowed) {
        return List.of(allowed).contains(value);
    }

    private static boolean validOpening(String value) {
        if (value.isEmpty()) {
            return true;
        }
        return value.length() <= 80
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static LocalDate optionalDate(String value) {
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() != 10) {
            throw new DateTimeException("Invalid date filter");
        }
        LocalDate date = LocalDate.parse(value);
        if (date.isBefore(LocalDate.of(2000, 1, 1))
                || date.isAfter(LocalDate.of(2100, 12, 31))) {
            throw new DateTimeException("Date filter out of range");
        }
        return date;
    }

    private static GameCatalogQuery.OpponentType opponentType(String value) {
        return switch (value) {
            case "human" -> GameCatalogQuery.OpponentType.HUMAN;
            case "lichess_ai" -> GameCatalogQuery.OpponentType.LICHESS_AI;
            case "bot" -> GameCatalogQuery.OpponentType.BOT;
            case "anonymous" -> GameCatalogQuery.OpponentType.ANONYMOUS;
            default -> null;
        };
    }

    private static GameCatalogQuery.AnalysisState analysisState(String value) {
        return switch (value) {
            case "eligible" -> GameCatalogQuery.AnalysisState.ELIGIBLE;
            case "catalog_only" -> GameCatalogQuery.AnalysisState.CATALOG_ONLY;
            default -> null;
        };
    }

    private static ResponseEntity<CatalogError> invalidFilter() {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new CatalogError("invalid_catalog_filter"));
    }

    private ResponseEntity<?> connected(String accountId, GameCatalogQuery query) {
        try {
            var page = gameCatalog.games(accountId, query);
            List<GameCardResponse> games = page.games().stream()
                    .map(GameCatalogController::toCard)
                    .toList();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new CatalogResponse(
                            page.total(), query.offset(), query.limit(), games));
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
