package com.chesstweek.composition.web;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.gamereview.ChessComPlayers;
import com.chesstweek.gamereview.ChessComRetrieval;
import com.chesstweek.gamereview.ChessComRetrievalResult;
import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;

/**
 * The account's chess.com username and manual chess.com game retrieval. Every request needs the
 * sealed Lichess session of the active account; requests that change state also need the local
 * same-origin check. Input is validated before the session is checked.
 */
@RestController
public final class ChessComController {

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final ChessComPlayers players;
    private final ChessComRetrieval retrieval;

    ChessComController(
            LichessSessionAuthorizer sessionAuthorizer,
            ChessComPlayers players,
            ChessComRetrieval retrieval) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.players = players;
        this.retrieval = retrieval;
    }

    @GetMapping("/api/chesscom/player")
    public ResponseEntity<?> player(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        var authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<ErrorResponse> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            return ok(new PlayerResponse(players.username(authorization.accountId()).orElse(null)));
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "catalog_unavailable");
        }
    }

    @PutMapping("/api/chesscom/player")
    public ResponseEntity<?> savePlayer(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestBody(required = false) PlayerRequest body) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String username = body == null ? null : body.username();
        if (!ChessComPlayers.isValidUsername(username)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_chesscom_username");
        }
        var authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<ErrorResponse> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            players.save(authorization.accountId(), username);
            return ok(new PlayerResponse(username));
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "catalog_unavailable");
        }
    }

    @DeleteMapping("/api/chesscom/player")
    public ResponseEntity<?> removePlayer(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<ErrorResponse> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        try {
            players.remove(authorization.accountId());
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "catalog_unavailable");
        }
    }

    @PostMapping("/api/chesscom/games/retrievals")
    public ResponseEntity<?> retrieve(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestBody(required = false) RetrievalRequest body) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ValidatedRetrieval validated = validate(body);
        if (validated == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_retrieval_request");
        }
        var authorization = sessionAuthorizer.authorize(sealedSession);
        ResponseEntity<ErrorResponse> denied = deny(authorization);
        if (denied != null) {
            return denied;
        }
        ChessComRetrievalResult result = retrieval.retrieve(
                authorization.accountId(), validated.count(), validated.beforeInstant());
        return switch (result.status()) {
            case SUCCESS -> ok(new RetrievalResponse(
                    validated.count(),
                    result.received(),
                    result.rejected(),
                    result.created(),
                    result.updated(),
                    result.unchanged(),
                    validated.beforeText()));
            case USERNAME_MISSING -> error(HttpStatus.CONFLICT, "chesscom_username_missing");
            case PLAYER_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "chesscom_player_not_found");
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()))
                    .body(new ErrorResponse("chesscom_rate_limited"));
            case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "invalid_chesscom_response");
            case UPSTREAM_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "chesscom_unavailable");
            case STORAGE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "catalog_unavailable");
            case BUSY -> error(HttpStatus.CONFLICT, "retrieval_already_running");
        };
    }

    private static ValidatedRetrieval validate(RetrievalRequest body) {
        if (body == null || body.count() == null
                || body.count() < 1 || body.count() > ChessComRetrieval.MAX_GAMES) {
            return null;
        }
        if (body.before() == null) {
            return new ValidatedRetrieval(body.count(), null, null);
        }
        if (body.before().length() != 10) {
            return null;
        }
        try {
            LocalDate before = LocalDate.parse(body.before());
            return new ValidatedRetrieval(
                    body.count(), before.toString(), before.atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static ResponseEntity<ErrorResponse> deny(LichessSessionAuthorization authorization) {
        return switch (authorization.type()) {
            case CONNECTED -> null;
            case DISCONNECTED -> {
                var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore());
                if (authorization.clearCookie()) {
                    response.header(
                            HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
                }
                yield response.body(new ErrorResponse("not_connected"));
            }
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "session_unavailable");
        };
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(code));
    }

    public record PlayerRequest(String username) {}

    public record PlayerResponse(String username) {}

    public record RetrievalRequest(Integer count, String before) {}

    public record RetrievalResponse(
            int requested,
            int received,
            int rejected,
            int created,
            int updated,
            int unchanged,
            String before) {}

    public record ErrorResponse(String error) {}

    private record ValidatedRetrieval(int count, String beforeText, Instant beforeInstant) {}
}
