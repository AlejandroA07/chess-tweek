package com.chesstweek.composition.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import com.chesstweek.ChessTweekApplication;

import static org.assertj.core.api.Assertions.assertThat;

class StaticResourceCachingTest {

    private static final Pattern SCRIPT = Pattern.compile("/assets/index-[A-Za-z0-9_-]+\\.js");

    @Test
    void neverLetsTheBrowserReuseAStaleApplicationPage(@TempDir Path temporaryDirectory)
            throws Exception {
        try (var application = new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--chess-tweek.database.path="
                                + temporaryDirectory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF")) {
            String base = "http://127.0.0.1:"
                    + application.getEnvironment().getProperty("local.server.port");
            HttpClient client = HttpClient.newHttpClient();

            for (String page : new String[] {"/", "/index.html"}) {
                HttpResponse<String> response = get(client, base + page);
                assertThat(response.statusCode()).as(page).isEqualTo(200);
                assertThat(response.headers().firstValue("Cache-Control"))
                        .as(page)
                        .hasValue("no-store");
                assertThat(response.headers().firstValue("Last-Modified")).as(page).isEmpty();
                assertThat(response.body()).as(page).contains("<div id=\"root\">");
            }

            Matcher script = SCRIPT.matcher(get(client, base + "/index.html").body());
            assertThat(script.find()).as("built page references a hashed script").isTrue();
            HttpResponse<String> asset = get(client, base + script.group());
            assertThat(asset.statusCode()).isEqualTo(200);
            assertThat(asset.headers().firstValue("Cache-Control"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .contains("max-age=31536000")
                            .contains("immutable"));

            HttpResponse<String> api = get(client, base + "/api/health");
            assertThat(api.statusCode()).isEqualTo(200);
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
