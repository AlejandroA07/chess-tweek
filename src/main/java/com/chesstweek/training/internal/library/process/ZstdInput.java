package com.chesstweek.training.internal.library.process;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** The single approved process boundary for reading a local zstd snapshot. */
public final class ZstdInput {

    private ZstdInput() {}

    public static InputStream open(Path file) throws IOException {
        Process process = new ProcessBuilder("zstd", "-dc", "--", file.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return new CheckedProcessInputStream(process);
    }

    private static final class CheckedProcessInputStream extends FilterInputStream {
        private final Process process;
        private boolean endReached;

        private CheckedProcessInputStream(Process process) {
            super(process.getInputStream());
            this.process = process;
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
            super.close();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Compressed puzzle reader did not stop");
                }
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
