package com.chesstweek.training.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chesstweek.training.OwnPuzzleTraining.ChangeOutcome;
import com.chesstweek.training.PuzzleTraining.TrainingList;

/**
 * Own Puzzle training state: New, Discarded, and the Own Puzzle rows the trainer reads, plus the
 * shared attempt, list, and session state over Own Puzzle tables. Every query is scoped to the
 * account that owns the puzzles.
 */
@Repository
public class JdbcOwnPuzzleTrainingStore {

    private static final JdbcPerformanceState.Tables TABLES = new JdbcPerformanceState.Tables(
            "training_own_puzzle_attempt",
            "training_own_puzzle_performance",
            "training_own_puzzle_session",
            "training_own_puzzle_session_item",
            "group_key");

    private static final String SELECT = """
            SELECT p.id, p.position_fen, p.previous_move_uci, p.solution_uci, p.label, p.phase,
                   p.opening_name, p.game_source, p.game_id, p.source_ply, p.game_played_at_ms,
                   (SELECT group_concat(t.theme || '/' || t.kind, ' ')
                    FROM training_own_puzzle_tactic t WHERE t.own_puzzle_id = p.id)
            FROM training_own_puzzle p
            """;
    private static final String UNCLASSIFIED = """
            NOT EXISTS (SELECT 1 FROM training_own_puzzle_performance s
                        WHERE s.account_id = p.account_id AND s.puzzle_id = p.id)
            """;
    private static final String DISCARDED = """
            EXISTS (SELECT 1 FROM training_own_puzzle_discard d
                    WHERE d.account_id = p.account_id AND d.puzzle_id = p.id)
            """;
    /** Binds the site twice: a null site matches every source game. */
    private static final String SITE = " AND (? IS NULL OR p.game_source = ?)";
    private static final String NEWEST_FIRST = " ORDER BY p.game_played_at_ms DESC, p.id DESC";

    /** One stored Own Puzzle; {@code tactics} are {@code theme/kind} values. */
    public record PuzzleRow(
            long id,
            String positionFen,
            String previousMoveUci,
            List<String> solution,
            String label,
            String phase,
            String openingName,
            String gameSource,
            String gameId,
            int sourcePly,
            long gamePlayedAtMillis,
            List<String> tactics) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JdbcPerformanceState state;

    JdbcOwnPuzzleTrainingStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.state = new JdbcPerformanceState(jdbc, transactions, TABLES);
    }

    /** Attempts, Training List state, and Practice Sessions of Own Puzzles. */
    public JdbcPerformanceState state() {
        return state;
    }

    /** Runs {@code work} in one transaction; nested state writes join it. */
    public <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    public Optional<PuzzleRow> puzzle(String accountId, long id) {
        return jdbc.query(SELECT + " WHERE p.account_id = ? AND p.id = ?",
                JdbcOwnPuzzleTrainingStore::row, accountId, id).stream().findFirst();
    }

    public boolean isNew(String accountId, long id) {
        return count(" WHERE p.account_id = ? AND p.id = ? AND " + UNCLASSIFIED
                + " AND NOT " + DISCARDED, accountId, id) == 1;
    }

    /** The newest New Own Puzzle from {@code site} (or every site when null). */
    public Optional<Long> newestNew(String accountId, String site) {
        return newAt(accountId, site, 0);
    }

    /**
     * The New Own Puzzle at {@code index} (newest first) from {@code site} (or every site when
     * null); empty when there are not that many.
     */
    public Optional<Long> newAt(String accountId, String site, int index) {
        return jdbc.queryForList("SELECT p.id FROM training_own_puzzle p WHERE p.account_id = ? AND "
                + UNCLASSIFIED + " AND NOT " + DISCARDED + SITE + NEWEST_FIRST + " LIMIT 1 OFFSET ?",
                Long.class, accountId, site, site, index).stream().findFirst();
    }

    public int countNew(String accountId) {
        return countNew(accountId, null);
    }

    /** New Own Puzzles from {@code site}, or from every site when null. */
    public int countNew(String accountId, String site) {
        return count(" WHERE p.account_id = ? AND " + UNCLASSIFIED + " AND NOT " + DISCARDED + SITE,
                accountId, site, site);
    }

    public int countDiscarded(String accountId) {
        return count(" WHERE p.account_id = ? AND " + DISCARDED, accountId);
    }

    /** One page of New Own Puzzles from {@code site} (or every site when null), newest first. */
    public List<PuzzleRow> newPage(String accountId, String site, int offset, int limit) {
        return jdbc.query(SELECT + " WHERE p.account_id = ? AND " + UNCLASSIFIED + " AND NOT "
                        + DISCARDED + SITE + NEWEST_FIRST + " LIMIT ? OFFSET ?",
                JdbcOwnPuzzleTrainingStore::row, accountId, site, site, limit, offset);
    }

    public List<PuzzleRow> discardedPage(String accountId, int offset, int limit) {
        return jdbc.query(SELECT + " WHERE p.account_id = ? AND " + DISCARDED + NEWEST_FIRST
                        + " LIMIT ? OFFSET ?",
                JdbcOwnPuzzleTrainingStore::row, accountId, limit, offset);
    }

    /** Every Own Puzzle of the account that is not discarded. */
    public List<PuzzleRow> kept(String accountId) {
        return jdbc.query(SELECT + " WHERE p.account_id = ? AND NOT " + DISCARDED + NEWEST_FIRST,
                JdbcOwnPuzzleTrainingStore::row, accountId);
    }

    public int countInList(String accountId, TrainingList list) {
        return state.count(accountId, list);
    }

    /** One page of the account's Own Puzzles in one Training List, newest source game first. */
    public List<PuzzleRow> inListPage(String accountId, TrainingList list, int offset, int limit) {
        return jdbc.query(SELECT + """
                 JOIN training_own_puzzle_performance s
                   ON s.account_id = p.account_id AND s.puzzle_id = p.id AND s.list = ?
                 WHERE p.account_id = ?
                """ + NEWEST_FIRST + " LIMIT ? OFFSET ?", JdbcOwnPuzzleTrainingStore::row,
                JdbcPerformanceState.databaseValue(list), accountId, limit, offset);
    }

    /** The account's Own Puzzles currently in one Training List. */
    public List<PuzzleRow> inList(String accountId, TrainingList list) {
        return jdbc.query(SELECT + """
                 JOIN training_own_puzzle_performance s
                   ON s.account_id = p.account_id AND s.puzzle_id = p.id AND s.list = ?
                 WHERE p.account_id = ?
                """ + NEWEST_FIRST, JdbcOwnPuzzleTrainingStore::row,
                JdbcPerformanceState.databaseValue(list), accountId);
    }

    /**
     * Discards an unclassified Own Puzzle and abandons any attempt in progress on it, in one
     * transaction, so the attempt can never classify it. Discarding twice changes nothing.
     */
    public ChangeOutcome discard(String accountId, long id, long nowMillis) {
        return transaction.execute(status -> {
            if (puzzle(accountId, id).isEmpty()) {
                return ChangeOutcome.NOT_FOUND;
            }
            if (state.performance(accountId, id).isPresent()) {
                return ChangeOutcome.CLASSIFIED;
            }
            jdbc.update("""
                    INSERT INTO training_own_puzzle_discard (account_id, puzzle_id, discarded_at_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT (account_id, puzzle_id) DO NOTHING
                    """, accountId, id, nowMillis);
            state.abandonActive(accountId, id, nowMillis);
            return ChangeOutcome.DONE;
        });
    }

    /** Returns a discarded Own Puzzle to New; a discarded puzzle never has list state. */
    public ChangeOutcome restore(String accountId, long id) {
        return transaction.execute(status -> {
            if (puzzle(accountId, id).isEmpty()) {
                return ChangeOutcome.NOT_FOUND;
            }
            int deleted = jdbc.update("""
                    DELETE FROM training_own_puzzle_discard WHERE account_id = ? AND puzzle_id = ?
                    """, accountId, id);
            return deleted == 1 ? ChangeOutcome.DONE : ChangeOutcome.NOT_DISCARDED;
        });
    }

    private int count(String where, Object... arguments) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM training_own_puzzle p" + where,
                Integer.class, arguments);
    }

    private static PuzzleRow row(ResultSet row, int number) throws SQLException {
        return new PuzzleRow(
                row.getLong(1),
                row.getString(2),
                row.getString(3),
                List.of(row.getString(4).split(" ")),
                row.getString(5),
                row.getString(6),
                row.getString(7),
                row.getString(8),
                row.getString(9),
                row.getInt(10),
                row.getLong(11),
                tactics(row.getString(12)));
    }

    private static List<String> tactics(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> tactics = new ArrayList<>(List.of(value.split(" ")));
        tactics.sort(Comparator.naturalOrder());
        return List.copyOf(tactics);
    }
}
