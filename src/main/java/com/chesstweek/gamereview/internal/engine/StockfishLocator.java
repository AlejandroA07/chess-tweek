package com.chesstweek.gamereview.internal.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.chesstweek.gamereview.EngineStatus;

/**
 * Finds the Stockfish binary. The standard location must resolve into Homebrew's Stockfish
 * cellar; an explicitly configured path is trusted local configuration but must still be an
 * absolute path to an executable regular file.
 */
final class StockfishLocator {

    static final Path HOMEBREW_BINARY = Path.of("/opt/homebrew/bin/stockfish");
    static final Path HOMEBREW_CELLAR = Path.of("/opt/homebrew/Cellar/stockfish");

    private final Path configured;
    private final Path standardBinary;
    private final Path standardCellar;

    StockfishLocator(Path configured, Path standardBinary, Path standardCellar) {
        this.configured = configured;
        this.standardBinary = standardBinary;
        this.standardCellar = standardCellar;
    }

    static StockfishLocator homebrew(Path configured) {
        return new StockfishLocator(configured, HOMEBREW_BINARY, HOMEBREW_CELLAR);
    }

    Location locate() {
        if (configured != null) {
            if (!configured.isAbsolute()) {
                return Location.unavailable(EngineStatus.State.NOT_EXECUTABLE, configured);
            }
            return inspect(configured, null);
        }
        return inspect(standardBinary, standardCellar);
    }

    private static Location inspect(Path candidate, Path requiredRoot) {
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Location.unavailable(EngineStatus.State.NOT_INSTALLED, candidate);
        }
        try {
            Path real = candidate.toRealPath();
            boolean accepted = Files.isRegularFile(real)
                    && Files.isExecutable(real)
                    && (requiredRoot == null || real.startsWith(realRoot(requiredRoot)));
            return accepted
                    ? new Location(EngineStatus.State.NOT_CHECKED, candidate, real)
                    : Location.unavailable(EngineStatus.State.NOT_EXECUTABLE, candidate);
        } catch (IOException exception) {
            return Location.unavailable(EngineStatus.State.NOT_INSTALLED, candidate);
        }
    }

    private static Path realRoot(Path root) throws IOException {
        return Files.exists(root) ? root.toRealPath() : root;
    }

    /** {@code executable} is the resolved binary to launch, or null when none is usable. */
    record Location(EngineStatus.State state, Path displayed, Path executable) {

        static Location unavailable(EngineStatus.State state, Path displayed) {
            return new Location(state, displayed, null);
        }

        boolean usable() {
            return executable != null;
        }
    }
}
