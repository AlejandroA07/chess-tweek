package com.chesstweek.training;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The manual Admin lifecycle of the complete official Lichess Puzzle corpus. Nothing here runs at
 * startup, in the background, or on a schedule: an update check sends one metadata request, and a
 * separate explicit start downloads, verifies, and imports the checked snapshot.
 */
public interface PuzzleCorpus {

    Status status();

    /** Sends one metadata request to the official source. It never downloads a snapshot. */
    UpdateCheckResult checkForUpdate();

    /** Resumes the unfinished import, or starts the checked available update. */
    StartResult startImport();

    /** Requests cancellation of the active import; its source file is kept for resume. */
    boolean cancelImport();

    record Status(
            boolean zstdAvailable,
            Storage storage,
            StoredSnapshot stored,
            UpdateCheck lastCheck,
            ImportProgress latestImport,
            List<Issue> issues) {}

    record Storage(long databaseBytes, long usableBytes, long requiredFreeBytes) {}

    record StoredSnapshot(
            long importId,
            LocalDate snapshotDate,
            String sourceUrl,
            String downloadChecksum,
            String importerVersion,
            long recordCount,
            Instant importedAt) {}

    record UpdateCheck(
            Instant checkedAt,
            LocalDate remoteSnapshotDate,
            long remoteBytes,
            boolean updateAvailable) {}

    record ImportProgress(
            long importId,
            Phase phase,
            String failureCode,
            LocalDate snapshotDate,
            long sourceBytes,
            long downloadedBytes,
            long consumedBytes,
            long processedRecords,
            long insertedPuzzles,
            long updatedPuzzles,
            long unchangedPuzzles,
            long rejectedRecords,
            long conflictingRecords,
            long removedPuzzles,
            long retainedPuzzles,
            boolean sourceDeleted,
            Instant startedAt,
            Instant updatedAt,
            Instant completedAt) {}

    enum Phase {
        DOWNLOADING,
        IMPORTING,
        FINALIZING,
        PAUSED,
        FAILED,
        VERIFIED
    }

    record Issue(long recordNumber, IssueKind kind, String externalId, String reason) {}

    enum IssueKind {
        REJECTED,
        CONFLICT
    }

    record UpdateCheckResult(UpdateCheckOutcome outcome, UpdateCheck check) {}

    enum UpdateCheckOutcome {
        CHECKED,
        UNAVAILABLE
    }

    enum StartResult {
        STARTED,
        RESUMED,
        ALREADY_RUNNING,
        NO_UPDATE_AVAILABLE,
        ZSTD_MISSING,
        INSUFFICIENT_DISK
    }
}
