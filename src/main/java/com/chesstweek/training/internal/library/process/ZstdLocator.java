package com.chesstweek.training.internal.library.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Finds the zstd prerequisite. The standard location must resolve into Homebrew's zstd cellar; an
 * explicitly configured path is trusted local configuration but must still be an absolute path to
 * an executable regular file. The application never searches PATH.
 */
@Component
public final class ZstdLocator {

    static final Path HOMEBREW_BINARY = Path.of("/opt/homebrew/bin/zstd");
    static final Path HOMEBREW_CELLAR = Path.of("/opt/homebrew/Cellar/zstd");

    private final Path configured;
    private final Path standardBinary;
    private final Path standardCellar;

    @Autowired
    ZstdLocator(@Value("${chess-tweek.puzzle-corpus.zstd-path:}") String configured) {
        this(configured == null || configured.isBlank() ? null : Path.of(configured),
                HOMEBREW_BINARY, HOMEBREW_CELLAR);
    }

    ZstdLocator(Path configured, Path standardBinary, Path standardCellar) {
        this.configured = configured;
        this.standardBinary = standardBinary;
        this.standardCellar = standardCellar;
    }

    /** The resolved executable, or empty when zstd is missing or unusable. */
    public Optional<Path> locate() {
        if (configured != null) {
            return configured.isAbsolute() ? inspect(configured, null) : Optional.empty();
        }
        return inspect(standardBinary, standardCellar);
    }

    private static Optional<Path> inspect(Path candidate, Path requiredRoot) {
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Path real = candidate.toRealPath();
            boolean accepted = Files.isRegularFile(real)
                    && Files.isExecutable(real)
                    && (requiredRoot == null
                            || (Files.exists(requiredRoot) && real.startsWith(requiredRoot.toRealPath())));
            return accepted ? Optional.of(real) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }
}
