package com.chesstweek.gamereview.internal.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Scripted engine: each command line received is answered with the responder's lines. */
final class FakeEngineProcess implements EngineProcess {

    private final PipedOutputStream engineOutput = new PipedOutputStream();
    private final PipedInputStream applicationInput;
    private final Function<String, List<String>> responder;
    private final List<String> received = new ArrayList<>();
    private volatile boolean stopped;

    FakeEngineProcess(Function<String, List<String>> responder) {
        this.responder = responder;
        try {
            this.applicationInput = new PipedInputStream(engineOutput, 1 << 16);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static FakeEngineProcess stockfish(String name) {
        return new FakeEngineProcess(command -> switch (command) {
            case "uci" -> List.of(
                    "id name " + name,
                    "id author the Stockfish developers",
                    "option name Threads type spin default 1 min 1 max 1024",
                    "uciok");
            case "isready" -> List.of("readyok");
            default -> List.of();
        });
    }

    /** Writes raw bytes to the application, for malformed-output scenarios. */
    void emit(String raw) {
        try {
            engineOutput.write(raw.getBytes(StandardCharsets.US_ASCII));
            engineOutput.flush();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    synchronized List<String> received() {
        return List.copyOf(received);
    }

    boolean stopped() {
        return stopped;
    }

    @Override
    public OutputStream input() {
        return new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();

            @Override
            public void write(int value) throws IOException {
                if (stopped) {
                    throw new IOException("Engine stopped");
                }
                if (value != '\n') {
                    line.write(value);
                    return;
                }
                String command = line.toString(StandardCharsets.US_ASCII);
                line.reset();
                synchronized (FakeEngineProcess.this) {
                    received.add(command);
                }
                for (String response : responder.apply(command)) {
                    engineOutput.write((response + "\n").getBytes(StandardCharsets.US_ASCII));
                }
                engineOutput.flush();
            }
        };
    }

    @Override
    public InputStream output() {
        return applicationInput;
    }

    @Override
    public void stop(Duration timeout) {
        stopped = true;
        try {
            engineOutput.close();
        } catch (IOException exception) {
            // Already closed.
        }
    }
}
