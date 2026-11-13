package com.chesstweek.gamereview.internal.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chesstweek.gamereview.EngineStatus;

import static org.assertj.core.api.Assertions.assertThat;

class StockfishLocatorTest {

    @Test
    void acceptsTheHomebrewLinkIntoTheStockfishCellar(@TempDir Path directory) throws Exception {
        var location = StockfishHealthServiceTest.locator(directory).locate();

        assertThat(location.state()).isEqualTo(EngineStatus.State.NOT_CHECKED);
        assertThat(location.displayed()).isEqualTo(directory.resolve("bin/stockfish"));
        assertThat(location.executable())
                .isEqualTo(directory.resolve("Cellar/stockfish/17.1/bin/stockfish").toRealPath());
    }

    @Test
    void reportsAMissingStandardBinary(@TempDir Path directory) {
        var location = new StockfishLocator(
                null, directory.resolve("bin/stockfish"), directory.resolve("Cellar/stockfish"))
                .locate();

        assertThat(location.state()).isEqualTo(EngineStatus.State.NOT_INSTALLED);
        assertThat(location.usable()).isFalse();
    }

    @Test
    void rejectsAStandardLinkThatLeavesTheStockfishCellar(@TempDir Path directory)
            throws Exception {
        Path elsewhere = executable(directory.resolve("elsewhere/stockfish"));
        Path link = directory.resolve("bin/stockfish");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, elsewhere);
        Files.createDirectories(directory.resolve("Cellar/stockfish"));

        var location = new StockfishLocator(null, link, directory.resolve("Cellar/stockfish"))
                .locate();

        assertThat(location.state()).isEqualTo(EngineStatus.State.NOT_EXECUTABLE);
        assertThat(location.usable()).isFalse();
    }

    @Test
    void rejectsANonExecutableOrDirectoryTarget(@TempDir Path directory) throws Exception {
        Path plain = directory.resolve("plain/stockfish");
        Files.createDirectories(plain.getParent());
        Files.createFile(plain);
        Path folder = Files.createDirectories(directory.resolve("folder/stockfish"));

        assertThat(new StockfishLocator(plain, null, null).locate().state())
                .isEqualTo(EngineStatus.State.NOT_EXECUTABLE);
        assertThat(new StockfishLocator(folder, null, null).locate().state())
                .isEqualTo(EngineStatus.State.NOT_EXECUTABLE);
    }

    @Test
    void acceptsAConfiguredAbsoluteExecutableAnywhere(@TempDir Path directory) throws Exception {
        Path configured = executable(directory.resolve("custom/stockfish"));

        var location = new StockfishLocator(configured, null, null).locate();

        assertThat(location.state()).isEqualTo(EngineStatus.State.NOT_CHECKED);
        assertThat(location.executable()).isEqualTo(configured.toRealPath());
    }

    @Test
    void rejectsARelativeConfiguredPath() {
        var location = new StockfishLocator(Path.of("stockfish"), null, null).locate();

        assertThat(location.state()).isEqualTo(EngineStatus.State.NOT_EXECUTABLE);
        assertThat(location.usable()).isFalse();
    }

    @Test
    void reportsAMissingConfiguredBinary(@TempDir Path directory) {
        var location = new StockfishLocator(directory.resolve("missing"), null, null).locate();

        assertThat(location.state()).isEqualTo(EngineStatus.State.NOT_INSTALLED);
    }

    private static Path executable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.createFile(path, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwxr-xr-x")));
    }
}
