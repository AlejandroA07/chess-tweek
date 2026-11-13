package com.chesstweek.gamereview.internal.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Starts the binary directly, without a shell or arguments, discarding its error stream. */
final class NativeEngineProcessLauncher implements EngineProcessLauncher {

    @Override
    public EngineProcess launch(Path executable) throws IOException {
        Process process = new ProcessBuilder(List.of(executable.toString()))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return new NativeProcess(process);
    }

    private record NativeProcess(Process process) implements EngineProcess {

        @Override
        public OutputStream input() {
            return process.getOutputStream();
        }

        @Override
        public InputStream output() {
            return process.getInputStream();
        }

        @Override
        public void stop(Duration timeout) {
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
