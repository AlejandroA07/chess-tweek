package com.chesstweek.composition.web;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;
import com.chesstweek.training.PuzzleCorpus;

/**
 * Admin operations for the full Lichess Puzzle corpus. Every operation requires the connected
 * owner session; state-changing operations also require a same-origin loopback request.
 */
@RestController
@RequestMapping("/api/admin/puzzle-corpus")
public final class PuzzleCorpusController {

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final PuzzleCorpus corpus;

    PuzzleCorpusController(LichessSessionAuthorizer sessionAuthorizer, PuzzleCorpus corpus) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.corpus = corpus;
    }

    @GetMapping
    public ResponseEntity<?> status(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        ResponseEntity<CorpusError> denied = deny(sessionAuthorizer.authorize(sealedSession));
        if (denied != null) {
            return denied;
        }
        return current();
    }

    /** Sends one metadata request to Lichess. It never downloads or imports. */
    @PostMapping("/update-checks")
    public ResponseEntity<?> checkForUpdate(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        ResponseEntity<?> rejected = authorizeChange(request, sealedSession);
        if (rejected != null) {
            return rejected;
        }
        PuzzleCorpus.UpdateCheckResult result = corpus.checkForUpdate();
        if (result.outcome() == PuzzleCorpus.UpdateCheckOutcome.UNAVAILABLE) {
            return error(HttpStatus.BAD_GATEWAY, "update_check_failed");
        }
        return current();
    }

    @PostMapping("/imports")
    public ResponseEntity<?> startImport(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        ResponseEntity<?> rejected = authorizeChange(request, sealedSession);
        if (rejected != null) {
            return rejected;
        }
        PuzzleCorpus.StartResult result;
        try {
            result = corpus.startImport();
        } catch (DataAccessException | IllegalStateException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "puzzle_corpus_unavailable");
        }
        return switch (result) {
            case STARTED, RESUMED -> current();
            case ALREADY_RUNNING -> error(HttpStatus.CONFLICT, "import_running");
            case NO_UPDATE_AVAILABLE -> error(HttpStatus.CONFLICT, "no_update_available");
            case ZSTD_MISSING -> error(HttpStatus.CONFLICT, "zstd_missing");
            case INSUFFICIENT_DISK -> error(HttpStatus.CONFLICT, "insufficient_disk");
        };
    }

    @PostMapping("/imports/cancel")
    public ResponseEntity<?> cancelImport(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        ResponseEntity<?> rejected = authorizeChange(request, sealedSession);
        if (rejected != null) {
            return rejected;
        }
        if (!corpus.cancelImport()) {
            return error(HttpStatus.CONFLICT, "no_active_import");
        }
        return current();
    }

    private ResponseEntity<?> authorizeChange(HttpServletRequest request, String sealedSession) {
        if (!LoopbackRequestPolicy.isSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return deny(sessionAuthorizer.authorize(sealedSession));
    }

    private ResponseEntity<?> current() {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(response(corpus.status()));
        } catch (DataAccessException exception) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "puzzle_corpus_unavailable");
        }
    }

    private static CorpusResponse response(PuzzleCorpus.Status status) {
        PuzzleCorpus.StoredSnapshot stored = status.stored();
        PuzzleCorpus.UpdateCheck check = status.lastCheck();
        PuzzleCorpus.ImportProgress progress = status.latestImport();
        return new CorpusResponse(
                status.zstdAvailable(),
                new StorageResponse(
                        status.storage().databaseBytes(),
                        status.storage().usableBytes(),
                        status.storage().requiredFreeBytes()),
                stored == null ? null : new StoredResponse(
                        stored.snapshotDate().toString(),
                        stored.sourceUrl(),
                        stored.downloadChecksum(),
                        stored.importerVersion(),
                        stored.recordCount(),
                        stored.importedAt().toString()),
                check == null ? null : new CheckResponse(
                        check.checkedAt().toString(),
                        check.remoteSnapshotDate().toString(),
                        check.remoteBytes(),
                        check.updateAvailable()),
                progress == null ? null : new ImportResponse(
                        progress.importId(),
                        lower(progress.phase()),
                        progress.failureCode(),
                        progress.snapshotDate().toString(),
                        progress.sourceBytes(),
                        progress.downloadedBytes(),
                        progress.consumedBytes(),
                        progress.processedRecords(),
                        progress.insertedPuzzles(),
                        progress.updatedPuzzles(),
                        progress.unchangedPuzzles(),
                        progress.rejectedRecords(),
                        progress.conflictingRecords(),
                        progress.removedPuzzles(),
                        progress.retainedPuzzles(),
                        progress.sourceDeleted(),
                        progress.startedAt().toString(),
                        progress.updatedAt().toString(),
                        progress.completedAt() == null ? null : progress.completedAt().toString()),
                status.issues().stream()
                        .map(issue -> new IssueResponse(
                                issue.recordNumber(),
                                lower(issue.kind()),
                                issue.externalId(),
                                issue.reason()))
                        .toList());
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static ResponseEntity<CorpusError> deny(LichessSessionAuthorization authorization) {
        return switch (authorization.type()) {
            case CONNECTED -> null;
            case DISCONNECTED -> {
                var response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .cacheControl(CacheControl.noStore());
                if (authorization.clearCookie()) {
                    response.header(
                            HttpHeaders.SET_COOKIE, LichessSessionCookies.cleared().toString());
                }
                yield response.body(new CorpusError("not_connected"));
            }
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE, "session_unavailable");
        };
    }

    private static ResponseEntity<CorpusError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new CorpusError(code));
    }

    public record CorpusResponse(
            boolean zstdAvailable,
            StorageResponse storage,
            StoredResponse stored,
            CheckResponse lastCheck,
            ImportResponse latestImport,
            List<IssueResponse> issues) {}

    public record StorageResponse(long databaseBytes, long usableBytes, long requiredFreeBytes) {}

    public record StoredResponse(
            String snapshotDate,
            String sourceUrl,
            String downloadChecksum,
            String importerVersion,
            long recordCount,
            String importedAt) {}

    public record CheckResponse(
            String checkedAt, String remoteSnapshotDate, long remoteBytes, boolean updateAvailable) {}

    public record ImportResponse(
            long id,
            String phase,
            String failureCode,
            String snapshotDate,
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
            String startedAt,
            String updatedAt,
            String completedAt) {}

    public record IssueResponse(long recordNumber, String kind, String externalId, String reason) {}

    public record CorpusError(String error) {}
}
