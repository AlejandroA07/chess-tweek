package com.chesstweek.composition.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.training.PuzzleCorpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PuzzleCorpusControllerTest {

    private static final jakarta.servlet.http.Cookie SESSION =
            new jakarta.servlet.http.Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final String ORIGIN = "http://127.0.0.1:8080";

    private final FakeCorpus corpus = new FakeCorpus();

    @Test
    void reportsStoredSnapshotProgressAndIssuesForTheConnectedOwner() throws Exception {
        mockMvc(connected())
                .perform(get("/api/admin/puzzle-corpus").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.zstdAvailable").value(true))
                .andExpect(jsonPath("$.stored.snapshotDate").value("2026-11-01"))
                .andExpect(jsonPath("$.stored.recordCount").value(6_193_530))
                .andExpect(jsonPath("$.lastCheck.updateAvailable").value(false))
                .andExpect(jsonPath("$.latestImport.phase").value("importing"))
                .andExpect(jsonPath("$.latestImport.processedRecords").value(25_000))
                .andExpect(jsonPath("$.issues[0].kind").value("conflict"))
                .andExpect(jsonPath("$.issues[0].reason").value("immutable_conflict"));
        assertThat(corpus.checks).hasValue(0);
        assertThat(corpus.starts).hasValue(0);
    }

    @Test
    void checksForAnUpdateWithoutStartingADownload() throws Exception {
        mockMvc(connected())
                .perform(post("/api/admin/puzzle-corpus/update-checks")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk());
        assertThat(corpus.checks).hasValue(1);
        assertThat(corpus.starts).hasValue(0);

        corpus.checkFails = true;
        mockMvc(connected())
                .perform(post("/api/admin/puzzle-corpus/update-checks")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("puzzle_corpus_unavailable"));
        corpus.checkFails = false;

        corpus.checkOutcome = PuzzleCorpus.UpdateCheckOutcome.UNAVAILABLE;
        mockMvc(connected())
                .perform(post("/api/admin/puzzle-corpus/update-checks")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("update_check_failed"));
    }

    @Test
    void mapsEveryRefusedStartToASafeCode() throws Exception {
        MockMvc mockMvc = mockMvc(connected());
        for (var expected : List.of(
                new Object[] {PuzzleCorpus.StartResult.ALREADY_RUNNING, "import_running"},
                new Object[] {PuzzleCorpus.StartResult.NO_UPDATE_AVAILABLE, "no_update_available"},
                new Object[] {PuzzleCorpus.StartResult.ZSTD_MISSING, "zstd_missing"},
                new Object[] {PuzzleCorpus.StartResult.INSUFFICIENT_DISK, "insufficient_disk"})) {
            corpus.startResult = (PuzzleCorpus.StartResult) expected[0];
            mockMvc.perform(post("/api/admin/puzzle-corpus/imports")
                            .cookie(SESSION).header("Origin", ORIGIN))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(expected[1]));
        }
        corpus.startResult = PuzzleCorpus.StartResult.RESUMED;
        mockMvc.perform(post("/api/admin/puzzle-corpus/imports")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestImport.phase").value("importing"));

        corpus.cancelled = false;
        mockMvc.perform(post("/api/admin/puzzle-corpus/imports/cancel")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_active_import"));
        corpus.cancelled = true;
        mockMvc.perform(post("/api/admin/puzzle-corpus/imports/cancel")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isOk());
    }

    @Test
    void refusesCrossOriginChangesBeforeAuthorizingOrActing() throws Exception {
        var authorizations = new AtomicInteger();
        MockMvc mockMvc = mockMvc(sealed -> {
            authorizations.incrementAndGet();
            return LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
        });
        for (String path : List.of("/update-checks", "/imports", "/imports/cancel")) {
            mockMvc.perform(post("/api/admin/puzzle-corpus" + path)
                            .cookie(SESSION).header("Origin", "https://example.com"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/puzzle-corpus" + path).cookie(SESSION))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/puzzle-corpus" + path)
                            .cookie(SESSION).header("Origin", ORIGIN)
                            .header("Sec-Fetch-Site", "cross-site"))
                    .andExpect(status().isForbidden());
        }
        assertThat(authorizations).hasValue(0);
        assertThat(corpus.checks).hasValue(0);
        assertThat(corpus.starts).hasValue(0);
        assertThat(corpus.cancels).hasValue(0);
    }

    @Test
    void deniesDisconnectedAndUnavailableSessionsBeforeActing() throws Exception {
        MockMvc disconnected = mockMvc(sealed -> LichessSessionAuthorization.disconnected(true));
        disconnected.perform(get("/api/admin/puzzle-corpus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("not_connected"));
        disconnected.perform(post("/api/admin/puzzle-corpus/imports")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mockMvc(sealed -> LichessSessionAuthorization.unavailable())
                .perform(post("/api/admin/puzzle-corpus/update-checks")
                        .cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("session_unavailable"));

        assertThat(corpus.statusReads).hasValue(0);
        assertThat(corpus.checks).hasValue(0);
        assertThat(corpus.starts).hasValue(0);
    }

    private MockMvc mockMvc(LichessSessionAuthorizer authorizer) {
        return MockMvcBuilders.standaloneSetup(new PuzzleCorpusController(authorizer, corpus)).build();
    }

    private static LichessSessionAuthorizer connected() {
        return sealed -> LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");
    }

    private static final class FakeCorpus implements PuzzleCorpus {
        private final AtomicInteger statusReads = new AtomicInteger();
        private final AtomicInteger checks = new AtomicInteger();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private UpdateCheckOutcome checkOutcome = UpdateCheckOutcome.CHECKED;
        private StartResult startResult = StartResult.STARTED;
        private boolean cancelled = true;
        private boolean checkFails;

        @Override
        public Status status() {
            statusReads.incrementAndGet();
            Instant at = Instant.parse("2026-11-19T10:00:00Z");
            return new Status(
                    true,
                    new Storage(3_000_000_000L, 60_000_000_000L, 6_442_450_944L),
                    new StoredSnapshot(1, LocalDate.parse("2026-11-01"),
                            "https://database.lichess.org/lichess_db_puzzle.csv.zst",
                            "a".repeat(64), "puzzle-corpus-v1", 6_193_530, at),
                    new UpdateCheck(at, LocalDate.parse("2026-11-01"), 309_751_173, false),
                    new ImportProgress(2, Phase.IMPORTING, null, LocalDate.parse("2026-12-01"),
                            309_751_173, 309_751_173, 1_000_000, 25_000, 10, 20, 24_960, 9, 1, 0, 0,
                            false, at, at, null),
                    List.of(new Issue(12, IssueKind.CONFLICT, "xrHJJ", "immutable_conflict")));
        }

        @Override
        public UpdateCheckResult checkForUpdate() {
            checks.incrementAndGet();
            if (checkFails) {
                throw new org.springframework.dao.DataAccessResourceFailureException("unavailable");
            }
            return new UpdateCheckResult(checkOutcome, null);
        }

        @Override
        public StartResult startImport() {
            starts.incrementAndGet();
            return startResult;
        }

        @Override
        public boolean cancelImport() {
            cancels.incrementAndGet();
            return cancelled;
        }
    }
}
