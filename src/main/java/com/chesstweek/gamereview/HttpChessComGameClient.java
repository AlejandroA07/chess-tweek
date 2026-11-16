package com.chesstweek.gamereview;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.chesstweek.shared.http.RetryAfter;

/**
 * The chess.com Published-Data API. Every request URL is built here from a validated username and
 * month; URLs inside responses are only read for their year and month and never requested.
 * Requests carry a generic client name and no personal data.
 */
@Component
final class HttpChessComGameClient implements ChessComGameClient {

    static final String BASE = "https://api.chess.com/pub/player/";
    static final String USER_AGENT = "ChessTweek/0.1 (local single-user app)";
    private static final Pattern ARCHIVE_URL = Pattern.compile(
            "https://api\\.chess\\.com/pub/player/[A-Za-z0-9_-]{1,32}/games/([0-9]{4})/([0-9]{2})");
    private static final int MAX_ARCHIVE_LIST_BYTES = 256 * 1024;
    private static final int MAX_MONTH_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MONTHS = 1_200;
    private static final int MAX_GAMES_PER_MONTH = 10_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    HttpChessComGameClient() {
        this(createRestClient(), Clock.systemUTC());
    }

    HttpChessComGameClient(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.objectMapper = JsonMapper.builder().build();
        this.clock = clock;
    }

    @Override
    public List<YearMonth> archiveMonths(String username) {
        JsonNode root = get(URI.create(BASE + path(username) + "/games/archives"),
                MAX_ARCHIVE_LIST_BYTES, true);
        JsonNode archives = root.get("archives");
        if (archives == null || !archives.isArray() || archives.size() > MAX_MONTHS) {
            throw failure(ChessComClientException.Reason.INVALID_RESPONSE, null, null);
        }
        var months = new ArrayList<YearMonth>(archives.size());
        for (JsonNode archive : archives) {
            Matcher matcher = archive.isString() ? ARCHIVE_URL.matcher(archive.stringValue()) : null;
            if (matcher == null || !matcher.matches()) {
                throw failure(ChessComClientException.Reason.INVALID_RESPONSE, null, null);
            }
            int month = Integer.parseInt(matcher.group(2));
            if (month < 1 || month > 12) {
                throw failure(ChessComClientException.Reason.INVALID_RESPONSE, null, null);
            }
            months.add(YearMonth.of(Integer.parseInt(matcher.group(1)), month));
        }
        return months.stream().distinct().sorted(Comparator.reverseOrder()).toList();
    }

    @Override
    public List<JsonNode> monthGames(String username, YearMonth month) {
        String monthPath = "/games/%04d/%02d".formatted(month.getYear(), month.getMonthValue());
        JsonNode root = get(URI.create(BASE + path(username) + monthPath), MAX_MONTH_BYTES, false);
        JsonNode games = root.get("games");
        if (games == null || !games.isArray() || games.size() > MAX_GAMES_PER_MONTH) {
            throw failure(ChessComClientException.Reason.INVALID_RESPONSE, null, null);
        }
        var result = new ArrayList<JsonNode>(games.size());
        games.forEach(result::add);
        return result;
    }

    private static String path(String username) {
        if (!ChessComPlayers.isValidUsername(username)) {
            throw new IllegalArgumentException("Invalid chess.com username");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    /** A 404 or 410 means no such player only for the archive list, the first request made. */
    private JsonNode get(URI uri, int maxBytes, boolean notFoundIsPlayer) {
        try {
            return restClient.get()
                    .uri(uri)
                    .headers(headers -> {
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if ((status == 404 || status == 410) && notFoundIsPlayer) {
                            throw failure(ChessComClientException.Reason.PLAYER_NOT_FOUND, null, null);
                        }
                        if (status == 429) {
                            throw failure(
                                    ChessComClientException.Reason.RATE_LIMITED,
                                    RetryAfter.parse(
                                            response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                                            clock.instant()),
                                    null);
                        }
                        if (status != 200) {
                            throw failure(ChessComClientException.Reason.UNAVAILABLE, null, null);
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null
                                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                            throw failure(ChessComClientException.Reason.INVALID_RESPONSE, null, null);
                        }
                        return read(response.getBody(), maxBytes, bytes -> {
                            JsonNode node = objectMapper.readTree(bytes);
                            if (node == null || !node.isObject()) {
                                throw failure(
                                        ChessComClientException.Reason.INVALID_RESPONSE, null, null);
                            }
                            return node;
                        });
                    });
        } catch (ChessComClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw failure(ChessComClientException.Reason.UNAVAILABLE, null, exception);
        }
    }

    private static JsonNode read(InputStream body, int maxBytes, Function<byte[], JsonNode> parse) {
        byte[] bytes;
        try {
            bytes = body.readNBytes(maxBytes + 1);
        } catch (IOException exception) {
            throw failure(ChessComClientException.Reason.UNAVAILABLE, null, exception);
        }
        if (bytes.length > maxBytes) {
            throw failure(ChessComClientException.Reason.INVALID_RESPONSE, null, null);
        }
        try {
            return parse.apply(bytes);
        } catch (ChessComClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(ChessComClientException.Reason.INVALID_RESPONSE, null, exception);
        }
    }

    private static ChessComClientException failure(
            ChessComClientException.Reason reason, Long retryAfterSeconds, Throwable cause) {
        return new ChessComClientException(reason, retryAfterSeconds, cause);
    }

    private static RestClient createRestClient() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
