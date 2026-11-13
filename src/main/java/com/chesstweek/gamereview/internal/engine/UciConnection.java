package com.chesstweek.gamereview.internal.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Line-oriented UCI exchange with a bounded reader. Engine output is read on a virtual thread
 * so every wait has a deadline; overlong lines, too many unread lines, or the end of output fail
 * the exchange instead of growing memory or blocking forever.
 */
final class UciConnection implements AutoCloseable {

    static final int MAX_LINE_LENGTH = 4_096;
    static final int MAX_BUFFERED_LINES = 2_048;
    private static final String END_OF_OUTPUT = "\u0000end";
    private static final String OVERFLOW = "\u0000overflow";

    private final EngineProcess process;
    private final OutputStream input;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final Thread reader;

    UciConnection(EngineProcess process) {
        this.process = process;
        this.input = process.input();
        this.reader = Thread.ofVirtual().name("stockfish-output").start(this::readOutput);
    }

    void send(String command) throws UciException {
        if (command.isEmpty() || command.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("UCI commands are single printable lines");
        }
        try {
            input.write((command + "\n").getBytes(StandardCharsets.US_ASCII));
            input.flush();
        } catch (IOException exception) {
            throw new UciException("Engine input closed", exception);
        }
    }

    /** Returns the first line matching {@code target}, passing every earlier line to {@code seen}. */
    String awaitLine(Predicate<String> target, Consumer<String> seen, Duration timeout)
            throws UciException {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                String line = remaining <= 0 ? null : lines.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null) {
                    throw new UciException("Engine did not answer in time", null);
                }
                if (line.equals(END_OF_OUTPUT) || line.equals(OVERFLOW)) {
                    throw new UciException("Engine output ended or exceeded limits", null);
                }
                if (target.test(line)) {
                    return line;
                }
                seen.accept(line);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UciException("Interrupted while waiting for the engine", exception);
        }
    }

    private void readOutput() {
        try (InputStream output = process.output()) {
            var line = new StringBuilder();
            int next;
            while ((next = output.read()) != -1) {
                if (next == '\n') {
                    if (lines.size() >= MAX_BUFFERED_LINES) {
                        lines.add(OVERFLOW);
                        return;
                    }
                    lines.add(stripCarriageReturn(line));
                    line.setLength(0);
                } else if (line.length() >= MAX_LINE_LENGTH) {
                    lines.add(OVERFLOW);
                    return;
                } else {
                    line.append((char) next);
                }
            }
        } catch (IOException exception) {
            // Treated as the end of output below.
        }
        lines.add(END_OF_OUTPUT);
    }

    private static String stripCarriageReturn(StringBuilder line) {
        int length = line.length();
        return length > 0 && line.charAt(length - 1) == '\r'
                ? line.substring(0, length - 1)
                : line.toString();
    }

    @Override
    public void close() {
        try {
            send("quit");
        } catch (UciException | RuntimeException exception) {
            // The process is stopped below either way.
        }
        try {
            input.close();
        } catch (IOException exception) {
            // Closing stdin also asks the engine to exit.
        }
        process.stop(Duration.ofSeconds(2));
        reader.interrupt();
    }

    static final class UciException extends Exception {

        UciException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
