package com.chesstweek.training.internal.explorer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.shared.http.RetryAfter;
import com.chesstweek.training.OpeningExplorer;

/**
 * Asks explorer.lichess.org with the owner's bearer token. Every field of the answer is validated
 * and bounded before it is used; anything unexpected makes the whole answer invalid.
 */
@Component
final class HttpLichessExplorerClient implements LichessExplorerClient {

    static final URI MASTERS_ENDPOINT = URI.create("https://explorer.lichess.org/masters");
    static final URI LICHESS_ENDPOINT = URI.create("https://explorer.lichess.org/lichess");
    private static final int MOVES = 12;
    private static final int MASTERS_TOP_GAMES = 15;
    private static final int LICHESS_TOP_GAMES = 4;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_LIST = 64;
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern SAN = Pattern.compile(
            "(O-O(-O)?|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?)[+#]?");
    private static final Pattern ECO = Pattern.compile("[A-E][0-9]{2}");
    private static final Pattern GAME_ID = Pattern.compile("[A-Za-z0-9]{8,12}");
    private static final Pattern TITLE = Pattern.compile("[A-Z]{1,4}");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    HttpLichessExplorerClient() {
        this(createRestClient(), Clock.systemUTC());
    }

    HttpLichessExplorerClient(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.objectMapper = JsonMapper.builder().build();
        this.clock = clock;
    }

    @Override
    public ExplorerAnswer masters(String accessToken, ExplorerPosition position) {
        URI uri = UriComponentsBuilder.fromUri(MASTERS_ENDPOINT)
                .queryParam("fen", position.fen())
                .queryParam("moves", MOVES)
                .queryParam("topGames", MASTERS_TOP_GAMES)
                .build()
                .encode()
                .toUri();
        return fetch(accessToken, uri);
    }

    @Override
    public ExplorerAnswer lichess(
            String accessToken,
            ExplorerPosition position,
            Set<OpeningExplorer.Speed> speeds,
            Set<OpeningExplorer.RatingGroup> ratings) {
        var builder = UriComponentsBuilder.fromUri(LICHESS_ENDPOINT)
                .queryParam("variant", "standard")
                .queryParam("fen", position.fen())
                .queryParam("moves", MOVES)
                .queryParam("topGames", LICHESS_TOP_GAMES)
                .queryParam("recentGames", 0);
        if (!speeds.isEmpty()) {
            builder.queryParam("speeds", speeds.stream()
                    .sorted()
                    .map(OpeningExplorer.Speed::value)
                    .collect(Collectors.joining(",")));
        }
        if (!ratings.isEmpty()) {
            builder.queryParam("ratings", ratings.stream()
                    .sorted(Comparator.comparingInt(OpeningExplorer.RatingGroup::lowest))
                    .map(group -> Integer.toString(group.lowest()))
                    .collect(Collectors.joining(",")));
        }
        return fetch(accessToken, builder.build().encode().toUri());
    }

    private ExplorerAnswer fetch(String accessToken, URI uri) {
        try {
            return restClient.get()
                    .uri(uri)
                    .headers(headers -> {
                        headers.setBearerAuth(accessToken);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 403) {
                            throw failure(ExplorerClientException.Reason.UNAUTHORIZED, null, null);
                        }
                        if (status == 429) {
                            throw failure(
                                    ExplorerClientException.Reason.RATE_LIMITED,
                                    RetryAfter.parse(
                                            response.getHeaders().getFirst("Retry-After"),
                                            clock.instant()),
                                    null);
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw failure(ExplorerClientException.Reason.UNAVAILABLE, null, null);
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null
                                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                            throw failure(ExplorerClientException.Reason.INVALID_RESPONSE, null, null);
                        }
                        String body;
                        try {
                            body = readBounded(response.getBody());
                        } catch (InvalidAnswerException exception) {
                            throw failure(
                                    ExplorerClientException.Reason.INVALID_RESPONSE, null, exception);
                        } catch (IOException exception) {
                            throw failure(ExplorerClientException.Reason.UNAVAILABLE, null, exception);
                        }
                        try {
                            return parse(objectMapper.readTree(body));
                        } catch (RuntimeException exception) {
                            throw failure(
                                    ExplorerClientException.Reason.INVALID_RESPONSE, null, exception);
                        }
                    });
        } catch (ExplorerClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw failure(ExplorerClientException.Reason.UNAVAILABLE, null, exception);
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new InvalidAnswerException("Explorer answer is too large");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidAnswerException("Explorer answer is not UTF-8");
        }
    }

    static ExplorerAnswer parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidAnswerException("Explorer answer is not an object");
        }
        var moves = new ArrayList<OpeningExplorer.NextMove>();
        for (JsonNode move : list(root, "moves")) {
            moves.add(new OpeningExplorer.NextMove(
                    pattern(move, "uci", UCI),
                    pattern(move, "san", SAN),
                    optionalRating(move, "averageRating"),
                    results(move),
                    opening(move.get("opening"))));
        }
        var topGames = new ArrayList<OpeningExplorer.TopGame>();
        JsonNode games = root.get("topGames");
        if (games != null && !games.isNull()) {
            for (JsonNode game : list(root, "topGames")) {
                topGames.add(new OpeningExplorer.TopGame(
                        pattern(game, "id", GAME_ID),
                        pattern(game, "uci", UCI),
                        player(game.get("white")),
                        player(game.get("black")),
                        winner(game.get("winner")),
                        optionalYear(game.get("year"))));
            }
        }
        return new ExplorerAnswer(opening(root.get("opening")), results(root), moves, topGames);
    }

    private static List<JsonNode> list(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray() || node.size() > MAX_LIST) {
            throw new InvalidAnswerException("Explorer list is missing or too long");
        }
        var items = new ArrayList<JsonNode>(node.size());
        node.forEach(item -> {
            if (!item.isObject()) {
                throw new InvalidAnswerException("Explorer list item is not an object");
            }
            items.add(item);
        });
        return items;
    }

    private static OpeningExplorer.Results results(JsonNode node) {
        return new OpeningExplorer.Results(count(node, "white"), count(node, "draws"), count(node, "black"));
    }

    private static long count(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidAnswerException("Explorer count is missing");
        }
        long count = value.longValue();
        if (count < 0 || count > 10_000_000_000L) {
            throw new InvalidAnswerException("Explorer count is out of range");
        }
        return count;
    }

    private static OpeningExplorer.Opening opening(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new InvalidAnswerException("Explorer opening is not an object");
        }
        return new OpeningExplorer.Opening(pattern(node, "eco", ECO), text(node.get("name"), 160));
    }

    private static OpeningExplorer.Player player(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidAnswerException("Explorer player is missing");
        }
        JsonNode title = node.get("title");
        String checkedTitle = null;
        if (title != null && !title.isNull()) {
            if (!title.isString() || !TITLE.matcher(title.stringValue()).matches()) {
                throw new InvalidAnswerException("Explorer title is invalid");
            }
            checkedTitle = title.stringValue();
        }
        return new OpeningExplorer.Player(
                text(node.get("name"), 80), optionalRating(node, "rating"), checkedTitle);
    }

    private static String winner(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isString() && ("white".equals(node.stringValue()) || "black".equals(node.stringValue()))) {
            return node.stringValue();
        }
        throw new InvalidAnswerException("Explorer winner is invalid");
    }

    private static Integer optionalYear(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.intValue() < 1400 || node.intValue() > 3000) {
            throw new InvalidAnswerException("Explorer year is invalid");
        }
        return node.intValue();
    }

    private static Integer optionalRating(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.intValue() < 0 || node.intValue() > 4000) {
            throw new InvalidAnswerException("Explorer rating is invalid");
        }
        return node.intValue();
    }

    private static String pattern(JsonNode parent, String field, Pattern pattern) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isString() || !pattern.matcher(node.stringValue()).matches()) {
            throw new InvalidAnswerException("Explorer field is invalid: " + field);
        }
        return node.stringValue();
    }

    private static String text(JsonNode node, int maxLength) {
        if (node == null || !node.isString()) {
            throw new InvalidAnswerException("Explorer text is missing");
        }
        String value = node.stringValue();
        if (value.isEmpty()
                || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidAnswerException("Explorer text is invalid");
        }
        return value;
    }

    private static ExplorerClientException failure(
            ExplorerClientException.Reason reason, Long retryAfterSeconds, Throwable cause) {
        return new ExplorerClientException(reason, retryAfterSeconds, cause);
    }

    private static RestClient createRestClient() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    static final class InvalidAnswerException extends RuntimeException {

        InvalidAnswerException(String message) {
            super(message);
        }
    }
}
