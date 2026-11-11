package com.chesstweek.shared.database;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseFoundationTest {

    @Test
    void databaseSurvivesAnApplicationRestart(@TempDir Path temporaryDirectory) throws Exception {
        var databasePath = temporaryDirectory.resolve("runtime/chess-tweek.db");

        startAndStop(databasePath);

        try (var restarted = start(databasePath)) {
            var jdbc = restarted.getBean(JdbcTemplate.class);

            assertThat(databasePath).isRegularFile();
            assertThat(java.nio.file.Files.getPosixFilePermissions(databasePath))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                    Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master "
                            + "WHERE type = 'table' AND name = 'game_review_game'",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("wal");
            assertThat(jdbc.queryForObject("PRAGMA synchronous", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(5_000);
        }
    }

    @Test
    void refusesASymbolicLinkDatabasePath(@TempDir Path temporaryDirectory) throws Exception {
        var externalFile = java.nio.file.Files.createFile(temporaryDirectory.resolve("external.db"));
        var runtimeDirectory = java.nio.file.Files.createDirectory(temporaryDirectory.resolve("runtime"));
        var linkedDatabase = java.nio.file.Files.createSymbolicLink(
                runtimeDirectory.resolve("chess-tweek.db"), externalFile);

        assertThatThrownBy(() -> {
            try (var ignored = start(linkedDatabase)) {
                // Startup must fail before a context is returned.
            }
        }).hasRootCauseMessage("Database path must not be a symbolic link");
    }

    @Test
    void refusesAnUnsafeExistingDirectoryWithoutChangingIt(@TempDir Path temporaryDirectory)
            throws Exception {
        var unsafePermissions = PosixFilePermissions.fromString("rwxr-xr-x");
        var existingDirectory = java.nio.file.Files.createDirectory(
                temporaryDirectory.resolve("existing"));
        java.nio.file.Files.setPosixFilePermissions(existingDirectory, unsafePermissions);

        assertThatThrownBy(() -> {
            try (var ignored = start(existingDirectory.resolve("chess-tweek.db"))) {
                // Startup must fail before a context is returned.
            }
        }).hasRootCauseMessage("Database directory must be owner-only");

        assertThat(java.nio.file.Files.getPosixFilePermissions(existingDirectory))
                .containsExactlyInAnyOrderElementsOf(unsafePermissions);
    }

    private void startAndStop(Path databasePath) {
        try (var ignored = start(databasePath)) {
            // Closing the context simulates the application being stopped normally.
        }
    }

    private ConfigurableApplicationContext start(Path databasePath) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--chess-tweek.database.path=" + databasePath,
                        "--logging.level.root=OFF");
    }
}
