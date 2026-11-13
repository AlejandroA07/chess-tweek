package com.chesstweek.gamereview.internal.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.gamereview.EngineHealth;
import com.chesstweek.gamereview.EngineStatus;

@Service
class StockfishHealthService implements EngineHealth {

    private static final Pattern STOCKFISH_NAME =
            Pattern.compile("Stockfish [A-Za-z0-9 ._+-]{1,48}");
    private static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

    private final StockfishLocator locator;
    private final EngineProcessLauncher launcher;
    private final Duration handshakeTimeout;
    private final ReentrantLock engineLock = new ReentrantLock();
    private volatile CheckedEngine lastCheck;

    @Autowired
    StockfishHealthService(StockfishLocator locator, EngineProcessLauncher launcher) {
        this(locator, launcher, DEFAULT_HANDSHAKE_TIMEOUT);
    }

    StockfishHealthService(
            StockfishLocator locator, EngineProcessLauncher launcher, Duration handshakeTimeout) {
        this.locator = locator;
        this.launcher = launcher;
        this.handshakeTimeout = handshakeTimeout;
    }

    @Override
    public EngineStatus status() {
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

    @Override
    public EngineStatus check() {
        StockfishLocator.Location location = locator.locate();
        if (!location.usable()) {
            return new EngineStatus(location.state(), display(location.displayed()), null);
        }
        if (!engineLock.tryLock()) {
            return new EngineStatus(EngineStatus.State.BUSY, display(location.displayed()), null);
        }
        try {
            EngineStatus status = handshake(location);
            lastCheck = new CheckedEngine(location.executable(), status);
            return status;
        } finally {
            engineLock.unlock();
        }
    }

    private EngineStatus handshake(StockfishLocator.Location location) {
        String displayed = display(location.displayed());
        EngineProcess process;
        try {
            process = launcher.launch(location.executable());
        } catch (IOException exception) {
            return new EngineStatus(EngineStatus.State.NOT_EXECUTABLE, displayed, null);
        }
        try (var connection = new UciConnection(process)) {
            var name = new String[1];
            connection.send("uci");
            connection.awaitLine("uciok"::equals, line -> {
                if (line.startsWith("id name ")) {
                    name[0] = line.substring("id name ".length()).strip();
                }
            }, handshakeTimeout);
            if (name[0] == null || !STOCKFISH_NAME.matcher(name[0]).matches()) {
                return new EngineStatus(EngineStatus.State.UNSUPPORTED_ENGINE, displayed, null);
            }
            connection.send("isready");
            connection.awaitLine("readyok"::equals, line -> {}, handshakeTimeout);
            return new EngineStatus(EngineStatus.State.READY, displayed, name[0]);
        } catch (UciConnection.UciException exception) {
            return new EngineStatus(EngineStatus.State.NOT_RESPONDING, displayed, null);
        }
    }

    private static String display(Path path) {
        return path == null ? null : path.toString();
    }

    private record CheckedEngine(Path executable, EngineStatus status) {}
}
