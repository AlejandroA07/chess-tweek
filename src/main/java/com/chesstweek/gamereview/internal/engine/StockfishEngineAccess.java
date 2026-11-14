package com.chesstweek.gamereview.internal.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chesstweek.gamereview.EngineStatus;

/**
 * The only way to run Stockfish. Locates the binary, allows one engine operation at a time,
 * completes the bounded UCI handshake, runs the task, and always stops the process.
 */
@Component
class StockfishEngineAccess {

    private static final Pattern STOCKFISH_NAME =
            Pattern.compile("Stockfish [A-Za-z0-9 ._+-]{1,48}");
    private static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

    private final StockfishLocator locator;
    private final EngineProcessLauncher launcher;
    private final Duration handshakeTimeout;
    private final ReentrantLock engineLock = new ReentrantLock();
    private volatile CheckedEngine lastCheck;

    @Autowired
    StockfishEngineAccess(StockfishLocator locator, EngineProcessLauncher launcher) {
        this(locator, launcher, DEFAULT_HANDSHAKE_TIMEOUT);
    }

    StockfishEngineAccess(
            StockfishLocator locator, EngineProcessLauncher launcher, Duration handshakeTimeout) {
        this.locator = locator;
        this.launcher = launcher;
        this.handshakeTimeout = handshakeTimeout;
    }

    /** The located binary with the last handshake result for it, without starting anything. */
    EngineStatus status() {
        StockfishLocator.Location location = locator.locate();
        if (!location.usable()) {
            return new EngineStatus(location.state(), display(location.displayed()), null);
        }
        CheckedEngine checked = lastCheck;
        if (checked != null && checked.executable().equals(location.executable())) {
            return checked.status();
        }
        return new EngineStatus(EngineStatus.State.NOT_CHECKED, display(location.displayed()), null);
    }

    /**
     * Runs {@code task} on a ready Stockfish. The result carries the handshake status; the task
     * value is present only when the status is {@link EngineStatus.State#READY} and the task
     * completed.
     */
    <T> Outcome<T> withReadyEngine(EngineTask<T> task) {
        StockfishLocator.Location location = locator.locate();
        String displayed = display(location.displayed());
        if (!location.usable()) {
            return Outcome.unavailable(new EngineStatus(location.state(), displayed, null));
        }
        if (!engineLock.tryLock()) {
            return Outcome.unavailable(new EngineStatus(EngineStatus.State.BUSY, displayed, null));
        }
        try {
            EngineProcess process;
            try {
                process = launcher.launch(location.executable());
            } catch (IOException exception) {
                return remember(location, Outcome.unavailable(
                        new EngineStatus(EngineStatus.State.NOT_EXECUTABLE, displayed, null)));
            }
            try (var connection = new UciConnection(process)) {
                String name = handshake(connection);
                if (name == null) {
                    return remember(location, Outcome.unavailable(new EngineStatus(
                            EngineStatus.State.UNSUPPORTED_ENGINE, displayed, null)));
                }
                var ready = new EngineStatus(EngineStatus.State.READY, displayed, name);
                remember(location, Outcome.unavailable(ready));
                try {
                    return new Outcome<>(ready, task.run(connection, name));
                } catch (UciConnection.UciException exception) {
                    return new Outcome<>(ready, null);
                }
            } catch (UciConnection.UciException exception) {
                return remember(location, Outcome.unavailable(
                        new EngineStatus(EngineStatus.State.NOT_RESPONDING, displayed, null)));
            }
        } finally {
            engineLock.unlock();
        }
    }

    private String handshake(UciConnection connection) throws UciConnection.UciException {
        var name = new String[1];
        connection.send("uci");
        connection.awaitLine("uciok"::equals, line -> {
            if (line.startsWith("id name ")) {
                name[0] = line.substring("id name ".length()).strip();
            }
        }, handshakeTimeout);
        if (name[0] == null || !STOCKFISH_NAME.matcher(name[0]).matches()) {
            return null;
        }
        connection.send("isready");
        connection.awaitLine("readyok"::equals, line -> {}, handshakeTimeout);
        return name[0];
    }

    private <T> Outcome<T> remember(StockfishLocator.Location location, Outcome<T> outcome) {
        lastCheck = new CheckedEngine(location.executable(), outcome.status());
        return outcome;
    }

    private static String display(Path path) {
        return path == null ? null : path.toString();
    }

    @FunctionalInterface
    interface EngineTask<T> {

        T run(UciConnection connection, String engineName) throws UciConnection.UciException;
    }

    /** {@code value} is null when the engine was unavailable or the task failed. */
    record Outcome<T>(EngineStatus status, T value) {

        static <T> Outcome<T> unavailable(EngineStatus status) {
            return new Outcome<>(status, null);
        }
    }

    private record CheckedEngine(Path executable, EngineStatus status) {}
}
