package com.chesstweek.training;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.training.internal.persistence.ExplorerCacheStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplorerCachePersistenceTest {

    @Test
    void replacesEntriesAndKeepsOnlyTheNewestWithinTheLimit(@TempDir Path directory) {
        Path database = directory.resolve("runtime/chess-tweek.db");
        try (var application = start(database)) {
            var cache = application.getBean(ExplorerCacheStore.class);

            cache.put(key('a'), "masters", "{\"v\":1}", Instant.ofEpochMilli(1_000), 2);
            cache.put(key('b'), "lichess", "{\"v\":2}", Instant.ofEpochMilli(2_000), 2);
            cache.put(key('a'), "masters", "{\"v\":3}", Instant.ofEpochMilli(3_000), 2);
            cache.put(key('c'), "masters", "{\"v\":4}", Instant.ofEpochMilli(4_000), 2);

            assertThat(cache.find(key('a'))).hasValueSatisfying(entry -> {
                assertThat(entry.responseJson()).isEqualTo("{\"v\":3}");
                assertThat(entry.fetchedAt()).isEqualTo(Instant.ofEpochMilli(3_000));
            });
            assertThat(cache.find(key('b'))).isEmpty();
            assertThat(cache.find(key('c'))).isPresent();
            assertThatThrownBy(() -> cache.find("not-a-digest"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (var restarted = start(database)) {
            assertThat(restarted.getBean(ExplorerCacheStore.class).find(key('c'))).isPresent();
        }
    }

    private static String key(char digit) {
        return String.valueOf(digit).repeat(64);
    }

    private static ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + database, "--logging.level.root=OFF");
    }
}
