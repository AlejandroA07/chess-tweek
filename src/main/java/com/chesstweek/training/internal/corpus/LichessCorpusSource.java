package com.chesstweek.training.internal.corpus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The official Lichess Puzzle export. Only the one configured file URL is ever requested; it is
 * validated at startup, redirects are never followed, and requests carry a generic client name.
 */
@Component
class LichessCorpusSource {

    static final String OFFICIAL_URL = "https://database.lichess.org/lichess_db_puzzle.csv.zst";
    static final String USER_AGENT = "ChessTweek/0.1 (local single-user app)";
    static final long MAX_SOURCE_BYTES = 10L * 1024 * 1024 * 1024;

    private static final Pattern ETAG = Pattern.compile("\"[\\x21\\x23-\\x7E]{1,120}\"");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]{1,12})-([0-9]{1,12})/([0-9]{1,12})");
    private static final Instant EARLIEST = Instant.parse("2020-01-01T00:00:00Z");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final URI source;
    private final HttpClient client;
    private final Clock clock;

    @Autowired
    LichessCorpusSource(@Value("${chess-tweek.puzzle-corpus.source-url:" + OFFICIAL_URL + "}") String source) {
        this(validated(source), HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build(), Clock.systemUTC());
    }

    LichessCorpusSource(URI source, HttpClient client, Clock clock) {
        this.source = source;
        this.client = client;
        this.clock = clock;
    }

    String url() {
        return source.toString();
    }

    /** The validated identity of the published snapshot. */
    record RemoteSnapshot(String url, String etag, Instant lastModified, LocalDate snapshotDate, long bytes) {}

    /** A response body positioned at {@code offset}; {@code offset} is 0 when the server restarted. */
    record Download(InputStream body, long offset) {}

    static final class SourceException extends Exception {
        private final String code;

        SourceException(String code) {
            super(code);
            this.code = code;
        }

        SourceException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    RemoteSnapshot head() throws SourceException {
        HttpRequest request = HttpRequest.newBuilder(source)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", USER_AGENT)
                .timeout(RESPONSE_TIMEOUT)
                .build();
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding(),
                "update_check_failed");
        if (response.statusCode() != 200) {
            throw new SourceException("update_check_failed");
        }
        return snapshot(response.headers(), "update_check_failed");
    }

    /**
     * Opens the snapshot body from {@code offset}. A partial download resumes only while the
     * server still publishes the same strong ETag; otherwise the snapshot has changed.
     */
    Download open(RemoteSnapshot expected, long offset) throws SourceException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(source)
                .GET()
                .header("User-Agent", USER_AGENT)
                .timeout(RESPONSE_TIMEOUT);
        if (offset > 0) {
            builder.header("Range", "bytes=" + offset + "-").header("If-Range", expected.etag());
        }
        HttpResponse<InputStream> response = send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream(), "download_failed");
        try {
            HttpHeaders headers = response.headers();
            String etag = headers.firstValue("ETag").orElse(null);
            if (!expected.etag().equals(etag)) {
                throw new SourceException("source_changed");
            }
            if (response.statusCode() == 200) {
                if (contentLength(headers).orElse(-1) != expected.bytes()) {
                    throw new SourceException("source_changed");
                }
                return new Download(response.body(), 0);
            }
            if (response.statusCode() == 206 && offset > 0) {
                Matcher range = CONTENT_RANGE.matcher(headers.firstValue("Content-Range").orElse(""));
                if (!range.matches()
                        || Long.parseLong(range.group(1)) != offset
                        || Long.parseLong(range.group(2)) != expected.bytes() - 1
                        || Long.parseLong(range.group(3)) != expected.bytes()) {
                    throw new SourceException("source_changed");
                }
                return new Download(response.body(), offset);
            }
            throw new SourceException("download_failed");
        } catch (SourceException | RuntimeException exception) {
            close(response.body());
            throw exception;
        }
    }

    private RemoteSnapshot snapshot(HttpHeaders headers, String failure) throws SourceException {
        String etag = headers.firstValue("ETag").orElse("");
        long bytes = contentLength(headers).orElse(-1);
        Instant lastModified;
        try {
            lastModified = ZonedDateTime.parse(
                    headers.firstValue("Last-Modified").orElse(""),
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException exception) {
            throw new SourceException(failure, exception);
        }
        if (!ETAG.matcher(etag).matches()
                || bytes < 1 || bytes > MAX_SOURCE_BYTES
                || lastModified.isBefore(EARLIEST)
                || lastModified.isAfter(clock.instant().plus(Duration.ofDays(1)))) {
            throw new SourceException(failure);
        }
        return new RemoteSnapshot(url(), etag, lastModified,
                LocalDate.ofInstant(lastModified, ZoneOffset.UTC), bytes);
    }

    private <T> HttpResponse<T> send(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, String failure)
            throws SourceException {
        try {
            return client.send(request, handler);
        } catch (IOException exception) {
            throw new SourceException(failure, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceException(failure, exception);
        }
    }

    private static OptionalLong contentLength(HttpHeaders headers) {
        Optional<String> value = headers.firstValue("Content-Length");
        if (value.isEmpty() || !value.get().matches("[0-9]{1,12}")) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Long.parseLong(value.get()));
    }

    private static void close(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // The response is abandoned; nothing else depends on it.
        }
    }

    /** Production uses the official HTTPS file; a loopback HTTP source is accepted for tests. */
    static URI validated(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Puzzle corpus source URL is invalid", exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean loopback = "http".equals(scheme) && "127.0.0.1".equals(host);
        if (!(("https".equals(scheme) && !host.isBlank()) || loopback)
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() == null || !uri.getPath().endsWith(".csv.zst")) {
            throw new IllegalArgumentException("Puzzle corpus source URL is invalid");
        }
        return uri;
    }
}
