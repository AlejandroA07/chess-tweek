package com.chesstweek.gamereview;

import java.io.ByteArrayOutputStream;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.chesstweek.shared.http.RetryAfter;

final class HttpLichessGameClient implements LichessGameClient {

    private static final URI GAMES_ENDPOINT = URI.create("https://lichess.org/api/games/user");
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final int MAX_LINE_BYTES = 512 * 1024;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern GAME_ID_PATTERN = Pattern.compile("[A-Za-z0-9]{8,32}");
    private static final Set<String> FINISHED_STATUSES = Set.of(
            "mate",
            "resign",
            "stalemate",
            "timeout",
            "draw",
            "outoftime",
            "cheat",
            "unknownFinish",
            "insufficientMaterialClaim",
            "variantEnd");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    HttpLichessGameClient() {
        this(createRestClient(), Clock.systemUTC());
    }

    HttpLichessGameClient(RestClient restClient) {
        this(restClient, Clock.systemUTC());
    }

    HttpLichessGameClient(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.objectMapper = JsonMapper.builder().build();
        this.clock = clock;
    }

    @Override
    public GameExportBatch fetch(
            String accessToken,
            String username,
            int count,
            Instant before) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("Invalid Lichess username");
        }
        if (count < 1 || count > 50) {
            throw new IllegalArgumentException("Invalid Lichess game count");
        }

        URI requestUri = requestUri(username, count, before);
        try {
            return restClient.get()
                    .uri(requestUri)
                    .headers(headers -> {
                        headers.setBearerAuth(accessToken);
                        headers.setAccept(java.util.List.of(NDJSON));
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) {
                            throw failure(LichessGameClientException.Reason.UNAUTHORIZED, null, null);
                        }
                        if (status == 429) {
                            throw failure(
                                    LichessGameClientException.Reason.RATE_LIMITED,
                                    RetryAfter.parse(
                                            response.getHeaders().getFirst("Retry-After"),
                                            clock.instant()),
                                    null);
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw failure(LichessGameClientException.Reason.UNAVAILABLE, null, null);
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null || !NDJSON.isCompatibleWith(contentType)) {
                            throw failure(LichessGameClientException.Reason.INVALID_RESPONSE, null, null);
                        }
                        try {
                            return readBatch(response.getBody(), count);
                        } catch (InvalidGameExportException exception) {
                            throw failure(
                                    LichessGameClientException.Reason.INVALID_RESPONSE,
                                    null,
                                    exception);
                        } catch (IOException exception) {
                            throw failure(
                                    LichessGameClientException.Reason.UNAVAILABLE,
                                    null,
                                    exception);
                        }
                    });
        } catch (LichessGameClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw failure(LichessGameClientException.Reason.UNAVAILABLE, null, exception);
        }
    }

    private GameExportBatch readBatch(InputStream input, int requestedCount) throws IOException {
        var games = new ArrayList<ExportedGame>();
        var line = new ByteArrayOutputStream();
        int responseBytes = 0;
        int next;
        boolean pendingCarriageReturn = false;

        while ((next = input.read()) != -1) {
            responseBytes += 1;
            if (responseBytes > MAX_RESPONSE_BYTES) {
                throw new InvalidGameExportException("Lichess response is too large");
            }
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false;
                if (next != '\n') {
                    throw new InvalidGameExportException("Lichess returned invalid NDJSON");
                }
                addGame(games, line, requestedCount);
                line.reset();
            } else if (next == '\r') {
                pendingCarriageReturn = true;
            } else if (next == '\n') {
                addGame(games, line, requestedCount);
                line.reset();
            } else {
                if (line.size() >= MAX_LINE_BYTES) {
                    throw new InvalidGameExportException("Lichess game is too large");
                }
                line.write(next);
            }
        }
        if (pendingCarriageReturn) {
            throw new InvalidGameExportException("Lichess returned invalid NDJSON");
        }
        addGame(games, line, requestedCount);
        return new GameExportBatch(games);
    }

    private void addGame(
            ArrayList<ExportedGame> games,
            ByteArrayOutputStream line,
            int requestedCount) {
        if (line.size() == 0) {
            return;
        }
        if (games.size() >= requestedCount) {
            throw new InvalidGameExportException("Lichess returned too many games");
        }

        String payload;
        try {
            payload = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidGameExportException("Lichess returned invalid UTF-8", exception);
        }
        JsonNode game;
        try {
            game = objectMapper.readTree(payload);
        } catch (RuntimeException exception) {
            throw new InvalidGameExportException("Lichess returned invalid JSON", exception);
        }
        JsonNode id = game.get("id");
        JsonNode status = game.get("status");
        if (id == null
                || !id.isString()
                || !GAME_ID_PATTERN.matcher(id.stringValue()).matches()
                || status == null
                || !status.isString()
                || !FINISHED_STATUSES.contains(status.stringValue())) {
            throw new InvalidGameExportException("Lichess returned an invalid game");
        }
        games.add(new ExportedGame(id.stringValue(), status.stringValue(), payload));
    }

    private static URI requestUri(String username, int count, Instant before) {
        var builder = UriComponentsBuilder.fromUri(GAMES_ENDPOINT)
                .pathSegment(username)
                .queryParam("max", count)
                .queryParam("moves", true)
                .queryParam("pgnInJson", true)
                .queryParam("tags", true)
                .queryParam("clocks", true)
                .queryParam("evals", false)
                .queryParam("opening", true)
                .queryParam("division", true)
                .queryParam("ongoing", false)
                .queryParam("finished", true)
                .queryParam("literate", false)
                .queryParam("sort", "dateDesc");
        if (before != null) {
            builder.queryParam("until", before.toEpochMilli());
        }
        return builder.build().encode().toUri();
    }

    private static LichessGameClientException failure(
            LichessGameClientException.Reason reason,
            Long retryAfterSeconds,
            Throwable cause) {
        return new LichessGameClientException(reason, retryAfterSeconds, cause);
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

    private static final class InvalidGameExportException extends RuntimeException {

        private InvalidGameExportException(String message) {
            super(message);
        }

        private InvalidGameExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
