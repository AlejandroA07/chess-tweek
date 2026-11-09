package com.chesstweek.shared.database;

import java.nio.file.Path;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chess-tweek.database")
record DatabaseProperties(Path path) {

    DatabaseProperties {
        Objects.requireNonNull(path, "Database path must be configured");
    }
}
