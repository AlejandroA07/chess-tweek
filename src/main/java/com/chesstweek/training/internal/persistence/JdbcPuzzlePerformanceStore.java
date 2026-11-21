package com.chesstweek.training.internal.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.internal.library.PuzzleImportBatch;

/**
 * Account attempts and Training List state for normal Puzzles. Selection seeks a uniform point in
 * the Puzzle ID range on a primary key and walks forward past excluded Puzzles; it never sorts or
 * scans the corpus, and the walk is bounded by the account's own excluded Puzzles.
 */
@Repository
public class JdbcPuzzlePerformanceStore {

    private final JdbcTemplate jdbc;
    private final PuzzleCategoryIndex categories;

    JdbcPuzzlePerformanceStore(JdbcTemplate jdbc, PuzzleCategoryIndex categories) {
        this.jdbc = jdbc;
        this.categories = categories;
    }

    public enum Status {
        ACTIVE,
        COMPLETED,
        ABANDONED
    }

    public record AttemptRow(
            long id,
            String accountId,
            long puzzleId,
            PuzzleTraining.Entry entry,
            Status status,
            PuzzleTraining.TrainingList listBefore,
            int nextPly,
            int wrongMoves,
            boolean hintUsed,
            long fastLimitMillis,
            long startedAtMillis) {}

    /** A Puzzle with no Training List state for the account, or empty when none remains. */
    public Optional<Long> randomUnclassified(String accountId, RandomGenerator random) {
        Long low = firstLong("SELECT min(puzzle_id) FROM training_puzzle_current");
        Long high = firstLong("SELECT max(puzzle_id) FROM training_puzzle_current");
        if (low == null || high == null) {
            return Optional.empty();
        }
        long start = random.nextLong(low, high + 1);
        String seek = """
                SELECT c.puzzle_id FROM training_puzzle_current c
                WHERE c.puzzle_id %s ?
                  AND NOT EXISTS (
                      SELECT 1 FROM training_puzzle_performance s
                      WHERE s.account_id = ? AND s.puzzle_id = c.puzzle_id)
                ORDER BY c.puzzle_id LIMIT 1
                """;
        Long found = firstLong(seek.formatted(">="), start, accountId);
        return Optional.ofNullable(found != null ? found : firstLong(seek.formatted("<"), start, accountId));
    }

    /** A Puzzle in one category that is not Mastered for the account. */
    public Optional<Long> randomInCategory(
            String accountId, PuzzleImportBatch.Kind kind, String key, RandomGenerator random) {
        Integer category = categories.category(kind, key);
        if (category == null) {
            return Optional.empty();
        }
        Long low = firstLong("""
                SELECT min(puzzle_id) FROM training_puzzle_category_member WHERE category_id = ?
                """, category);
        Long high = firstLong("""
                SELECT max(puzzle_id) FROM training_puzzle_category_member WHERE category_id = ?
                """, category);
        if (low == null || high == null) {
            return Optional.empty();
        }
        long start = random.nextLong(low, high + 1);
        String seek = """
                SELECT m.puzzle_id FROM training_puzzle_category_member m
                WHERE m.category_id = ? AND m.puzzle_id %s ?
                  AND NOT EXISTS (
                      SELECT 1 FROM training_puzzle_performance s
                      WHERE s.account_id = ? AND s.puzzle_id = m.puzzle_id
                        AND s.list = 'mastered')
                ORDER BY m.puzzle_id LIMIT 1
                """;
        Long found = firstLong(seek.formatted(">="), category, start, accountId);
        return Optional.ofNullable(
                found != null ? found : firstLong(seek.formatted("<"), category, start, accountId));
    }

    public Optional<PuzzleTraining.Performance> performance(String accountId, long puzzleId) {
        return jdbc.query("""
                SELECT list, successes, strikes FROM training_puzzle_performance
                WHERE account_id = ? AND puzzle_id = ?
                """, (row, number) -> new PuzzleTraining.Performance(
                        trainingList(row.getString(1)), row.getInt(2), row.getInt(3)),
                accountId, puzzleId).stream().findFirst();
    }

    /** Abandons the account's other active attempts and starts a fresh one. */
    @Transactional
    public long startAttempt(
            String accountId,
            long puzzleId,
            PuzzleTraining.Entry entry,
            PuzzleTraining.TrainingList listBefore,
            long fastLimitMillis,
            long nowMillis) {
        jdbc.update("""
                UPDATE training_puzzle_attempt SET status = 'abandoned', finished_at_ms = ?
                WHERE account_id = ? AND status = 'active'
                """, nowMillis, accountId);
        jdbc.update("""
                INSERT INTO training_puzzle_attempt (
                    account_id, puzzle_id, entry, status, list_before, next_ply,
                    wrong_moves, hint_used, revealed, fast_limit_ms, started_at_ms
                ) VALUES (?, ?, ?, 'active', ?, 0, 0, 0, 0, ?, ?)
                """, accountId, puzzleId, databaseValue(entry), databaseValue(listBefore), fastLimitMillis, nowMillis);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public Optional<AttemptRow> attempt(String accountId, long attemptId) {
        return jdbc.query("""
                SELECT id, account_id, puzzle_id, entry, status, list_before, next_ply,
                       wrong_moves, hint_used, fast_limit_ms, started_at_ms
                FROM training_puzzle_attempt WHERE id = ? AND account_id = ?
                """, (row, number) -> new AttemptRow(
                        row.getLong(1),
                        row.getString(2),
                        row.getLong(3),
                        PuzzleTraining.Entry.valueOf(row.getString(4).toUpperCase(Locale.ROOT)),
                        Status.valueOf(row.getString(5).toUpperCase(Locale.ROOT)),
                        row.getString(6) == null ? null : trainingList(row.getString(6)),
                        row.getInt(7),
                        row.getInt(8),
                        row.getInt(9) == 1,
                        row.getLong(10),
                        row.getLong(11)),
                attemptId, accountId).stream().findFirst();
    }

    /**
     * Records solving progress on an active attempt that is still at {@code expectedPly} with
     * {@code expectedWrongMoves}, and stores any list movement the attempt has already decided
     * (computed from the account's current state inside the same transaction). Returns false,
     * changing nothing, when another request already moved, counted, or finished it.
     */
    @Transactional
    public boolean progress(
            AttemptRow attempt,
            int nextPly,
            int wrongMoves,
            boolean hintUsed,
            Function<Optional<PuzzleTraining.Performance>, Optional<PuzzleTraining.Performance>>
                    immediate,
            long nowMillis) {
        int updated = jdbc.update("""
                UPDATE training_puzzle_attempt
                SET next_ply = ?, wrong_moves = ?, hint_used = ?
                WHERE id = ? AND status = 'active' AND next_ply = ? AND wrong_moves = ?
                """, nextPly, wrongMoves, hintUsed ? 1 : 0,
                attempt.id(), attempt.nextPly(), attempt.wrongMoves());
        if (updated != 1) {
            return false;
        }
        immediate.apply(performance(attempt.accountId(), attempt.puzzleId()))
                .ifPresent(next -> jdbc.update("""
                        UPDATE training_puzzle_performance
                        SET list = ?, successes = ?, strikes = ?, updated_at_ms = ?
                        WHERE account_id = ? AND puzzle_id = ?
                        """, databaseValue(next.list()), next.successes(), next.strikes(),
                        nowMillis, attempt.accountId(), attempt.puzzleId()));
        return true;
    }

    /** The final state of one completed attempt. */
    public record Completed(
            int finalPly,
            int wrongMoves,
            boolean hintUsed,
            boolean revealed,
            long elapsedMillis) {}

    /**
     * Completes an active attempt that has not moved on and stores the account's new list state,
     * computed from its current state inside the same transaction. A Mastered-practice attempt
     * records the unchanged state and never writes it. Returns empty, changing nothing, when
     * another request already moved, counted, or finished the attempt.
     */
    @Transactional
    public Optional<PuzzleTraining.Performance> complete(
            AttemptRow attempt,
            Completed completed,
            Function<Optional<PuzzleTraining.Performance>, PuzzleTraining.Performance> rules,
            long nowMillis) {
        PuzzleTraining.Performance next =
                rules.apply(performance(attempt.accountId(), attempt.puzzleId()));
        int updated = jdbc.update("""
                UPDATE training_puzzle_attempt
                SET status = 'completed', next_ply = ?, wrong_moves = ?, hint_used = ?,
                    revealed = ?, finished_at_ms = ?, elapsed_ms = ?, list_after = ?
                WHERE id = ? AND status = 'active' AND next_ply = ? AND wrong_moves = ?
                """, completed.finalPly(), completed.wrongMoves(), completed.hintUsed() ? 1 : 0,
                completed.revealed() ? 1 : 0, nowMillis, completed.elapsedMillis(),
                databaseValue(next.list()), attempt.id(), attempt.nextPly(),
                attempt.wrongMoves());
        if (updated != 1) {
            return Optional.empty();
        }
        if (attempt.entry() == PuzzleTraining.Entry.MASTERED_PRACTICE) {
            return Optional.of(next);
        }
        jdbc.update("""
                INSERT INTO training_puzzle_performance (
                    account_id, puzzle_id, list, successes, strikes,
                    classified_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, puzzle_id) DO UPDATE SET
                    list = excluded.list,
                    successes = excluded.successes,
                    strikes = excluded.strikes,
                    updated_at_ms = excluded.updated_at_ms
                """, attempt.accountId(), attempt.puzzleId(), databaseValue(next.list()),
                next.successes(), next.strikes(), nowMillis, nowMillis);
        return Optional.of(next);
    }

    /** The number of the account's Puzzles in each Training List; lists with none are absent. */
    public Map<PuzzleTraining.TrainingList, Integer> listCounts(String accountId) {
        Map<PuzzleTraining.TrainingList, Integer> counts =
                new EnumMap<>(PuzzleTraining.TrainingList.class);
        jdbc.query("""
                SELECT list, COUNT(*) FROM training_puzzle_performance
                WHERE account_id = ? GROUP BY list
                """, row -> {
                    counts.put(trainingList(row.getString(1)), row.getInt(2));
                }, accountId);
        return counts;
    }

    public int count(String accountId, PuzzleTraining.TrainingList list) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM training_puzzle_performance WHERE account_id = ? AND list = ?
                """, Integer.class, accountId, databaseValue(list));
    }

    /**
     * The account's Mastered Puzzle count in every category of their current observations, keyed
     * by category kind and key. Membership is derived from the stored tags exactly as the category
     * index derives it, so the walk is bounded by the account's Mastered Puzzles.
     */
    public Map<PuzzleImportBatch.Kind, Map<String, Integer>> masteredCategoryCounts(String accountId) {
        Map<Integer, Integer> byId = new HashMap<>();
        jdbc.query("""
                SELECT c.source_themes, c.source_opening_tags
                FROM training_puzzle_performance s
                JOIN training_puzzle_current c ON c.puzzle_id = s.puzzle_id
                WHERE s.account_id = ? AND s.list = 'mastered'
                """, row -> {
                    for (Integer id : categories.categories(
                            split(row.getString(1)), split(row.getString(2)), false)) {
                        byId.merge(id, 1, Integer::sum);
                    }
                }, accountId);
        Map<PuzzleImportBatch.Kind, Map<String, Integer>> counts =
                new EnumMap<>(PuzzleImportBatch.Kind.class);
        if (byId.isEmpty()) {
            return counts;
        }
        jdbc.query("SELECT id, kind, classification_key FROM training_puzzle_category", row -> {
            Integer count = byId.get(row.getInt(1));
            if (count != null) {
                counts.computeIfAbsent(kind(row.getString(2)), key -> new HashMap<>())
                        .put(row.getString(3), count);
            }
        });
        return counts;
    }

    /** A session's list and its number of Puzzles. */
    public record SessionRow(long id, PuzzleTraining.TrainingList list, int total) {}

    /** The next Puzzle a session presents, with its one-based place in the session order. */
    public record SessionItem(long puzzleId, int position, int total) {}

    /**
     * Replaces the account's previous session with a new one over every Puzzle currently in
     * {@code list} (only those in the category, when {@code categoryKey} is given), shuffled.
     * Empty when no Puzzle qualifies.
     */
    @Transactional
    public Optional<SessionRow> startSession(
            String accountId,
            PuzzleTraining.TrainingList list,
            PuzzleImportBatch.Kind categoryKind,
            String categoryKey,
            Random random,
            long nowMillis) {
        Integer category = null;
        List<Long> puzzles;
        if (categoryKind == null) {
            puzzles = jdbc.queryForList("""
                    SELECT puzzle_id FROM training_puzzle_performance
                    WHERE account_id = ? AND list = ?
                    """, Long.class, accountId, databaseValue(list));
        } else {
            category = categories.category(categoryKind, categoryKey);
            if (category == null) {
                return Optional.empty();
            }
            puzzles = jdbc.queryForList("""
                    SELECT s.puzzle_id FROM training_puzzle_performance s
                    JOIN training_puzzle_category_member m
                      ON m.category_id = ? AND m.puzzle_id = s.puzzle_id
                    WHERE s.account_id = ? AND s.list = ?
                    """, Long.class, category, accountId, databaseValue(list));
        }
        if (puzzles.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update("""
                DELETE FROM training_puzzle_session_item WHERE session_id IN (
                    SELECT id FROM training_puzzle_session WHERE account_id = ?)
                """, accountId);
        jdbc.update("DELETE FROM training_puzzle_session WHERE account_id = ?", accountId);
        jdbc.update("""
                INSERT INTO training_puzzle_session (account_id, list, category_id, created_at_ms)
                VALUES (?, ?, ?, ?)
                """, accountId, databaseValue(list), category, nowMillis);
        long sessionId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        List<Long> order = new ArrayList<>(puzzles);
        Collections.shuffle(order, random);
        List<Object[]> rows = new ArrayList<>(order.size());
        for (int index = 0; index < order.size(); index++) {
            rows.add(new Object[] {sessionId, index + 1, order.get(index)});
        }
        jdbc.batchUpdate("""
                INSERT INTO training_puzzle_session_item (session_id, position, puzzle_id)
                VALUES (?, ?, ?)
                """, rows);
        return Optional.of(new SessionRow(sessionId, list, order.size()));
    }

    public Optional<SessionRow> session(String accountId, long sessionId) {
        return jdbc.query("""
                SELECT s.id, s.list, (
                    SELECT COUNT(*) FROM training_puzzle_session_item i WHERE i.session_id = s.id)
                FROM training_puzzle_session s WHERE s.id = ? AND s.account_id = ?
                """, (row, number) -> new SessionRow(
                        row.getLong(1), trainingList(row.getString(2)), row.getInt(3)),
                sessionId, accountId).stream().findFirst();
    }

    /**
     * Marks and returns the session's next unpresented Puzzle that is still in the session's list.
     * A Puzzle that has left the list since the session started is passed over. Empty when every
     * Puzzle has been presented.
     */
    @Transactional
    public Optional<SessionItem> presentNext(String accountId, SessionRow session, long nowMillis) {
        while (true) {
            SessionItem item = jdbc.query("""
                    SELECT puzzle_id, position FROM training_puzzle_session_item
                    WHERE session_id = ? AND presented_at_ms IS NULL
                    ORDER BY position LIMIT 1
                    """, (row, number) -> new SessionItem(
                            row.getLong(1), row.getInt(2), session.total()),
                    session.id()).stream().findFirst().orElse(null);
            if (item == null) {
                return Optional.empty();
            }
            int claimed = jdbc.update("""
                    UPDATE training_puzzle_session_item SET presented_at_ms = ?
                    WHERE session_id = ? AND position = ? AND presented_at_ms IS NULL
                    """, nowMillis, session.id(), item.position());
            if (claimed != 1) {
                // A concurrent request presented it; never present one Puzzle twice.
                continue;
            }
            boolean inList = performance(accountId, item.puzzleId())
                    .map(state -> state.list() == session.list())
                    .orElse(false);
            if (inList) {
                return Optional.of(item);
            }
        }
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(" "));
    }

    private static PuzzleImportBatch.Kind kind(String databaseValue) {
        for (PuzzleImportBatch.Kind kind : PuzzleImportBatch.Kind.values()) {
            if (kind.databaseValue().equals(databaseValue)) {
                return kind;
            }
        }
        throw new IllegalStateException("Unknown category kind");
    }

    private Long firstLong(String sql, Object... arguments) {
        // An aggregate over no rows returns one NULL row; a seek with no match returns none.
        java.util.List<Long> rows = jdbc.query(sql, (row, number) -> {
            long value = row.getLong(1);
            return row.wasNull() ? null : value;
        }, arguments);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static PuzzleTraining.TrainingList trainingList(String value) {
        return PuzzleTraining.TrainingList.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static String databaseValue(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
}
