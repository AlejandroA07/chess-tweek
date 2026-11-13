package com.chesstweek.gamereview.internal.engine;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code path} optionally overrides the standard Homebrew location with a local binary. */
@ConfigurationProperties("chess-tweek.stockfish")
record StockfishProperties(Path path) {}
