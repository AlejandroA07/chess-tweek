package com.chesstweek.training.internal.persistence;

import java.util.Locale;
import java.util.Optional;
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
            Status status,
            PuzzleTraining.TrainingList listBefore,
            int nextPly,
            int wrongMoves,
            boolean hintUsed,
            long fastLimitMillis,
            long startedAtMillis) {}

    /** A Puzzle with no Training List state for the account, or empty when none remains. */
    public Optional<Long> randomUnclassified(String accountId, RandomGenerator random) {
        Long low = id("SELECT min(puzzle_id) FROM training_puzzle_current");
        Long high = id("SELECT max(puzzle_id) FROM training_puzzle_current");
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
        Long found = id(seek.formatted(">="), start, accountId);
        return Optional.ofNullable(found != null ? found : id(seek.formatted("<"), start, accountId));
    }

    /** A Puzzle in one category that is not Mastered for the account. */
    public Optional<Long> randomInCategory(
            String accountId, PuzzleImportBatch.Kind kind, String key, RandomGenerator random) {
        Integer category = categories.category(kind, key);
        if (category == null) {
            return Optional.empty();
        }
        Long low = id("""
                SELECT min(puzzle_id) FROM training_puzzle_category_member WHERE category_id = ?
                """, category);
        Long high = id("""
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
        Long found = id(seek.formatted(">="), category, start, accountId);
        return Optional.ofNullable(
                found != null ? found : id(seek.formatted("<"), category, start, accountId));
    }

    public Optional<PuzzleTraining.Performance> performance(String accountId, long puzzleId) {
        return jdbc.query("""
                SELECT list, successes, strikes FROM training_puzzle_performance
                WHERE account_id = ? AND puzzle_id = ?
                """, (row, number) -> new PuzzleTraining.Performance(
                        list(row.getString(1)), row.getInt(2), row.getInt(3)),
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
                """, accountId, puzzleId, value(entry), value(listBefore), fastLimitMillis, nowMillis);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public Optional<AttemptRow> attempt(String accountId, long attemptId) {
        return jdbc.query("""
                SELECT id, account_id, puzzle_id, status, list_before, next_ply,
                       wrong_moves, hint_used, fast_limit_ms, started_at_ms
                FROM training_puzzle_attempt WHERE id = ? AND account_id = ?
                """, (row, number) -> new AttemptRow(
                        row.getLong(1),
                        row.getString(2),
                        row.getLong(3),
                        Status.valueOf(row.getString(4).toUpperCase(Locale.ROOT)),
                        row.getString(5) == null ? null : list(row.getString(5)),
                        row.getInt(6),
                        row.getInt(7),
                        row.getInt(8) == 1,
                        row.getLong(9),
                        row.getLong(10)),
                attemptId, accountId).stream().findFirst();
    }

    /**
     * Records solving progress on an active attempt that is still at {@code expectedPly}.
     * Returns false when another request already moved or finished it.
     */
    public boolean progress(
            long attemptId, int expectedPly, int nextPly, int wrongMoves, boolean hintUsed) {
        return jdbc.update("""
                UPDATE training_puzzle_attempt
                SET next_ply = ?, wrong_moves = ?, hint_used = ?
                WHERE id = ? AND status = 'active' AND next_ply = ?
                """, nextPly, wrongMoves, hintUsed ? 1 : 0, attemptId, expectedPly) == 1;
    }

    /**
     * Completes an active attempt still at {@code expectedPly} and stores the account's new list
     * state in the same transaction. Returns false, changing nothing, when it already moved on.
     */
    @Transactional
    public boolean complete(
            AttemptRow attempt,
            int expectedPly,
            int finalPly,
            int wrongMoves,
            boolean hintUsed,
            boolean revealed,
            long elapsedMillis,
            PuzzleTraining.Performance next,
            long nowMillis) {
        int updated = jdbc.update("""
                UPDATE training_puzzle_attempt
                SET status = 'completed', next_ply = ?, wrong_moves = ?, hint_used = ?,
                    revealed = ?, finished_at_ms = ?, elapsed_ms = ?, list_after = ?
                WHERE id = ? AND status = 'active' AND next_ply = ?
                """, finalPly, wrongMoves, hintUsed ? 1 : 0, revealed ? 1 : 0, nowMillis,
                elapsedMillis, value(next.list()), attempt.id(), expectedPly);
        if (updated != 1) {
            return false;
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
                """, attempt.accountId(), attempt.puzzleId(), value(next.list()),
                next.successes(), next.strikes(), nowMillis, nowMillis);
        return true;
    }

    private Long id(String sql, Object... arguments) {
        // An aggregate over no rows returns one NULL row; a seek with no match returns none.
        java.util.List<Long> rows = jdbc.query(sql, (row, number) -> {
            long value = row.getLong(1);
            return row.wasNull() ? null : value;
        }, arguments);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static PuzzleTraining.TrainingList list(String value) {
        return PuzzleTraining.TrainingList.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static String value(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
}
