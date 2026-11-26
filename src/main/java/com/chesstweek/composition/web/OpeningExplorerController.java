package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
import com.chesstweek.gamereview.GameRef;
import com.chesstweek.gamereview.MyGamesExplorer;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;
import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningExplorer;

/**
 * Opening explorer statistics for one position: Lichess Masters, Lichess games by speed and rating
 * group, and the owner's own stored games. The Lichess token stays on the server; failures of the
 * Lichess explorer never affect My games.
 */
@RestController
public final class OpeningExplorerController {

    private static final int MAX_LIST_PARAMETER = 120;

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final OpeningExplorer openingExplorer;
    private final MyGamesExplorer myGamesExplorer;

    OpeningExplorerController(
            LichessSessionAuthorizer sessionAuthorizer,
            OpeningExplorer openingExplorer,
            MyGamesExplorer myGamesExplorer) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.openingExplorer = openingExplorer;
        this.myGamesExplorer = myGamesExplorer;
    }

    @GetMapping("/api/explorer/masters")
    public ResponseEntity<?> masters(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "fen", defaultValue = "") String fen) {
        Optional<ExplorerPosition> position = ExplorerPosition.parse(fen);
        if (position.isEmpty()) {
            return invalidRequest();
        }
        return withSession(sealedSession, authorization -> lichessAnswer(authorization.useAccessToken(
                token -> openingExplorer.masters(token, position.get()))));
    }

    @GetMapping("/api/explorer/lichess")
    public ResponseEntity<?> lichess(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "fen", defaultValue = "") String fen,
            @RequestParam(name = "speeds", defaultValue = "") String speedsText,
            @RequestParam(name = "ratings", defaultValue = "") String ratingsText) {
        Optional<ExplorerPosition> position = ExplorerPosition.parse(fen);
        Optional<Set<OpeningExplorer.Speed>> speeds = parseList(
                speedsText, OpeningExplorer.Speed::fromValue, OpeningExplorer.Speed.class);
        Optional<Set<OpeningExplorer.RatingGroup>> ratings = parseList(
                ratingsText, OpeningExplorer.RatingGroup::fromValue, OpeningExplorer.RatingGroup.class);
        if (position.isEmpty() || speeds.isEmpty() || ratings.isEmpty()) {
            return invalidRequest();
        }
        return withSession(sealedSession, authorization -> lichessAnswer(authorization.useAccessToken(
                token -> openingExplorer.lichess(token, position.get(), speeds.get(), ratings.get()))));
    }

    @GetMapping("/api/explorer/my-games")
    public ResponseEntity<?> myGames(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "fen", defaultValue = "") String fen,
            @RequestParam(name = "color", defaultValue = "") String color,
            @RequestParam(name = "speed", defaultValue = "") String speed,
            @RequestParam(name = "source", defaultValue = "") String source) {
        Optional<ExplorerPosition> position = ExplorerPosition.parse(fen);
        Optional<Optional<CatalogGame.Color>> ownerColor = optionalValue(color, CatalogGame.Color::fromValue);
        Optional<Optional<CatalogGame.Speed>> gameSpeed = optionalValue(speed, CatalogGame.Speed::fromValue);
        Optional<Optional<GameRef.Source>> gameSource = optionalValue(
                source, value -> GameRef.Source.fromValue(value).orElseThrow(IllegalArgumentException::new));
        if (position.isEmpty() || ownerColor.isEmpty() || gameSpeed.isEmpty() || gameSource.isEmpty()) {
            return invalidRequest();
        }
        var filter = new MyGamesExplorer.Filter(
                ownerColor.get().orElse(null), gameSpeed.get().orElse(null), gameSource.get().orElse(null));
        return withSession(sealedSession, authorization -> {
            try {
                var statistics = myGamesExplorer.statistics(
                        authorization.accountId(), position.get(), filter);
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(MyGamesResponse.from(statistics));
            } catch (DataAccessException exception) {
                return error(HttpStatus.SERVICE_UNAVAILABLE, "my_games_unavailable");
            }
        });
    }

    private ResponseEntity<?> withSession(
            String sealedSession,
            Function<LichessSessionAuthorization, ResponseEntity<?>> connected) {
        var authorization = sessionAuthorizer.authorize(sealedSession);
        return switch (authorization.type()) {
            case CONNECTED -> connected.apply(authorization);
            case DISCONNECTED -> notConnected(authorization.clearCookie());
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "session_unavailable");
        };
    }

    private static ResponseEntity<?> lichessAnswer(OpeningExplorer.Answer answer) {
        return switch (answer.status()) {
            case SUCCESS -> ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(LichessExplorerResponse.from(answer.statistics()));
            case UNAUTHORIZED -> error(HttpStatus.BAD_GATEWAY, "lichess_explorer_refused");
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(
                            answer.retryAfterSeconds() == null ? 60 : answer.retryAfterSeconds()))
                    .body(new ErrorResponse("lichess_rate_limited"));
            case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "invalid_lichess_response");
            case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "lichess_unavailable");
        };
    }

    /** Empty text means any (an empty inner value); an unknown value is invalid (empty). */
    private static <T> Optional<Optional<T>> optionalValue(String text, Function<String, T> parse) {
        if (text.isEmpty()) {
            return Optional.of(Optional.empty());
        }
        try {
            return Optional.of(Optional.of(parse.apply(text)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** A comma-separated list of distinct known values; empty text means none. */
    private static <E extends Enum<E>> Optional<Set<E>> parseList(
            String text, Function<String, Optional<E>> parse, Class<E> type) {
        if (text.isEmpty()) {
            return Optional.of(EnumSet.noneOf(type));
        }
        if (text.length() > MAX_LIST_PARAMETER) {
            return Optional.empty();
        }
        Set<E> values = EnumSet.noneOf(type);
        for (String part : text.split(",", -1)) {
            Optional<E> value = parse.apply(part);
            if (value.isEmpty() || !values.add(value.get())) {
                return Optional.empty();
            }
        }
        return Optional.of(values);
    }

    private static ResponseEntity<?> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "invalid_explorer_request");
    }

    private static ResponseEntity<?> notConnected(boolean clearCookie) {
        var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore());
        if (clearCookie) {
            response.header(HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
        }
        return response.body(new ErrorResponse("not_connected"));
    }

    private static ResponseEntity<?> error(HttpStatus status, String error) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(error));
    }

    public record ErrorResponse(String error) {}

    public record ResultsResponse(long white, long draws, long black) {}

    public record OpeningResponse(String eco, String name) {

        static OpeningResponse from(OpeningExplorer.Opening opening) {
            return opening == null ? null : new OpeningResponse(opening.eco(), opening.name());
        }
    }

    public record LichessMoveResponse(
            String uci,
            String san,
            Integer averageRating,
            ResultsResponse results,
            OpeningResponse opening) {}

    public record PlayerResponse(String name, Integer rating, String title) {}

    public record TopGameResponse(
            String id, String uci, PlayerResponse white, PlayerResponse black, String winner, Integer year) {}

    public record LichessExplorerResponse(
            OpeningResponse opening,
            ResultsResponse results,
            List<LichessMoveResponse> moves,
            List<TopGameResponse> topGames,
            Instant fetchedAt,
            boolean stale) {

        static LichessExplorerResponse from(OpeningExplorer.Statistics statistics) {
            return new LichessExplorerResponse(
                    OpeningResponse.from(statistics.opening()),
                    results(statistics.results()),
                    statistics.moves().stream()
                            .map(move -> new LichessMoveResponse(
                                    move.uci(),
                                    move.san(),
                                    move.averageRating(),
                                    results(move.results()),
                                    OpeningResponse.from(move.opening())))
                            .toList(),
                    statistics.topGames().stream()
                            .map(game -> new TopGameResponse(
                                    game.id(),
                                    game.uci(),
                                    player(game.white()),
                                    player(game.black()),
                                    game.winner(),
                                    game.year()))
                            .toList(),
                    statistics.fetchedAt(),
                    statistics.stale());
        }

        private static ResultsResponse results(OpeningExplorer.Results results) {
            return new ResultsResponse(results.white(), results.draws(), results.black());
        }

        private static PlayerResponse player(OpeningExplorer.Player player) {
            return new PlayerResponse(player.name(), player.rating(), player.title());
        }
    }

    public record MyGamesMoveResponse(String uci, String san, ResultsResponse results) {}

    public record MyGamesGameResponse(
            String source,
            String id,
            Instant playedAt,
            String ownerColor,
            String ownerResult,
            String opponentName,
            Integer opponentRating,
            String speed,
            String nextSan) {}

    public record MyGamesResponse(
            ResultsResponse results,
            List<MyGamesMoveResponse> moves,
            List<MyGamesGameResponse> recentGames) {

        static MyGamesResponse from(MyGamesExplorer.PositionStatistics statistics) {
            return new MyGamesResponse(
                    results(statistics.results()),
                    statistics.moves().stream()
                            .map(move -> new MyGamesMoveResponse(
                                    move.uci(), move.san(), results(move.results())))
                            .toList(),
                    statistics.recentGames().stream()
                            .map(game -> new MyGamesGameResponse(
                                    game.ref().source().value(),
                                    game.ref().id(),
                                    game.playedAt(),
                                    game.ownerColor().value(),
                                    game.ownerResult().value(),
                                    game.opponentName(),
                                    game.opponentRating(),
                                    game.speed().value(),
                                    game.nextSan()))
                            .toList());
        }

        private static ResultsResponse results(MyGamesExplorer.Results results) {
            return new ResultsResponse(results.white(), results.draws(), results.black());
        }
    }
}
