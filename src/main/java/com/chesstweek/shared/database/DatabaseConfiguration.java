package com.chesstweek.shared.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import javax.sql.DataSource;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
class DatabaseConfiguration {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    @Bean(destroyMethod = "close")
    DataSource dataSource(DatabaseProperties properties) throws IOException {
        var databasePath = prepareDatabasePath(properties.path());
        var sqlite = new SQLiteDataSource(sqliteConfig());
        sqlite.setUrl("jdbc:sqlite:" + databasePath);

        var pool = new HikariConfig();
        pool.setPoolName("chess-tweek-db");
        pool.setDataSource(sqlite);
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setIdleTimeout(30_000);
        pool.setMaxLifetime(0);
        return new HikariDataSource(pool);
    }

    private Path prepareDatabasePath(Path configuredPath) throws IOException {
        var databasePath = configuredPath.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(databasePath)) {
            throw new IOException("Database path must not be a symbolic link");
        }
        var parent = databasePath.getParent();
        if (parent != null) {
            var directoryAlreadyExists = Files.exists(parent);
            Files.createDirectories(parent);
            if (directoryAlreadyExists
                    && !Files.getPosixFilePermissions(parent).equals(DIRECTORY_PERMISSIONS)) {
                throw new IOException("Database directory must be owner-only");
            }
            if (!directoryAlreadyExists) {
                Files.setPosixFilePermissions(parent, DIRECTORY_PERMISSIONS);
            }
        }
        if (Files.notExists(databasePath)) {
            Files.createFile(databasePath, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        }
        Files.setPosixFilePermissions(databasePath, FILE_PERMISSIONS);
        return databasePath;
    }

    private SQLiteConfig sqliteConfig() {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5_000);
        return config;
    }
}
