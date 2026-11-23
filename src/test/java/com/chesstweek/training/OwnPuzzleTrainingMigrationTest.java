package com.chesstweek.training;

import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V15 gives Own Puzzles their own account-scoped attempts, list state, discards, and sessions. */
class OwnPuzzleTrainingMigrationTest {

    private static final String FEN =
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3";

    @Test
    void ownPuzzleStateBelongsToTheOwningAccount(@TempDir Path directory) {
        var jdbc = new JdbcTemplate(migrated(directory));
        long mine = ownPuzzle(jdbc, "owner", 5);

        jdbc.update(performance("owner", mine, "retiring"));
        jdbc.update(attempt("owner", mine, "new"));
        // Another account can never hold state on the owner's Own Puzzle.
        for (String foreign : new String[] {
                performance("someone", mine, "retiring"),
                attempt("someone", mine, "new"),
                "INSERT INTO training_own_puzzle_discard VALUES ('someone', %d, 1)".formatted(mine)}) {
            assertThatThrownBy(() -> jdbc.update(foreign))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("FOREIGN KEY constraint failed");
        }
    }

    @Test
    void aDiscardedOwnPuzzleIsNeverClassifiedAndAClassifiedOneIsNeverDiscarded(
            @TempDir Path directory) {
        var jdbc = new JdbcTemplate(migrated(directory));
        long discarded = ownPuzzle(jdbc, "owner", 5);
        long classified = ownPuzzle(jdbc, "owner", 7);

        jdbc.update("INSERT INTO training_own_puzzle_discard VALUES ('owner', ?, 1)", discarded);
        assertThatThrownBy(() -> jdbc.update(performance("owner", discarded, "retiring")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Own Puzzle is discarded");

        jdbc.update(performance("owner", classified, "wrong_moves"));
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO training_own_puzzle_discard VALUES ('owner', ?, 1)", classified))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Own Puzzle is classified");
    }

    @Test
    void refusesUnknownEntriesListsAndSessionGroups(@TempDir Path directory) {
        var jdbc = new JdbcTemplate(migrated(directory));
        long puzzle = ownPuzzle(jdbc, "owner", 5);

        for (String entry : new String[] {"new", "repetition"}) {
            jdbc.update(attempt("owner", puzzle, entry));
        }
        for (String invalid : new String[] {
                attempt("owner", puzzle, "random"),
                attempt("owner", puzzle, "category"),
                performance("owner", puzzle, "lost"),
                session("hard_to_solve", "'phase:endgame'"),
                session("mastered", "NULL"),
                session("mastered", "'colour:white'")}) {
            assertThatThrownBy(() -> jdbc.update(invalid))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("CHECK constraint failed");
        }
        jdbc.update(session("mastered", "'tactic:fork'"));
        jdbc.update(session("retiring", "NULL"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index' AND name IN (
                    'training_own_puzzle_account', 'training_own_puzzle_performance_list',
                    'training_own_puzzle_attempt_account', 'training_own_puzzle_attempt_puzzle',
                    'training_own_puzzle_session_account',
                    'training_own_puzzle_session_item_puzzle')
                """, Integer.class)).isEqualTo(6);
    }

    private static long ownPuzzle(JdbcTemplate jdbc, String account, int ply) {
        jdbc.update("""
                INSERT INTO training_own_puzzle (
                    account_id, game_source, game_id, source_ply, analysis_version_id,
                    position_fen, previous_move_uci, solution_uci, label, phase, opening_eco,
                    opening_name, game_played_at_ms, created_at_ms
                ) VALUES (?, 'lichess', 'abcd1234', ?, 3, ?, NULL, 'f1c4', 'mistake', 'opening',
                          NULL, NULL, 1000, 2000)
                """, account, ply, FEN);
        return jdbc.queryForObject("SELECT max(id) FROM training_own_puzzle", Long.class);
    }

    private static String performance(String account, long puzzle, String list) {
        return """
                INSERT INTO training_own_puzzle_performance VALUES ('%s', %d, '%s', 0, 0, 5, 9)
                """.formatted(account, puzzle, list);
    }

    private static String attempt(String account, long puzzle, String entry) {
        return """
                INSERT INTO training_own_puzzle_attempt (
                    account_id, puzzle_id, entry, status, list_before, next_ply, wrong_moves,
                    hint_used, revealed, fast_limit_ms, started_at_ms
                ) VALUES ('%s', %d, '%s', 'abandoned', NULL, 0, 0, 0, 0, 6000, 200)
                """.formatted(account, puzzle, entry);
    }

    private static String session(String list, String group) {
        return """
                INSERT INTO training_own_puzzle_session (account_id, list, group_key, created_at_ms)
                VALUES ('owner', '%s', %s, 1)
                """.formatted(list, group);
    }

    private static SQLiteDataSource migrated(Path directory) {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("migration.db"));
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return dataSource;
    }
}
