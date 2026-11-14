package com.chesstweek.gamereview;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.gamereview.internal.persistence.EngineProfileStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineProfilePersistenceTest {

    @Test
    void storesImmutableProfilesAndReturnsTheLatestAfterRestart(@TempDir Path directory) {
        Path database = directory.resolve("runtime/chess-tweek.db");
        EngineProfile first;
        EngineProfile second;
        try (var application = start(database)) {
            var store = application.getBean(EngineProfileStore.class);
            assertThat(application.getBean(EngineBenchmark.class).currentProfile()).isEmpty();

            first = store.save(profile(1_000_000, Instant.parse("2026-11-14T09:00:00Z")));
            second = store.save(profile(1_100_000, Instant.parse("2026-11-14T10:00:00Z")));

            assertThat(first.id()).isPositive();
            assertThat(second.id()).isGreaterThan(first.id());
        }

        try (var restarted = start(database)) {
            assertThat(restarted.getBean(EngineBenchmark.class).currentProfile()).contains(second);
            assertThat(restarted.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM game_review_engine_profile", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void rejectsOutOfRangeProfilesInTheDatabase(@TempDir Path directory) {
        try (var application = start(directory.resolve("runtime/chess-tweek.db"))) {
            var jdbc = application.getBean(JdbcTemplate.class);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO game_review_engine_profile (
                        engine_name, threads, hash_mb, multi_pv, benchmark_positions,
                        benchmark_nodes_per_position, nodes_per_second, quick_target_ms,
                        deep_target_ms, quick_nodes, deep_nodes, created_at_ms
                    ) VALUES ('Stockfish 19', 2, 256, 3, 6, 1000000, 1000000, 750, 3000,
                              3000000, 750000, 0)
                    """))
                    .hasMessageContaining("CHECK constraint failed");
        }
    }

    private static EngineProfile profile(long nodesPerSecond, Instant createdAt) {
        return new EngineProfile(
                0, "Stockfish 19", 2, 256, 3, 6, 1_000_000, nodesPerSecond, 750, 3_000,
                nodesPerSecond * 3 / 4, nodesPerSecond * 3, createdAt);
    }

    private static ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + database, "--logging.level.root=OFF");
    }
}
