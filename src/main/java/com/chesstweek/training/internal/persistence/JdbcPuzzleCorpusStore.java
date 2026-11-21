package com.chesstweek.training.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.training.internal.library.LichessPuzzleRecords;
import com.chesstweek.training.internal.library.PuzzleImportBatch;

/**
 * Persistence for the full Lichess Puzzle corpus. Each source record is applied idempotently by
 * provider and external ID: new Puzzles are inserted once, existing Puzzles receive the new
 * observation, and immutable position or solution conflicts are rejected and reported.
 */
@Repository
public class JdbcPuzzleCorpusStore {

    public static final int MAX_STORED_ISSUES = 200;
    private static final int LOOKUP_BATCH = 500;
    private static final String PROVIDER = LichessPuzzleRecords.PROVIDER;

    private final JdbcTemplate jdbc;
    private final PuzzleCategoryIndex categories;

    public JdbcPuzzleCorpusStore(JdbcTemplate jdbc, PuzzleCategoryIndex categories) {
        this.jdbc = jdbc;
        this.categories = categories;
    }

    public record ImportRow(
            long id,
            String sourceUrl,
            String sourceEtag,
            long sourceLastModifiedMs,
            LocalDate snapshotDate,
            long sourceBytes,
            long downloadedBytes,
            String downloadChecksum,
            String importerVersion,
            String state,
            String failureCode,
            long processedRecords,
            boolean recordsComplete,
            long insertedPuzzles,
            long updatedPuzzles,
            long unchangedPuzzles,
            long rejectedRecords,
            long conflictingRecords,
            long removedPuzzles,
            long retainedPuzzles,
            Long recordCount,
            boolean sourceDeleted,
            long startedAtMs,
            long updatedAtMs,
            Long completedAtMs) {}

    public record NewImport(
            String sourceUrl,
            String sourceEtag,
            long sourceLastModifiedMs,
            LocalDate snapshotDate,
            long sourceBytes,
            String importerVersion,
            long nowMs) {}

    public sealed interface Entry permits Accepted, Rejected {}

    public record Accepted(long recordNumber, PuzzleImportBatch.PuzzleRow row) implements Entry {}

    public record Rejected(long recordNumber, String externalId, String reason) implements Entry {}

    public record IssueRow(long recordNumber, String kind, String externalId, String reason) {}

    public record RemovalBatch(int removed, int retained, long lastImportId, long lastPuzzleId, boolean done) {}

    // Lifecycle -----------------------------------------------------------------------------

    public ImportRow create(NewImport value) {
        jdbc.update("""
                INSERT INTO training_puzzle_corpus_import (
                    provider, source_url, source_etag, source_last_modified_ms, snapshot_date,
                    source_bytes, importer_version, state, started_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'downloading', ?, ?)
                """, PROVIDER, value.sourceUrl(), value.sourceEtag(), value.sourceLastModifiedMs(),
                value.snapshotDate().toString(), value.sourceBytes(), value.importerVersion(),
                value.nowMs(), value.nowMs());
        return unfinished().orElseThrow();
    }

    public Optional<ImportRow> get(long id) {
        return jdbc.query("SELECT * FROM training_puzzle_corpus_import WHERE id = ?",
                JdbcPuzzleCorpusStore::mapImport, id).stream().findFirst();
    }

    public Optional<ImportRow> unfinished() {
        return jdbc.query("""
                SELECT * FROM training_puzzle_corpus_import
                WHERE state IN ('downloading', 'importing', 'finalizing', 'paused')
                ORDER BY id DESC LIMIT 1
                """, JdbcPuzzleCorpusStore::mapImport).stream().findFirst();
    }

    public Optional<ImportRow> latestVerified() {
        return jdbc.query("""
                SELECT * FROM training_puzzle_corpus_import
                WHERE state = 'verified' ORDER BY id DESC LIMIT 1
                """, JdbcPuzzleCorpusStore::mapImport).stream().findFirst();
    }

    public Optional<ImportRow> latest() {
        return jdbc.query("""
                SELECT * FROM training_puzzle_corpus_import ORDER BY id DESC LIMIT 1
                """, JdbcPuzzleCorpusStore::mapImport).stream().findFirst();
    }

    public List<ImportRow> verifiedWithSource() {
        return jdbc.query("""
                SELECT * FROM training_puzzle_corpus_import
                WHERE state IN ('verified', 'failed') AND source_deleted = 0
                """, JdbcPuzzleCorpusStore::mapImport);
    }

    public void recordDownload(long id, long downloadedBytes, long nowMs) {
        jdbc.update("""
                UPDATE training_puzzle_corpus_import
                SET downloaded_bytes = ?, updated_at_ms = ?
                WHERE id = ? AND state = 'downloading'
                """, downloadedBytes, nowMs, id);
    }

    public void downloaded(long id, String checksum, long nowMs) {
        require(jdbc.update("""
                UPDATE training_puzzle_corpus_import
                SET downloaded_bytes = source_bytes, download_checksum = ?, state = 'importing',
                    failure_code = NULL, updated_at_ms = ?
                WHERE id = ? AND state = 'downloading'
                """, checksum, nowMs, id));
    }

    /** Moves an import into an active phase, or into paused or failed with a failure code. */
    public void transition(long id, String state, String failureCode, long nowMs) {
        require(jdbc.update("""
                UPDATE training_puzzle_corpus_import
                SET state = ?, failure_code = ?, updated_at_ms = ?
                WHERE id = ? AND state <> 'verified'
                """, state, failureCode, nowMs, id));
    }

    /** Startup recovery: an import that was active when the application stopped is paused. */
    public int markInterrupted(long nowMs) {
        return jdbc.update("""
                UPDATE training_puzzle_corpus_import
                SET state = 'paused', failure_code = 'interrupted', updated_at_ms = ?
                WHERE state IN ('downloading', 'importing', 'finalizing')
                """, nowMs);
    }

    public void markSourceDeleted(long id) {
        jdbc.update("UPDATE training_puzzle_corpus_import SET source_deleted = 1 WHERE id = ?", id);
    }

    public List<IssueRow> issues(long importId, int limit) {
        return jdbc.query("""
                SELECT record_number, kind, external_id, reason
                FROM training_puzzle_corpus_issue
                WHERE import_id = ? ORDER BY record_number LIMIT ?
                """, (row, number) -> new IssueRow(
                        row.getLong(1), row.getString(2), row.getString(3), row.getString(4)),
                importId, limit);
    }

    public long databaseBytes() {
        long pages = jdbc.queryForObject("PRAGMA page_count", Long.class);
        long pageSize = jdbc.queryForObject("PRAGMA page_size", Long.class);
        return pages * pageSize;
    }

    // Records -------------------------------------------------------------------------------

    /**
     * Applies one chunk of consecutive source records and advances the resume cursor in the same
     * transaction, so an interruption either keeps the whole chunk or none of it.
     */
    @Transactional
    public void applyChunk(
            long importId,
            long expectedProcessed,
            List<Entry> entries,
            boolean complete,
            long nowMs) {
        try {
            apply(importId, expectedProcessed, entries, complete, nowMs);
        } catch (RuntimeException exception) {
            categories.reload();
            throw exception;
        }
    }

    private void apply(
            long importId, long expectedProcessed, List<Entry> entries, boolean complete, long nowMs) {
        List<Accepted> accepted = new ArrayList<>();
        List<IssueRow> issues = new ArrayList<>();
        for (Entry entry : entries) {
            switch (entry) {
                case Accepted value -> accepted.add(value);
                case Rejected value -> issues.add(new IssueRow(
                        value.recordNumber(), "rejected", value.externalId(), value.reason()));
            }
        }
        long rejected = issues.size();
        long conflicts = 0;
        Map<String, Existing> existing = existing(accepted.stream()
                .map(value -> value.row().externalId()).toList());
        Set<String> seen = new HashSet<>();
        List<Accepted> inserts = new ArrayList<>();
        List<Accepted> missingCurrent = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<Long> unchanged = new ArrayList<>();
        List<Long> confirmed = new ArrayList<>();
        for (Accepted value : accepted) {
            PuzzleImportBatch.PuzzleRow row = value.row();
            Existing stored = existing.get(row.externalId());
            if (!seen.add(row.externalId())
                    || (stored != null && Objects.equals(stored.importId(), importId))) {
                issues.add(new IssueRow(
                        value.recordNumber(), "rejected", row.externalId(), "duplicate_id"));
                rejected++;
            } else if (stored == null) {
                inserts.add(value);
            } else if (!stored.sourceFen().equals(row.sourceFen())
                    || !stored.sourceMoves().equals(join(row.sourceMoves()))) {
                issues.add(new IssueRow(
                        value.recordNumber(), "conflict", row.externalId(), "immutable_conflict"));
                conflicts++;
                confirmed.add(stored.id());
            } else if (!stored.hasCurrent()) {
                missingCurrent.add(value);
            } else if (stored.sameObservation(row)) {
                unchanged.add(stored.id());
            } else {
                changes.add(new Change(stored, row));
            }
        }

        insertPuzzles(inserts, nowMs);
        Map<String, Existing> created = existing(inserts.stream()
                .map(value -> value.row().externalId()).toList());
        List<Member> members = new ArrayList<>();
        List<CurrentRow> currents = new ArrayList<>();
        for (Accepted value : inserts) {
            currents.add(new CurrentRow(created.get(value.row().externalId()).id(), value.row()));
        }
        for (Accepted value : missingCurrent) {
            currents.add(new CurrentRow(existing.get(value.row().externalId()).id(), value.row()));
        }
        insertCurrent(importId, currents);
        for (CurrentRow current : currents) {
            for (Integer category : categories.categories(
                    current.row().themes(), current.row().openingTags(), true)) {
                members.add(new Member(category, current.puzzleId()));
            }
        }
        insertMembers(members);
        applyChanges(importId, changes);
        markUnchanged(importId, unchanged);
        // A conflicting record still proves the ID is in this snapshot: keep the stored Puzzle.
        markUnchanged(importId, confirmed);
        storeIssues(importId, issues);

        int updatedRows = jdbc.update("""
                UPDATE training_puzzle_corpus_import
                SET processed_records = ?, records_complete = ?,
                    inserted_puzzles = inserted_puzzles + ?,
                    updated_puzzles = updated_puzzles + ?,
                    unchanged_puzzles = unchanged_puzzles + ?,
                    rejected_records = rejected_records + ?,
                    conflicting_records = conflicting_records + ?,
                    updated_at_ms = ?
                WHERE id = ? AND state = 'importing' AND processed_records = ?
                  AND records_complete = 0
                """,
                expectedProcessed + entries.size(), complete ? 1 : 0, inserts.size(),
                changes.size() + missingCurrent.size(), unchanged.size(), rejected, conflicts,
                nowMs, importId, expectedProcessed);
        require(updatedRows);
    }

    private void insertPuzzles(List<Accepted> inserts, long nowMs) {
        if (inserts.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO training_puzzle (
                    provider, external_id, source_fen, source_moves_uci, setup_move_uci,
                    position_fen, solution_uci, game_url, license, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, inserts, inserts.size(), (statement, value) -> {
                    PuzzleImportBatch.PuzzleRow row = value.row();
                    statement.setString(1, row.provider());
                    statement.setString(2, row.externalId());
                    statement.setString(3, row.sourceFen());
                    statement.setString(4, join(row.sourceMoves()));
                    statement.setString(5, row.setupMove());
                    statement.setString(6, row.positionFen());
                    statement.setString(7, join(row.solutionMoves()));
                    statement.setString(8, row.gameUrl());
                    statement.setString(9, row.license());
                    statement.setLong(10, nowMs);
                });
    }

    private void insertCurrent(long importId, List<CurrentRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO training_puzzle_current (
                    puzzle_id, corpus_import_id, rating, rating_deviation, popularity,
                    play_count, source_themes, source_opening_tags, daily_date
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, rows.size(), (statement, current) -> {
                    PuzzleImportBatch.PuzzleRow row = current.row();
                    statement.setLong(1, current.puzzleId());
                    statement.setLong(2, importId);
                    statement.setInt(3, row.rating());
                    statement.setInt(4, row.ratingDeviation());
                    statement.setInt(5, row.popularity());
                    statement.setInt(6, row.playCount());
                    statement.setString(7, join(row.themes()));
                    statement.setString(8, join(row.openingTags()));
                    statement.setString(9, row.dailyDate() == null ? null : row.dailyDate().toString());
                });
    }

    private void applyChanges(long importId, List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                UPDATE training_puzzle_current
                SET corpus_import_id = ?, rating = ?, rating_deviation = ?, popularity = ?,
                    play_count = ?, source_themes = ?, source_opening_tags = ?, daily_date = ?
                WHERE puzzle_id = ?
                """, changes, changes.size(), (statement, change) -> {
                    PuzzleImportBatch.PuzzleRow row = change.row();
                    statement.setLong(1, importId);
                    statement.setInt(2, row.rating());
                    statement.setInt(3, row.ratingDeviation());
                    statement.setInt(4, row.popularity());
                    statement.setInt(5, row.playCount());
                    statement.setString(6, join(row.themes()));
                    statement.setString(7, join(row.openingTags()));
                    statement.setString(8, row.dailyDate() == null ? null : row.dailyDate().toString());
                    statement.setLong(9, change.stored().id());
                });
        List<Member> removed = new ArrayList<>();
        List<Member> added = new ArrayList<>();
        for (Change change : changes) {
            Existing stored = change.stored();
            Set<Integer> before = new LinkedHashSet<>(categories.categories(
                    split(stored.themes()), split(stored.openingTags()), false));
            Set<Integer> after = new LinkedHashSet<>(categories.categories(
                    change.row().themes(), change.row().openingTags(), true));
            for (Integer category : before) {
                if (!after.contains(category)) {
                    removed.add(new Member(category, stored.id()));
                }
            }
            for (Integer category : after) {
                if (!before.contains(category)) {
                    added.add(new Member(category, stored.id()));
                }
            }
        }
        deleteMembers(removed);
        insertMembers(added);
    }

    private void markUnchanged(long importId, List<Long> puzzleIds) {
        if (puzzleIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                UPDATE training_puzzle_current SET corpus_import_id = ? WHERE puzzle_id = ?
                """, puzzleIds, puzzleIds.size(), (statement, puzzleId) -> {
                    statement.setLong(1, importId);
                    statement.setLong(2, puzzleId);
                });
    }

    private void storeIssues(long importId, List<IssueRow> issues) {
        if (issues.isEmpty()) {
            return;
        }
        int stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM training_puzzle_corpus_issue WHERE import_id = ?",
                Integer.class, importId);
        List<IssueRow> kept = issues.stream()
                .sorted((left, right) -> Long.compare(left.recordNumber(), right.recordNumber()))
                .limit(Math.max(0, MAX_STORED_ISSUES - stored))
                .toList();
        if (kept.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT OR IGNORE INTO training_puzzle_corpus_issue (
                    import_id, record_number, kind, external_id, reason
                ) VALUES (?, ?, ?, ?, ?)
                """, kept, kept.size(), (statement, issue) -> {
                    statement.setLong(1, importId);
                    statement.setLong(2, issue.recordNumber());
                    statement.setString(3, issue.kind());
                    statement.setString(4, issue.externalId());
                    statement.setString(5, issue.reason());
                });
    }

    private void insertMembers(List<Member> members) {
        if (members.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT OR IGNORE INTO training_puzzle_category_member (category_id, puzzle_id)
                VALUES (?, ?)
                """, members, members.size(), (statement, member) -> {
                    statement.setInt(1, member.category());
                    statement.setLong(2, member.puzzleId());
                });
    }

    private void deleteMembers(List<Member> members) {
        if (members.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                DELETE FROM training_puzzle_category_member
                WHERE category_id = ? AND puzzle_id = ?
                """, members, members.size(), (statement, member) -> {
                    statement.setInt(1, member.category());
                    statement.setLong(2, member.puzzleId());
                });
    }

    private Map<String, Existing> existing(List<String> externalIds) {
        if (externalIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Existing> found = new HashMap<>();
        for (int start = 0; start < externalIds.size(); start += LOOKUP_BATCH) {
            List<String> slice = externalIds.subList(
                    start, Math.min(externalIds.size(), start + LOOKUP_BATCH));
            List<Object> arguments = new ArrayList<>(slice.size() + 1);
            arguments.add(PROVIDER);
            arguments.addAll(slice);
            jdbc.query("""
                    SELECT p.id, p.external_id, p.source_fen, p.source_moves_uci,
                           c.puzzle_id AS current_id, c.corpus_import_id, c.rating,
                           c.rating_deviation, c.popularity, c.play_count, c.source_themes,
                           c.source_opening_tags, c.daily_date
                    FROM training_puzzle p
                    LEFT JOIN training_puzzle_current c ON c.puzzle_id = p.id
                    WHERE p.provider = ? AND p.external_id IN (%s)
                    """.formatted(String.join(",", Collections.nCopies(slice.size(), "?"))),
                    row -> {
                        Existing value = mapExisting(row);
                        found.put(value.externalId(), value);
                    }, arguments.toArray());
        }
        return found;
    }

    // Finalization --------------------------------------------------------------------------

    /**
     * Removes one batch of Puzzles that a newer verified snapshot no longer contains, walking
     * the import index by key. A missing Puzzle is retained whenever user work or authored content
     * references it, including any account's attempts or Training List state ({@link #RETAINED}).
     */
    @Transactional
    public RemovalBatch removeMissing(
            long importId, long afterImportId, long afterPuzzleId, int limit) {
        List<Missing> candidates = jdbc.query("""
                SELECT puzzle_id, corpus_import_id, source_themes, source_opening_tags
                FROM training_puzzle_current
                WHERE (corpus_import_id, puzzle_id) > (?, ?) AND corpus_import_id < ?
                ORDER BY corpus_import_id, puzzle_id
                LIMIT ?
                """, (row, number) -> new Missing(
                        row.getLong(1), row.getLong(2), row.getString(3), row.getString(4)),
                afterImportId, afterPuzzleId, importId, limit);
        int removed = 0;
        int retained = 0;
        List<Member> members = new ArrayList<>();
        List<Long> deleted = new ArrayList<>();
        for (Missing candidate : candidates) {
            boolean keep = jdbc.queryForObject(RETAINED, Integer.class,
                    candidate.puzzleId(), candidate.puzzleId(), candidate.puzzleId(),
                    candidate.puzzleId(), candidate.puzzleId(), candidate.puzzleId()) == 1;
            if (keep) {
                retained++;
                continue;
            }
            for (Integer category : categories.categories(
                    split(candidate.themes()), split(candidate.openingTags()), false)) {
                members.add(new Member(category, candidate.puzzleId()));
            }
            deleted.add(candidate.puzzleId());
            removed++;
        }
        deleteMembers(members);
        if (!deleted.isEmpty()) {
            jdbc.batchUpdate("DELETE FROM training_puzzle_current WHERE puzzle_id = ?",
                    deleted, deleted.size(), (statement, id) -> statement.setLong(1, id));
            jdbc.batchUpdate("DELETE FROM training_puzzle WHERE id = ?",
                    deleted, deleted.size(), (statement, id) -> statement.setLong(1, id));
            jdbc.update("""
                    UPDATE training_puzzle_corpus_import
                    SET removed_puzzles = removed_puzzles + ? WHERE id = ?
                    """, removed, importId);
        }
        Missing last = candidates.isEmpty() ? null : candidates.getLast();
        return new RemovalBatch(
                removed, retained,
                last == null ? afterImportId : last.importId(),
                last == null ? afterPuzzleId : last.puzzleId(),
                candidates.size() < limit);
    }

    /** Puzzle references that preserve a source record removed by a later snapshot. */
    static final String RETAINED = """
            SELECT EXISTS (SELECT 1 FROM training_method_exercise_puzzle WHERE puzzle_id = ?)
                OR EXISTS (SELECT 1 FROM training_puzzle_collection_member WHERE puzzle_id = ?)
                OR EXISTS (SELECT 1 FROM training_puzzle_observation WHERE puzzle_id = ?)
                OR EXISTS (SELECT 1 FROM training_puzzle_attempt WHERE puzzle_id = ?)
                OR EXISTS (SELECT 1 FROM training_puzzle_performance WHERE puzzle_id = ?)
                OR EXISTS (SELECT 1 FROM training_puzzle_session_item WHERE puzzle_id = ?)
            """;

    /**
     * Verifies that every accepted record of the snapshot is stored exactly once, then records
     * the auditable snapshot metadata. Throws when the stored corpus disagrees with the counts.
     */
    @Transactional
    public ImportRow verify(long importId, long nowMs) {
        ImportRow row = get(importId).orElseThrow();
        long stored = jdbc.queryForObject("""
                SELECT COUNT(*) FROM training_puzzle_current WHERE corpus_import_id = ?
                """, Long.class, importId);
        long accepted = row.insertedPuzzles() + row.updatedPuzzles() + row.unchangedPuzzles();
        long processed = accepted + row.rejectedRecords() + row.conflictingRecords();
        long confirmed = accepted + row.conflictingRecords();
        if (!row.recordsComplete() || !"finalizing".equals(row.state())
                || stored != confirmed || processed != row.processedRecords()) {
            throw new IllegalStateException("Puzzle corpus verification failed");
        }
        long retained = jdbc.queryForObject("""
                SELECT COUNT(*) FROM training_puzzle_current WHERE corpus_import_id < ?
                """, Long.class, importId);
        require(jdbc.update("""
                UPDATE training_puzzle_corpus_import
                SET state = 'verified', failure_code = NULL, record_count = ?,
                    retained_puzzles = ?, completed_at_ms = ?, updated_at_ms = ?
                WHERE id = ? AND state = 'finalizing'
                """, stored, retained, nowMs, nowMs, importId));
        categories.refreshCounts();
        return get(importId).orElseThrow();
    }

    // Mapping -------------------------------------------------------------------------------

    private static ImportRow mapImport(ResultSet row, int number) throws SQLException {
        return new ImportRow(
                row.getLong("id"), row.getString("source_url"), row.getString("source_etag"),
                row.getLong("source_last_modified_ms"),
                LocalDate.parse(row.getString("snapshot_date")),
                row.getLong("source_bytes"), row.getLong("downloaded_bytes"),
                row.getString("download_checksum"), row.getString("importer_version"),
                row.getString("state"), row.getString("failure_code"),
                row.getLong("processed_records"), row.getInt("records_complete") == 1,
                row.getLong("inserted_puzzles"), row.getLong("updated_puzzles"),
                row.getLong("unchanged_puzzles"), row.getLong("rejected_records"),
                row.getLong("conflicting_records"), row.getLong("removed_puzzles"),
                row.getLong("retained_puzzles"), nullableLong(row, "record_count"),
                row.getInt("source_deleted") == 1, row.getLong("started_at_ms"),
                row.getLong("updated_at_ms"), nullableLong(row, "completed_at_ms"));
    }

    private static Existing mapExisting(ResultSet row) throws SQLException {
        boolean hasCurrent = row.getObject("current_id") != null;
        return new Existing(
                row.getLong("id"), row.getString("external_id"), row.getString("source_fen"),
                row.getString("source_moves_uci"), hasCurrent,
                nullableLong(row, "corpus_import_id"),
                hasCurrent ? row.getInt("rating") : 0,
                hasCurrent ? row.getInt("rating_deviation") : 0,
                hasCurrent ? row.getInt("popularity") : 0,
                hasCurrent ? row.getInt("play_count") : 0,
                row.getString("source_themes"), row.getString("source_opening_tags"),
                row.getString("daily_date"));
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static String join(List<String> values) {
        return String.join(" ", values);
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(" "));
    }

    private static void require(int updatedRows) {
        if (updatedRows != 1) {
            throw new IllegalStateException("Puzzle corpus import state changed unexpectedly");
        }
    }

    private record Existing(
            long id,
            String externalId,
            String sourceFen,
            String sourceMoves,
            boolean hasCurrent,
            Long importId,
            int rating,
            int ratingDeviation,
            int popularity,
            int playCount,
            String themes,
            String openingTags,
            String dailyDate) {

        boolean sameObservation(PuzzleImportBatch.PuzzleRow row) {
            return rating == row.rating()
                    && ratingDeviation == row.ratingDeviation()
                    && popularity == row.popularity()
                    && playCount == row.playCount()
                    && Objects.equals(themes, join(row.themes()))
                    && Objects.equals(openingTags, join(row.openingTags()))
                    && Objects.equals(dailyDate,
                            row.dailyDate() == null ? null : row.dailyDate().toString());
        }
    }

    private record Change(Existing stored, PuzzleImportBatch.PuzzleRow row) {}

    private record CurrentRow(long puzzleId, PuzzleImportBatch.PuzzleRow row) {}

    private record Member(int category, long puzzleId) {}

    private record Missing(long puzzleId, long importId, String themes, String openingTags) {}
}
