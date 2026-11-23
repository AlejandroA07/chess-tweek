package com.chesstweek.training.internal.persistence;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.random.RandomGenerator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.training.PuzzleTraining;
import com.chesstweek.training.internal.library.PuzzleImportBatch;

/**
 * Account attempts and Training List state for normal Puzzles. Attempts, list state, and sessions
 * live in {@link #state()}; this store adds the normal-Puzzle selection. Selection seeks a uniform
 * point in the Puzzle ID range on a primary key and walks forward past excluded Puzzles; it never
 * sorts or scans the corpus, and the walk is bounded by the account's own excluded Puzzles.
 */
@Repository
public class JdbcPuzzlePerformanceStore {

    private static final JdbcPerformanceState.Tables TABLES = new JdbcPerformanceState.Tables(
            "training_puzzle_attempt",
            "training_puzzle_performance",
            "training_puzzle_session",
            "training_puzzle_session_item",
            "category_id");

    private final JdbcTemplate jdbc;
    private final PuzzleCategoryIndex categories;
    private final JdbcPerformanceState state;

    JdbcPuzzlePerformanceStore(
            JdbcTemplate jdbc,
            PuzzleCategoryIndex categories,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.categories = categories;
        this.state = new JdbcPerformanceState(jdbc, transactions, TABLES);
    }

    /** Attempts, Training List state, and Practice Sessions of normal Puzzles. */
    public JdbcPerformanceState state() {
        return state;
    }

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

    /**
     * Replaces the account's previous session with a new one over every Puzzle currently in
     * {@code list} (only those in the category, when {@code categoryKind} is given), shuffled.
     * Empty when no Puzzle qualifies. The Puzzles are read in the session's transaction.
     */
    @Transactional
    public Optional<JdbcPerformanceState.SessionRow> startSession(
            String accountId,
            PuzzleTraining.TrainingList list,
            PuzzleImportBatch.Kind categoryKind,
            String categoryKey,
            Random random,
            long nowMillis) {
        if (categoryKind == null) {
            return state.startSession(accountId, list, null,
                    state.puzzlesInList(accountId, list), random, nowMillis);
        }
        Integer category = categories.category(categoryKind, categoryKey);
        if (category == null) {
            return Optional.empty();
        }
        List<Long> puzzles = jdbc.queryForList("""
                SELECT s.puzzle_id FROM training_puzzle_performance s
                JOIN training_puzzle_category_member m
                  ON m.category_id = ? AND m.puzzle_id = s.puzzle_id
                WHERE s.account_id = ? AND s.list = ?
                """, Long.class, category, accountId, JdbcPerformanceState.databaseValue(list));
        return state.startSession(accountId, list, category, puzzles, random, nowMillis);
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
}
