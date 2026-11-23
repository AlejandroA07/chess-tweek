package com.chesstweek.training.internal.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chesstweek.training.PuzzleTraining.Performance;
import com.chesstweek.training.PuzzleTraining.TrainingList;

/**
 * Account attempts, Training List state, and Practice Sessions for one kind of Puzzle. Normal and
 * Own Puzzles apply the same rules to separate tables, so each kind builds one instance over its
 * own {@link Tables}. Every write that reads state first runs in one transaction.
 */
public final class JdbcPerformanceState {

    /**
     * The tables of one Puzzle kind. Names are compile-time constants of the owning store, never
     * input. {@code sessionGroup} is the session column that selects a Mastered category.
     */
    public record Tables(
            String attempt, String performance, String session, String sessionItem,
            String sessionGroup) {}

    public enum Status {
        ACTIVE,
        COMPLETED,
        ABANDONED
    }

    /** {@code entry} is the stored entry-point value, such as {@code random} or {@code new}. */
    public record AttemptRow(
            long id,
            String accountId,
            long puzzleId,
            String entry,
            Status status,
            TrainingList listBefore,
            int nextPly,
            int wrongMoves,
            boolean hintUsed,
            long fastLimitMillis,
            long startedAtMillis) {}

    /** The final state of one completed attempt. */
    public record Completed(
            int finalPly,
            int wrongMoves,
            boolean hintUsed,
            boolean revealed,
            long elapsedMillis) {}

    /** A session's list and its number of Puzzles. */
    public record SessionRow(long id, TrainingList list, int total) {}

    /**
     * The next Puzzle a session presents. {@code position} counts the Puzzles presented so far,
     * including this one; {@code total} is that count plus the unpresented Puzzles still in the
     * session's list.
     */
    public record SessionItem(long puzzleId, int position, int total) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Tables tables;

    JdbcPerformanceState(JdbcTemplate jdbc, PlatformTransactionManager transactions, Tables tables) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.tables = tables;
    }

    public Optional<Performance> performance(String accountId, long puzzleId) {
        return jdbc.query("""
                SELECT list, successes, strikes FROM %s
                WHERE account_id = ? AND puzzle_id = ?
                """.formatted(tables.performance()), (row, number) -> new Performance(
                        trainingList(row.getString(1)), row.getInt(2), row.getInt(3)),
                accountId, puzzleId).stream().findFirst();
    }

    /** Abandons the account's other active attempts and starts a fresh one. */
    public long startAttempt(
            String accountId,
            long puzzleId,
            String entry,
            TrainingList listBefore,
            long fastLimitMillis,
            long nowMillis) {
        return transaction.execute(status -> {
            jdbc.update("""
                    UPDATE %s SET status = 'abandoned', finished_at_ms = ?
                    WHERE account_id = ? AND status = 'active'
                    """.formatted(tables.attempt()), nowMillis, accountId);
            jdbc.update("""
                    INSERT INTO %s (
                        account_id, puzzle_id, entry, status, list_before, next_ply,
                        wrong_moves, hint_used, revealed, fast_limit_ms, started_at_ms
                    ) VALUES (?, ?, ?, 'active', ?, 0, 0, 0, 0, ?, ?)
                    """.formatted(tables.attempt()), accountId, puzzleId, entry,
                    databaseValue(listBefore), fastLimitMillis, nowMillis);
            return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        });
    }

    public Optional<AttemptRow> attempt(String accountId, long attemptId) {
        return jdbc.query("""
                SELECT id, account_id, puzzle_id, entry, status, list_before, next_ply,
                       wrong_moves, hint_used, fast_limit_ms, started_at_ms
                FROM %s WHERE id = ? AND account_id = ?
                """.formatted(tables.attempt()), (row, number) -> new AttemptRow(
                        row.getLong(1),
                        row.getString(2),
                        row.getLong(3),
                        row.getString(4),
                        Status.valueOf(row.getString(5).toUpperCase(Locale.ROOT)),
                        row.getString(6) == null ? null : trainingList(row.getString(6)),
                        row.getInt(7),
                        row.getInt(8),
                        row.getInt(9) == 1,
                        row.getLong(10),
                        row.getLong(11)),
                attemptId, accountId).stream().findFirst();
    }

    /** Abandons the account's active attempts on one Puzzle. */
    public void abandonActive(String accountId, long puzzleId, long nowMillis) {
        jdbc.update("""
                UPDATE %s SET status = 'abandoned', finished_at_ms = ?
                WHERE account_id = ? AND puzzle_id = ? AND status = 'active'
                """.formatted(tables.attempt()), nowMillis, accountId, puzzleId);
    }

    /**
     * Records solving progress on an active attempt that is still at its expected ply and Wrong
     * Move count, and stores any list movement the attempt has already decided (computed from the
     * account's current state inside the same transaction). Returns false, changing nothing, when
     * another request already moved, counted, or finished it.
     */
    public boolean progress(
            AttemptRow attempt,
            int nextPly,
            int wrongMoves,
            boolean hintUsed,
            Function<Optional<Performance>, Optional<Performance>> immediate,
            long nowMillis) {
        return transaction.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE %s
                    SET next_ply = ?, wrong_moves = ?, hint_used = ?
                    WHERE id = ? AND status = 'active' AND next_ply = ? AND wrong_moves = ?
                    """.formatted(tables.attempt()), nextPly, wrongMoves, hintUsed ? 1 : 0,
                    attempt.id(), attempt.nextPly(), attempt.wrongMoves());
            if (updated != 1) {
                return false;
            }
            immediate.apply(performance(attempt.accountId(), attempt.puzzleId()))
                    .ifPresent(next -> jdbc.update("""
                            UPDATE %s
                            SET list = ?, successes = ?, strikes = ?, updated_at_ms = ?
                            WHERE account_id = ? AND puzzle_id = ?
                            """.formatted(tables.performance()), databaseValue(next.list()),
                            next.successes(), next.strikes(), nowMillis, attempt.accountId(),
                            attempt.puzzleId()));
            return true;
        });
    }

    /**
     * Completes an active attempt that has not moved on and stores the account's new list state,
     * computed from its current state inside the same transaction. A {@code practice} attempt
     * records the unchanged state and never writes it. Returns empty, changing nothing, when
     * another request already moved, counted, or finished the attempt.
     */
    public Optional<Performance> complete(
            AttemptRow attempt,
            Completed completed,
            boolean practice,
            Function<Optional<Performance>, Performance> rules,
            long nowMillis) {
        return transaction.execute(status -> {
            Performance next = rules.apply(performance(attempt.accountId(), attempt.puzzleId()));
            int updated = jdbc.update("""
                    UPDATE %s
                    SET status = 'completed', next_ply = ?, wrong_moves = ?, hint_used = ?,
                        revealed = ?, finished_at_ms = ?, elapsed_ms = ?, list_after = ?
                    WHERE id = ? AND status = 'active' AND next_ply = ? AND wrong_moves = ?
                    """.formatted(tables.attempt()), completed.finalPly(), completed.wrongMoves(),
                    completed.hintUsed() ? 1 : 0, completed.revealed() ? 1 : 0, nowMillis,
                    completed.elapsedMillis(), databaseValue(next.list()), attempt.id(),
                    attempt.nextPly(), attempt.wrongMoves());
            if (updated != 1) {
                return Optional.<Performance>empty();
            }
            if (practice) {
                return Optional.of(next);
            }
            jdbc.update("""
                    INSERT INTO %s (
                        account_id, puzzle_id, list, successes, strikes,
                        classified_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (account_id, puzzle_id) DO UPDATE SET
                        list = excluded.list,
                        successes = excluded.successes,
                        strikes = excluded.strikes,
                        updated_at_ms = excluded.updated_at_ms
                    """.formatted(tables.performance()), attempt.accountId(), attempt.puzzleId(),
                    databaseValue(next.list()), next.successes(), next.strikes(), nowMillis,
                    nowMillis);
            return Optional.of(next);
        });
    }

    /** The number of the account's Puzzles in each Training List; lists with none are absent. */
    public Map<TrainingList, Integer> listCounts(String accountId) {
        Map<TrainingList, Integer> counts = new EnumMap<>(TrainingList.class);
        jdbc.query("""
                SELECT list, COUNT(*) FROM %s WHERE account_id = ? GROUP BY list
                """.formatted(tables.performance()), row -> {
                    counts.put(trainingList(row.getString(1)), row.getInt(2));
                }, accountId);
        return counts;
    }

    public int count(String accountId, TrainingList list) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM %s WHERE account_id = ? AND list = ?
                """.formatted(tables.performance()), Integer.class, accountId,
                databaseValue(list));
    }

    /** The account's Puzzles currently in one list. */
    public List<Long> puzzlesInList(String accountId, TrainingList list) {
        return jdbc.queryForList("""
                SELECT puzzle_id FROM %s WHERE account_id = ? AND list = ?
                """.formatted(tables.performance()), Long.class, accountId, databaseValue(list));
    }

    /**
     * Replaces the account's previous session with a new one over {@code puzzles}, shuffled.
     * {@code group} is the session's Mastered category, or null for a whole list. When the previous
     * session covered the same list and group, the new order does not start with the Puzzle
     * presented last, so consecutive rounds never show one Puzzle twice in a row. Empty when
     * {@code puzzles} is empty.
     */
    public Optional<SessionRow> startSession(
            String accountId,
            TrainingList list,
            Object group,
            List<Long> puzzles,
            Random random,
            long nowMillis) {
        if (puzzles.isEmpty()) {
            return Optional.empty();
        }
        return transaction.execute(status -> {
            // The Puzzle the owner saw last in the previous session over the same list or group.
            Long previous = jdbc.query("""
                    SELECT i.puzzle_id FROM %s s
                    JOIN %s i ON i.session_id = s.id
                    WHERE s.account_id = ? AND s.list = ? AND s.%s IS ?
                      AND i.presented_at_ms IS NOT NULL
                    ORDER BY i.presented_at_ms DESC, i.position DESC LIMIT 1
                    """.formatted(tables.session(), tables.sessionItem(), tables.sessionGroup()),
                    (row, number) -> row.getLong(1), accountId, databaseValue(list), group)
                    .stream().findFirst().orElse(null);
            jdbc.update("""
                    DELETE FROM %s WHERE session_id IN (SELECT id FROM %s WHERE account_id = ?)
                    """.formatted(tables.sessionItem(), tables.session()), accountId);
            jdbc.update("DELETE FROM %s WHERE account_id = ?".formatted(tables.session()),
                    accountId);
            jdbc.update("""
                    INSERT INTO %s (account_id, list, %s, created_at_ms) VALUES (?, ?, ?, ?)
                    """.formatted(tables.session(), tables.sessionGroup()), accountId,
                    databaseValue(list), group, nowMillis);
            long sessionId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            List<Long> order = new ArrayList<>(puzzles);
            Collections.shuffle(order, random);
            // A new round never opens with the Puzzle that ended the previous one.
            if (order.size() > 1 && order.getFirst().equals(previous)) {
                Collections.swap(order, 0, 1 + random.nextInt(order.size() - 1));
            }
            List<Object[]> rows = new ArrayList<>(order.size());
            for (int index = 0; index < order.size(); index++) {
                rows.add(new Object[] {sessionId, index + 1, order.get(index)});
            }
            jdbc.batchUpdate("""
                    INSERT INTO %s (session_id, position, puzzle_id) VALUES (?, ?, ?)
                    """.formatted(tables.sessionItem()), rows);
            return Optional.of(new SessionRow(sessionId, list, order.size()));
        });
    }

    public Optional<SessionRow> session(String accountId, long sessionId) {
        return jdbc.query("""
                SELECT s.id, s.list, (SELECT COUNT(*) FROM %s i WHERE i.session_id = s.id)
                FROM %s s WHERE s.id = ? AND s.account_id = ?
                """.formatted(tables.sessionItem(), tables.session()),
                (row, number) -> new SessionRow(
                        row.getLong(1), trainingList(row.getString(2)), row.getInt(3)),
                sessionId, accountId).stream().findFirst();
    }

    /**
     * Marks and returns the session's next unpresented Puzzle that is still in the session's list.
     * A Puzzle that has left the list since the session started is removed from the session, so
     * the session's size shrinks instead of its progress jumping. Empty when every Puzzle has been
     * presented.
     */
    public Optional<SessionItem> presentNext(String accountId, SessionRow session, long nowMillis) {
        return transaction.execute(status -> {
            while (true) {
                SessionItem item = jdbc.query("""
                        SELECT puzzle_id, position FROM %s
                        WHERE session_id = ? AND presented_at_ms IS NULL
                        ORDER BY position LIMIT 1
                        """.formatted(tables.sessionItem()),
                        (row, number) -> new SessionItem(row.getLong(1), row.getInt(2), 0),
                        session.id()).stream().findFirst().orElse(null);
                if (item == null) {
                    return Optional.<SessionItem>empty();
                }
                int claimed = jdbc.update("""
                        UPDATE %s SET presented_at_ms = ?
                        WHERE session_id = ? AND position = ? AND presented_at_ms IS NULL
                        """.formatted(tables.sessionItem()), nowMillis, session.id(),
                        item.position());
                if (claimed != 1) {
                    // A concurrent request presented it; never present one Puzzle twice.
                    continue;
                }
                boolean inList = performance(accountId, item.puzzleId())
                        .map(state -> state.list() == session.list())
                        .orElse(false);
                if (inList) {
                    int presented = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM %s
                            WHERE session_id = ? AND presented_at_ms IS NOT NULL
                            """.formatted(tables.sessionItem()), Integer.class, session.id());
                    // Only Puzzles still in the list will be presented, so only they are counted.
                    int remaining = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM %s i
                            JOIN %s s
                              ON s.account_id = ? AND s.puzzle_id = i.puzzle_id AND s.list = ?
                            WHERE i.session_id = ? AND i.presented_at_ms IS NULL
                            """.formatted(tables.sessionItem(), tables.performance()),
                            Integer.class, accountId, databaseValue(session.list()),
                            session.id());
                    return Optional.of(
                            new SessionItem(item.puzzleId(), presented, presented + remaining));
                }
                jdbc.update("""
                        DELETE FROM %s WHERE session_id = ? AND position = ?
                        """.formatted(tables.sessionItem()), session.id(), item.position());
            }
        });
    }

    static TrainingList trainingList(String value) {
        return TrainingList.valueOf(value.toUpperCase(Locale.ROOT));
    }

    public static String databaseValue(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }
}
