package com.chesstweek.gamereview.internal.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chesstweek.gamereview.EngineStatus;

import static org.assertj.core.api.Assertions.assertThat;

class StockfishHealthServiceTest {

    private static final Duration SHORT = Duration.ofMillis(300);

    @Test
    void reportsAReadyStockfishAndStopsTheProcess(@TempDir Path directory) throws Exception {
        var engine = FakeEngineProcess.stockfish("Stockfish 17.1");
        var service = service(directory, executable -> engine);

        assertThat(service.status().state()).isEqualTo(EngineStatus.State.NOT_CHECKED);

        EngineStatus status = service.check();

        assertThat(status.state()).isEqualTo(EngineStatus.State.READY);
        assertThat(status.engineName()).isEqualTo("Stockfish 17.1");
        assertThat(status.path()).endsWith("bin/stockfish");
        assertThat(engine.received()).containsExactly("uci", "isready", "quit");
        assertThat(engine.stopped()).isTrue();
        assertThat(service.status()).isEqualTo(status);
    }

    @Test
    void rejectsAnEngineThatIsNotStockfish(@TempDir Path directory) throws Exception {
        var engine = FakeEngineProcess.stockfish("SomeOtherEngine 1.0");

        EngineStatus status = service(directory, executable -> engine).check();

        assertThat(status.state()).isEqualTo(EngineStatus.State.UNSUPPORTED_ENGINE);
        assertThat(status.engineName()).isNull();
        assertThat(engine.stopped()).isTrue();
    }

    @Test
    void rejectsAnEngineNameWithUnexpectedCharacters(@TempDir Path directory) throws Exception {
        var engine = FakeEngineProcess.stockfish("Stockfish <script>");

        assertThat(service(directory, executable -> engine).check().state())
                .isEqualTo(EngineStatus.State.UNSUPPORTED_ENGINE);
    }

    @Test
    void reportsAnEngineThatNeverCompletesTheHandshake(@TempDir Path directory) throws Exception {
        var engine = new FakeEngineProcess(command -> List.of("id name Stockfish 17.1"));

        EngineStatus status = service(directory, executable -> engine).check();

        assertThat(status.state()).isEqualTo(EngineStatus.State.NOT_RESPONDING);
        assertThat(engine.stopped()).isTrue();
    }

    @Test
    void reportsAnEngineThatNeverBecomesReady(@TempDir Path directory) throws Exception {
        var engine = new FakeEngineProcess(command -> command.equals("uci")
                ? List.of("id name Stockfish 17.1", "uciok")
                : List.of());

        assertThat(service(directory, executable -> engine).check().state())
                .isEqualTo(EngineStatus.State.NOT_RESPONDING);
    }

    @Test
    void failsSafelyOnAnOverlongOutputLine(@TempDir Path directory) throws Exception {
        var engine = new FakeEngineProcess(command -> List.of());
        engine.emit("x".repeat(UciConnection.MAX_LINE_LENGTH + 1));

        assertThat(service(directory, executable -> engine).check().state())
                .isEqualTo(EngineStatus.State.NOT_RESPONDING);
        assertThat(engine.stopped()).isTrue();
    }

    @Test
    void reportsALaunchFailureWithoutDetail(@TempDir Path directory) throws Exception {
        EngineStatus status = service(directory, executable -> {
            throw new IOException("private launch detail");
        }).check();

        assertThat(status.state()).isEqualTo(EngineStatus.State.NOT_EXECUTABLE);
        assertThat(status.engineName()).isNull();
    }

    @Test
    void reportsBusyInsteadOfStartingASecondEngine(@TempDir Path directory) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slowEngine = new FakeEngineProcess(command -> {
            if (command.equals("uci")) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return List.of("id name Stockfish 17.1", "uciok");
            }
            return command.equals("isready") ? List.of("readyok") : List.of();
        });
        var service = new StockfishHealthService(
                locator(directory), executable -> slowEngine, Duration.ofSeconds(5));
        var first = new AtomicReference<EngineStatus>();
        Thread checker = Thread.ofVirtual().start(() -> first.set(service.check()));

        started.await();
        assertThat(service.check().state()).isEqualTo(EngineStatus.State.BUSY);
        release.countDown();
        checker.join();

        assertThat(first.get().state()).isEqualTo(EngineStatus.State.READY);
    }

    @Test
    void reportsAMissingBinaryWithoutLaunching(@TempDir Path directory) {
        var launches = new int[1];
        var service = new StockfishHealthService(
                new StockfishLocator(
                        null,
                        directory.resolve("bin/stockfish"),
                        directory.resolve("Cellar/stockfish")),
                executable -> {
                    launches[0]++;
                    throw new IOException("unexpected");
                },
                SHORT);

        assertThat(service.status().state()).isEqualTo(EngineStatus.State.NOT_INSTALLED);
        assertThat(service.check().state()).isEqualTo(EngineStatus.State.NOT_INSTALLED);
        assertThat(launches[0]).isZero();
    }

    private static StockfishHealthService service(Path directory, EngineProcessLauncher launcher)
            throws IOException {
        return new StockfishHealthService(locator(directory), launcher, SHORT);
    }

    static StockfishLocator locator(Path directory) throws IOException {
        Path binary = directory.resolve("Cellar/stockfish/17.1/bin/stockfish");
        Files.createDirectories(binary.getParent());
        if (!Files.exists(binary)) {
            Files.createFile(binary, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwxr-xr-x")));
        }
        Path link = directory.resolve("bin/stockfish");
        Files.createDirectories(link.getParent());
        if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(link, binary);
        }
        return new StockfishLocator(null, link, directory.resolve("Cellar/stockfish"));
    }
}
