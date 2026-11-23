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

/** V14 stores Own Puzzles apart from the Lichess Puzzle Library, one per source game position. */
class OwnPuzzleMigrationTest {

    private static final String FEN =
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3";

    @Test
    void storesOneOwnPuzzlePerAccountGameAndPly(@TempDir Path directory) {
        var jdbc = new JdbcTemplate(migrated(directory));

        jdbc.update(insert("owner", "lichess", "abcd1234", 5, "'e7e5'", "f1c4 g8f6 d2d3", "mistake"));
        long id = jdbc.queryForObject("SELECT id FROM training_own_puzzle", Long.class);
        jdbc.update("INSERT INTO training_own_puzzle_tactic VALUES (?, 'fork', 'missed')", id);

        // The same position from the same game is a duplicate; another account or game is not.
        assertThatThrownBy(() -> jdbc.update(
                insert("owner", "lichess", "abcd1234", 5, "NULL", "f1c4", "blunder")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed");
        jdbc.update(insert("someone", "lichess", "abcd1234", 5, "NULL", "f1c4", "blunder"));
        jdbc.update(insert("owner", "chesscom", "123456", 5, "NULL", "f1c4", "inaccuracy"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM training_own_puzzle", Integer.class))
                .isEqualTo(3);

        // A puzzle's tactic labels go with it.
        jdbc.update("DELETE FROM training_own_puzzle WHERE id = ?", id);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM training_own_puzzle_tactic", Integer.class)).isZero();
    }

    @Test
    void refusesInvalidOwnPuzzles(@TempDir Path directory) {
        var jdbc = new JdbcTemplate(migrated(directory));

        // Only labelled moves, one or three solution moves, and known sources are stored.
        for (String invalid : new String[] {
                insert("owner", "lichess", "abcd1234", 5, "NULL", "f1c4", "none"),
                insert("owner", "lichess", "abcd1234", 5, "NULL", "f1c4 g8f6", "mistake"),
                insert("owner", "lichess", "abcd1234", 5, "NULL", "", "mistake"),
                insert("owner", "elsewhere", "abcd1234", 5, "NULL", "f1c4", "mistake"),
                insert("owner", "lichess", "abcd1234", 0, "NULL", "f1c4", "mistake"),
                insert("", "lichess", "abcd1234", 5, "NULL", "f1c4", "mistake")}) {
            assertThatThrownBy(() -> jdbc.update(invalid))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("CHECK constraint failed");
        }
        jdbc.update(insert("owner", "lichess", "abcd1234", 5, "NULL", "f1c4", "mistake"));
        long id = jdbc.queryForObject("SELECT id FROM training_own_puzzle", Long.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO training_own_puzzle_tactic VALUES (?, 'fork', 'maybe')", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CHECK constraint failed");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO training_own_puzzle_tactic VALUES (999, 'fork', 'missed')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("FOREIGN KEY constraint failed");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index' AND name IN (
                    'training_own_puzzle_newest', 'training_own_puzzle_phase')
                """, Integer.class)).isEqualTo(2);
    }

    private static String insert(
            String account, String source, String game, int ply, String previous, String solution,
            String label) {
        return """
                INSERT INTO training_own_puzzle (
                    account_id, game_source, game_id, source_ply, analysis_version_id,
                    position_fen, previous_move_uci, solution_uci, label, phase, opening_eco,
                    opening_name, game_played_at_ms, created_at_ms
                ) VALUES ('%s', '%s', '%s', %d, 3, '%s', %s, '%s', '%s', 'opening', 'C50',
                          'Italian Game', 1000, 2000)
                """.formatted(account, source, game, ply, FEN, previous, solution, label);
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
