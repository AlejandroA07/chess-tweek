package com.chesstweek.training.internal.library.process;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single approved process boundary for reading a local zstd snapshot. The compressed file is
 * fed to zstd through standard input by the application, so the caller can observe exactly how
 * much of the source has been consumed.
 */
public final class ZstdInput {

    private static final long TEST_TIMEOUT_SECONDS = 600;

    private ZstdInput() {}

    public static InputStream open(Path executable, Path file) throws IOException {
        return open(executable, file, new AtomicLong());
    }

    /** Decompresses {@code file}; {@code consumed} receives the compressed bytes fed so far. */
    public static InputStream open(Path executable, Path file, AtomicLong consumed)
            throws IOException {
        InputStream source = Files.newInputStream(file);
        Process process;
        try {
            process = new ProcessBuilder(executable.toString(), "-dcq")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException exception) {
            source.close();
            throw exception;
        }
        Thread feeder = Thread.ofVirtual().name("zstd-feeder").start(() -> feed(source, process, consumed));
        return new CheckedProcessInputStream(process, feeder);
    }

    /** Runs the complete zstd integrity test, including frame content checksums. */
    public static boolean test(Path executable, Path file) throws IOException {
        Process process = new ProcessBuilder(executable.toString(), "-tq", "--", file.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Compressed puzzle test was interrupted", exception);
        }
    }

    private static void feed(InputStream source, Process process, AtomicLong consumed) {
        try (source; OutputStream sink = process.getOutputStream()) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = source.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
                consumed.addAndGet(read);
            }
        } catch (IOException exception) {
            // The reader closed the stream or zstd stopped; its exit status reports validity.
        }
    }

    private static final class CheckedProcessInputStream extends FilterInputStream {
        private final Process process;
        private final Thread feeder;
        private boolean endReached;

        private CheckedProcessInputStream(Process process, Thread feeder) {
            super(process.getInputStream());
            this.process = process;
            this.feeder = feeder;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            endReached |= value == -1;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            endReached |= count == -1;
            return count;
        }

        @Override
        public void close() throws IOException {
            if (!endReached) {
                process.destroy();
            }
            super.close();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Compressed puzzle reader did not stop");
                }
                feeder.join(TimeUnit.SECONDS.toMillis(5));
                if (endReached && process.exitValue() != 0) {
                    throw new IOException("Compressed puzzle snapshot is invalid");
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Compressed puzzle import was interrupted", exception);
            }
        }
    }
}
