package com.chesstweek.training.internal.library;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads newline-terminated records with explicit total-size and per-record bounds. */
public final class BoundedLineReader {

    private final InputStream input;
    private final long maximumTotalBytes;
    private final int maximumRecordBytes;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(512);
    private long total;

    public BoundedLineReader(InputStream input, long maximumTotalBytes, int maximumRecordBytes) {
        this.input = input;
        this.maximumTotalBytes = maximumTotalBytes;
        this.maximumRecordBytes = maximumRecordBytes;
    }

    /** The next record without its line terminator, or null at the end of input. */
    public String next() throws IOException {
        line.reset();
        int value;
        while ((value = input.read()) != -1) {
            total++;
            if (total > maximumTotalBytes) {
                throw new IllegalArgumentException("Puzzle snapshot expands beyond the allowed size");
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                if (line.size() >= maximumRecordBytes) {
                    throw new IllegalArgumentException("Puzzle snapshot record is too long");
                }
                line.write(value);
            }
        }
        if (value == -1 && line.size() == 0) {
            return null;
        }
        return line.toString(StandardCharsets.UTF_8);
    }
}
