package com.chesstweek.training.internal.corpus;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.chesstweek.training.PuzzleCorpus;
import com.chesstweek.training.internal.library.BoundedLineReader;
import com.chesstweek.training.internal.library.LichessPuzzleRecords;
import com.chesstweek.training.internal.library.process.ZstdInput;
import com.chesstweek.training.internal.library.process.ZstdLocator;
import com.chesstweek.training.internal.persistence.JdbcPuzzleCorpusStore;
import com.chesstweek.training.internal.persistence.JdbcPuzzleCorpusStore.ImportRow;

/**
 * Runs the manual full-corpus lifecycle on one worker thread: download with resume, integrity and
 * checksum verification, chunked idempotent import with a durable cursor, removal of unreferenced
 * Puzzles missing from the new snapshot, final verification, and deletion of the source file.
 * Startup only pauses an import that was interrupted; it never contacts Lichess.
 */
@Service
public final class PuzzleCorpusService implements PuzzleCorpus, DisposableBean {

    static final String IMPORTER_VERSION = "puzzle-corpus-v1";
    static final long GIB = 1024L * 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(PuzzleCorpusService.class);
    private static final int REMOVAL_BATCH = 2_000;
    private static final long MAX_RECORDS = 20_000_000;
    private static final long MAX_DECOMPRESSED_BYTES = 20 * GIB;
    private static final long PROGRESS_INTERVAL_MS = 1_000;
    private static final int ISSUE_LIMIT = 20;

    private final JdbcPuzzleCorpusStore store;
    private final LichessCorpusSource source;
    private final ZstdLocator zstdLocator;
    private final Path directory;
    private final long requiredFreeBytes;
    private final long minimumFreeBytes;
    private final long maximumRejectedRecords;
    private final int chunkRecords;
    private final long stallMillis;
    private final Clock clock;
    private final ExecutorService worker;
    private final AtomicReference<Checked> lastCheck = new AtomicReference<>();
    private volatile Active active;
    private volatile boolean shuttingDown;

    @Autowired
    PuzzleCorpusService(
            JdbcPuzzleCorpusStore store,
            LichessCorpusSource source,
            ZstdLocator zstdLocator,
            @Value("${chess-tweek.puzzle-corpus.directory:var/puzzle-corpus}") String directory,
            @Value("${chess-tweek.puzzle-corpus.required-free-bytes:6442450944}") long requiredFreeBytes,
            @Value("${chess-tweek.puzzle-corpus.minimum-free-bytes:1073741824}") long minimumFreeBytes,
            @Value("${chess-tweek.puzzle-corpus.maximum-rejected-records:1000}") long maximumRejectedRecords,
            @Value("${chess-tweek.puzzle-corpus.chunk-records:5000}") int chunkRecords,
            @Value("${chess-tweek.puzzle-corpus.download-stall-seconds:60}") int stallSeconds) {
        this(store, source, zstdLocator, Path.of(directory), requiredFreeBytes, minimumFreeBytes,
                maximumRejectedRecords, chunkRecords, stallSeconds, Clock.systemUTC());
    }

    PuzzleCorpusService(
            JdbcPuzzleCorpusStore store,
            LichessCorpusSource source,
            ZstdLocator zstdLocator,
            Path directory,
            long requiredFreeBytes,
            long minimumFreeBytes,
            long maximumRejectedRecords,
            int chunkRecords,
            int stallSeconds,
            Clock clock) {
        if (requiredFreeBytes < 0 || minimumFreeBytes < 0 || maximumRejectedRecords < 0
                || chunkRecords < 1 || chunkRecords > 50_000
                || stallSeconds < 1 || stallSeconds > 3_600) {
            throw new IllegalArgumentException("Puzzle corpus limits are invalid");
        }
        this.store = store;
        this.source = source;
        this.zstdLocator = zstdLocator;
        this.directory = directory.toAbsolutePath().normalize();
        this.requiredFreeBytes = requiredFreeBytes;
        this.minimumFreeBytes = minimumFreeBytes;
        this.maximumRejectedRecords = maximumRejectedRecords;
        this.chunkRecords = chunkRecords;
        this.stallMillis = TimeUnit.SECONDS.toMillis(stallSeconds);
        this.clock = clock;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "puzzle-corpus-import");
            thread.setDaemon(true);
            return thread;
        });
        int interrupted = store.markInterrupted(now());
        if (interrupted > 0) {
            LOGGER.info("Paused {} interrupted Puzzle corpus import", interrupted);
        }
        store.verifiedWithSource().forEach(row -> deleteSource(row.id()));
    }

    // Public lifecycle ----------------------------------------------------------------------

    @Override
    public Status status() {
        Optional<ImportRow> latest = store.latest();
        Optional<ImportRow> verified = store.latestVerified();
        Active running = active;
        return new Status(
                zstdLocator.locate().isPresent(),
                new Storage(store.databaseBytes(), usableBytes(), requiredFreeBytes),
                verified.map(PuzzleCorpusService::stored).orElse(null),
                checkView(lastCheck.get(), verified),
                latest.map(row -> progress(row, running)).orElse(null),
                latest.map(row -> store.issues(row.id(), ISSUE_LIMIT).stream()
                                .map(issue -> new Issue(
                                        issue.recordNumber(),
                                        "conflict".equals(issue.kind())
                                                ? IssueKind.CONFLICT : IssueKind.REJECTED,
                                        issue.externalId(), issue.reason()))
                                .toList())
                        .orElse(List.of()));
    }

    @Override
    public UpdateCheckResult checkForUpdate() {
        try {
            LichessCorpusSource.RemoteSnapshot remote = source.head();
            Checked checked = new Checked(clock.instant(), remote);
            lastCheck.set(checked);
            return new UpdateCheckResult(
                    UpdateCheckOutcome.CHECKED, checkView(checked, store.latestVerified()));
        } catch (LichessCorpusSource.SourceException exception) {
            LOGGER.info("Puzzle corpus update check failed: {}", exception.code());
            return new UpdateCheckResult(UpdateCheckOutcome.UNAVAILABLE, null);
        }
    }

    @Override
    public synchronized StartResult startImport() {
        if (active != null || shuttingDown) {
            return StartResult.ALREADY_RUNNING;
        }
        Optional<Path> zstd = zstdLocator.locate();
        if (zstd.isEmpty()) {
            return StartResult.ZSTD_MISSING;
        }
        Optional<ImportRow> unfinished = store.unfinished();
        if (unfinished.isPresent()) {
            ImportRow row = unfinished.get();
            long remaining = row.downloadChecksum() == null
                    ? row.sourceBytes() - existingBytes(partFile(row.id()))
                    : 0;
            if (!enoughSpace(remaining)) {
                return StartResult.INSUFFICIENT_DISK;
            }
            store.transition(row.id(), resumePhase(row), null, now());
            launch(row.id(), zstd.get());
            return StartResult.RESUMED;
        }
        Checked checked = lastCheck.get();
        if (checked == null || !updateAvailable(checked.remote(), store.latestVerified())) {
            return StartResult.NO_UPDATE_AVAILABLE;
        }
        if (!enoughSpace(checked.remote().bytes())) {
            return StartResult.INSUFFICIENT_DISK;
        }
        LichessCorpusSource.RemoteSnapshot remote = checked.remote();
        ImportRow row = store.create(new JdbcPuzzleCorpusStore.NewImport(
                remote.url(), remote.etag(), remote.lastModified().toEpochMilli(),
                remote.snapshotDate(), remote.bytes(), IMPORTER_VERSION, now()));
        launch(row.id(), zstd.get());
        return StartResult.STARTED;
    }

    @Override
    public boolean cancelImport() {
        Active running = active;
        if (running == null) {
            return false;
        }
        running.cancelled.set(true);
        InputStream body = running.body;
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // The worker observes cancellation on its next read.
            }
        }
        return true;
    }

    @Override
    public void destroy() throws InterruptedException {
        shuttingDown = true;
        cancelImport();
        worker.shutdown();
        if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
            worker.shutdownNow();
        }
    }

    // Worker --------------------------------------------------------------------------------

    private void launch(long importId, Path zstd) {
        Active running = new Active(importId);
        active = running;
        worker.execute(() -> run(running, zstd));
    }

    private void run(Active running, Path zstd) {
        long id = running.importId;
        try {
            ImportRow row = store.get(id).orElseThrow();
            if ("downloading".equals(row.state())) {
                download(running, row, zstd);
                row = store.get(id).orElseThrow();
            }
            if ("importing".equals(row.state())) {
                importRecords(running, row, zstd);
                store.transition(id, "finalizing", null, now());
                row = store.get(id).orElseThrow();
            }
            if ("finalizing".equals(row.state())) {
                finalizeImport(running, row);
            }
        } catch (Stop stop) {
            String state = stop.terminal ? "failed" : "paused";
            String code = !stop.terminal && shuttingDown ? "interrupted" : stop.code;
            LOGGER.info("Puzzle corpus import {} {}: {}", id, state, code);
            safely(() -> store.transition(id, state, code, now()));
            if (stop.terminal) {
                deleteSource(id);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Puzzle corpus import {} stopped unexpectedly: {}",
                    id, exception.getClass().getSimpleName());
            safely(() -> store.transition(id, "paused", "import_error", now()));
        } finally {
            active = null;
        }
    }

    private void download(Active running, ImportRow row, Path zstd) throws Stop {
        long started = System.nanoTime();
        Path part = partFile(row.id());
        Path complete = sourceFile(row.id());
        prepareDirectory();
        if (existingBytes(complete) != row.sourceBytes()) {
            long offset = existingBytes(part);
            if (offset > row.sourceBytes()) {
                deleteQuietly(part);
                offset = 0;
            }
            if (offset < row.sourceBytes()) {
                transfer(running, row, part, offset);
            }
            try {
                Files.move(part, complete, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new Stop("storage_unavailable", false);
            }
        }
        try {
            if (!ZstdInput.test(zstd, complete)) {
                throw new Stop("source_corrupt", true);
            }
        } catch (IOException exception) {
            throw new Stop("source_corrupt", true);
        }
        store.downloaded(row.id(), sha256(complete), now());
        LOGGER.info("Puzzle corpus import {} downloaded and verified {} bytes in {} ms",
                row.id(), row.sourceBytes(), elapsedMillis(started));
    }

    private void transfer(Active running, ImportRow row, Path part, long offset) throws Stop {
        LichessCorpusSource.RemoteSnapshot expected = new LichessCorpusSource.RemoteSnapshot(
                row.sourceUrl(), row.sourceEtag(), Instant.ofEpochMilli(row.sourceLastModifiedMs()),
                row.snapshotDate(), row.sourceBytes());
        LichessCorpusSource.Download download;
        try {
            download = source.open(expected, offset);
        } catch (LichessCorpusSource.SourceException exception) {
            throw new Stop(exception.code(), "source_changed".equals(exception.code()));
        }
        running.body = download.body();
        running.lastRead = System.nanoTime();
        Thread watchdog = Thread.ofVirtual().name("puzzle-corpus-stall-watchdog")
                .start(() -> abortWhenStalled(running));
        long written = download.offset();
        running.downloaded.set(written);
        StandardOpenOption[] options = written == 0
                ? new StandardOpenOption[] {
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE}
                : new StandardOpenOption[] {StandardOpenOption.APPEND, StandardOpenOption.WRITE};
        long saved = System.currentTimeMillis();
        try (InputStream body = download.body();
                OutputStream output = Files.newOutputStream(part, options)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = body.read(buffer)) != -1) {
                running.lastRead = System.nanoTime();
                if (running.cancelled.get()) {
                    throw new Stop("cancelled", false);
                }
                if (written + read > row.sourceBytes()) {
                    throw new Stop("source_changed", true);
                }
                output.write(buffer, 0, read);
                written += read;
                running.downloaded.set(written);
                if (System.currentTimeMillis() - saved >= PROGRESS_INTERVAL_MS) {
                    store.recordDownload(row.id(), written, now());
                    requireMinimumSpace();
                    saved = System.currentTimeMillis();
                }
            }
        } catch (IOException exception) {
            store.recordDownload(row.id(), Math.min(written, row.sourceBytes()), now());
            throw new Stop(running.cancelled.get() ? "cancelled" : "download_failed", false);
        } finally {
            running.body = null;
            watchdog.interrupt();
        }
        store.recordDownload(row.id(), written, now());
        if (running.cancelled.get()) {
            throw new Stop("cancelled", false);
        }
        if (written != row.sourceBytes()) {
            throw new Stop("download_incomplete", false);
        }
    }

    /** Closes a response body that delivers no bytes for the stall period; the read then fails. */
    private void abortWhenStalled(Active running) {
        try {
            while (running.body != null) {
                Thread.sleep(Math.min(1_000, stallMillis));
                InputStream body = running.body;
                if (body != null && TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - running.lastRead) >= stallMillis) {
                    LOGGER.info("Puzzle corpus download stalled; closing the connection");
                    body.close();
                    return;
                }
            }
        } catch (InterruptedException | IOException ignored) {
            // The download finished or failed on its own.
        }
    }

    private void importRecords(Active running, ImportRow row, Path zstd) throws Stop {
        long started = System.nanoTime();
        Path complete = sourceFile(row.id());
        if (existingBytes(complete) != row.sourceBytes()) {
            throw new Stop("source_missing", true);
        }
        if (!sha256(complete).equals(row.downloadChecksum())) {
            throw new Stop("checksum_mismatch", true);
        }
        if (!enoughSpace(0)) {
            throw new Stop("insufficient_disk", false);
        }
        long processed = row.processedRecords();
        List<String> pending = new ArrayList<>(chunkRecords);
        long pendingStart = processed;
        try (InputStream input = new BufferedInputStream(
                ZstdInput.open(zstd, complete, running.consumed), 1 << 20)) {
            BoundedLineReader lines = new BoundedLineReader(
                    input, MAX_DECOMPRESSED_BYTES, LichessPuzzleRecords.MAX_RECORD_BYTES);
            if (!LichessPuzzleRecords.HEADER.equals(lines.next())) {
                throw new Stop("invalid_header", true);
            }
            for (long skipped = 0; skipped < processed; skipped++) {
                if (lines.next() == null) {
                    throw new Stop("source_changed", true);
                }
            }
            String line;
            while ((line = lines.next()) != null) {
                if (pendingStart + pending.size() >= MAX_RECORDS) {
                    throw new Stop("snapshot_too_large", true);
                }
                pending.add(line);
                if (pending.size() == chunkRecords) {
                    if (running.cancelled.get()) {
                        throw new Stop("cancelled", false);
                    }
                    requireMinimumSpace();
                    apply(row.id(), pendingStart, pending, false);
                    pendingStart += pending.size();
                    pending.clear();
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            if (running.cancelled.get()) {
                throw new Stop("cancelled", false);
            }
            throw new Stop("source_corrupt", true);
        }
        apply(row.id(), pendingStart, pending, true);
        LOGGER.info("Puzzle corpus import {} applied {} records in {} ms",
                row.id(), pendingStart + pending.size(), elapsedMillis(started));
    }

    private void apply(long importId, long processed, List<String> lines, boolean complete)
            throws Stop {
        List<JdbcPuzzleCorpusStore.Entry> entries = IntStream.range(0, lines.size())
                .parallel()
                .mapToObj(index -> entry(processed + index + 1, lines.get(index)))
                .toList();
        store.applyChunk(importId, processed, entries, complete, now());
        ImportRow updated = store.get(importId).orElseThrow();
        if (updated.rejectedRecords() > maximumRejectedRecords) {
            throw new Stop("too_many_rejected", true);
        }
    }

    private static JdbcPuzzleCorpusStore.Entry entry(long recordNumber, String line) {
        try {
            return new JdbcPuzzleCorpusStore.Accepted(recordNumber,
                    LichessPuzzleRecords.validate(LichessPuzzleRecords.parse(line)));
        } catch (LichessPuzzleRecords.RecordRejected rejected) {
            return new JdbcPuzzleCorpusStore.Rejected(
                    recordNumber, LichessPuzzleRecords.externalIdOrNull(line), rejected.reason());
        }
    }

    private void finalizeImport(Active running, ImportRow row) throws Stop {
        long started = System.nanoTime();
        long afterImport = 0;
        long afterPuzzle = 0;
        JdbcPuzzleCorpusStore.RemovalBatch batch;
        do {
            if (running.cancelled.get()) {
                throw new Stop("cancelled", false);
            }
            batch = store.removeMissing(row.id(), afterImport, afterPuzzle, REMOVAL_BATCH);
            afterImport = batch.lastImportId();
            afterPuzzle = batch.lastPuzzleId();
        } while (!batch.done());
        ImportRow verified;
        try {
            verified = store.verify(row.id(), now());
        } catch (IllegalStateException exception) {
            throw new Stop("verification_failed", true);
        }
        deleteSource(row.id());
        LOGGER.info(
                "Puzzle corpus import {} verified {} Puzzles ({} inserted, {} updated, {} unchanged, "
                        + "{} rejected, {} conflicts, {} removed, {} retained) in {} ms; "
                        + "database {} bytes",
                row.id(), verified.recordCount(), verified.insertedPuzzles(),
                verified.updatedPuzzles(), verified.unchangedPuzzles(), verified.rejectedRecords(),
                verified.conflictingRecords(), verified.removedPuzzles(), verified.retainedPuzzles(),
                elapsedMillis(started), store.databaseBytes());
    }

    // Views ---------------------------------------------------------------------------------

    private static StoredSnapshot stored(ImportRow row) {
        return new StoredSnapshot(
                row.id(), row.snapshotDate(), row.sourceUrl(), row.downloadChecksum(),
                row.importerVersion(), row.recordCount(), Instant.ofEpochMilli(row.completedAtMs()));
    }

    private static UpdateCheck checkView(Checked checked, Optional<ImportRow> verified) {
        if (checked == null) {
            return null;
        }
        return new UpdateCheck(
                checked.checkedAt(), checked.remote().snapshotDate(), checked.remote().bytes(),
                updateAvailable(checked.remote(), verified));
    }

    private static boolean updateAvailable(
            LichessCorpusSource.RemoteSnapshot remote, Optional<ImportRow> verified) {
        return verified.map(row -> !row.sourceEtag().equals(remote.etag())
                        || row.sourceBytes() != remote.bytes()
                        || row.sourceLastModifiedMs() != remote.lastModified().toEpochMilli())
                .orElse(true);
    }

    private static ImportProgress progress(ImportRow row, Active running) {
        boolean live = running != null && running.importId == row.id();
        long downloaded = live && "downloading".equals(row.state())
                ? Math.max(row.downloadedBytes(), running.downloaded.get())
                : row.downloadedBytes();
        long consumed = live && "importing".equals(row.state()) ? running.consumed.get() : 0;
        return new ImportProgress(
                row.id(),
                switch (row.state()) {
                    case "downloading" -> Phase.DOWNLOADING;
                    case "importing" -> Phase.IMPORTING;
                    case "finalizing" -> Phase.FINALIZING;
                    case "paused" -> Phase.PAUSED;
                    case "failed" -> Phase.FAILED;
                    default -> Phase.VERIFIED;
                },
                row.failureCode(), row.snapshotDate(), row.sourceBytes(),
                Math.min(downloaded, row.sourceBytes()), Math.min(consumed, row.sourceBytes()),
                row.processedRecords(), row.insertedPuzzles(), row.updatedPuzzles(),
                row.unchangedPuzzles(), row.rejectedRecords(), row.conflictingRecords(),
                row.removedPuzzles(), row.retainedPuzzles(), row.sourceDeleted(),
                Instant.ofEpochMilli(row.startedAtMs()), Instant.ofEpochMilli(row.updatedAtMs()),
                row.completedAtMs() == null ? null : Instant.ofEpochMilli(row.completedAtMs()));
    }

    private static String resumePhase(ImportRow row) {
        if (row.downloadChecksum() == null) {
            return "downloading";
        }
        return row.recordsComplete() ? "finalizing" : "importing";
    }

    // Local files ---------------------------------------------------------------------------

    Path sourceFile(long importId) {
        return directory.resolve("lichess_db_puzzle-" + importId + ".csv.zst");
    }

    Path partFile(long importId) {
        return directory.resolve("lichess_db_puzzle-" + importId + ".csv.zst.part");
    }

    private void prepareDirectory() throws Stop {
        try {
            if (Files.isSymbolicLink(directory)) {
                throw new Stop("storage_unavailable", false);
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(directory);
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException exception) {
            throw new Stop("storage_unavailable", false);
        }
    }

    private void deleteSource(long importId) {
        deleteQuietly(partFile(importId));
        deleteQuietly(sourceFile(importId));
        if (existingBytes(partFile(importId)) == 0 && existingBytes(sourceFile(importId)) == 0) {
            safely(() -> store.markSourceDeleted(importId));
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (!Files.isSymbolicLink(file)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException exception) {
            LOGGER.warn("Puzzle corpus source file could not be deleted");
        }
    }

    private static long existingBytes(Path file) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0;
        } catch (IOException exception) {
            return 0;
        }
    }

    private boolean enoughSpace(long remainingDownload) {
        return usableBytes() >= remainingDownload + requiredFreeBytes;
    }

    private void requireMinimumSpace() throws Stop {
        if (usableBytes() < minimumFreeBytes) {
            throw new Stop("insufficient_disk", false);
        }
    }

    private long usableBytes() {
        Path existing = directory;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        try {
            FileStore fileStore = Files.getFileStore(existing == null ? Path.of(".") : existing);
            return fileStore.getUsableSpace();
        } catch (IOException exception) {
            return 0;
        }
    }

    private static String sha256(Path file) throws Stop {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new Stop("storage_unavailable", false);
        }
    }

    private long now() {
        return clock.millis();
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Puzzle corpus import state could not be recorded: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private record Checked(Instant checkedAt, LichessCorpusSource.RemoteSnapshot remote) {}

    private static final class Active {
        private final long importId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicLong consumed = new AtomicLong();
        private final AtomicLong downloaded = new AtomicLong();
        private volatile InputStream body;
        private volatile long lastRead;

        private Active(long importId) {
            this.importId = importId;
        }
    }

    /** Stops the worker. A terminal stop fails the import; otherwise it pauses for resume. */
    private static final class Stop extends Exception {
        private final String code;
        private final boolean terminal;

        private Stop(String code, boolean terminal) {
            super(code, null, false, false);
            this.code = code;
            this.terminal = terminal;
        }
    }
}
