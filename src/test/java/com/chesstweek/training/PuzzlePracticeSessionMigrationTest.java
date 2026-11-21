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

/** V13 rebuilds the attempt table for the new entry points without losing existing attempts. */
class PuzzlePracticeSessionMigrationTest {

    private static final String FEN = "5rk1/5pb1/B1p4p/2p5/8/5RqP/PQ4P1/R5K1 b - - 0 26";

    @Test
    void keepsExistingAttemptsAndAllowsTheNewEntryPoints(@TempDir Path directory) {
        var dataSource = dataSource(directory);
        migrate(dataSource, "12");
        var jdbc = new JdbcTemplate(dataSource);
        long puzzle = insertPuzzle(jdbc);
        jdbc.update("""
                INSERT INTO training_puzzle_performance VALUES ('owner', ?, 'retiring', 2, 1, 5, 9)
                """, puzzle);
        jdbc.update("""
                INSERT INTO training_puzzle_attempt (
                    id, account_id, puzzle_id, entry, status, list_before, next_ply, wrong_moves,
                    hint_used, revealed, fast_limit_ms, started_at_ms, finished_at_ms, elapsed_ms,
                    list_after
                ) VALUES (41, 'owner', ?, 'category', 'completed', 'worth_repeating', 3, 0,
                          0, 0, 11000, 100, 4100, 4000, 'retiring')
                """, puzzle);

        migrate(dataSource, null);

        assertThat(jdbc.queryForObject("""
                SELECT entry || '/' || status || '/' || list_before || '/' || list_after
                       || '/' || elapsed_ms
                FROM training_puzzle_attempt WHERE id = 41
                """, String.class)).isEqualTo("category/completed/worth_repeating/retiring/4000");
        for (String entry : new String[] {"repetition", "method"}) {
            jdbc.update(attempt(entry, "retiring"), puzzle);
        }
        jdbc.update(attempt("mastered_practice", "mastered"), puzzle);
        assertThat(jdbc.queryForList(
                "SELECT id FROM training_puzzle_attempt ORDER BY id", Long.class))
                .containsExactly(41L, 42L, 43L, 44L);

        // Mastered practice starts in Mastered; an unknown entry point is refused.
        assertThatThrownBy(() -> jdbc.update(attempt("mastered_practice", "retiring"), puzzle))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CHECK constraint failed");
        assertThatThrownBy(() -> jdbc.update(attempt("daily", "retiring"), puzzle))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CHECK constraint failed");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index' AND name IN (
                    'training_puzzle_attempt_account', 'training_puzzle_attempt_puzzle',
                    'training_puzzle_performance_list')
                """, Integer.class)).isEqualTo(3);
    }

    private static String attempt(String entry, String listBefore) {
        return """
                INSERT INTO training_puzzle_attempt (
                    account_id, puzzle_id, entry, status, list_before, next_ply, wrong_moves,
                    hint_used, revealed, fast_limit_ms, started_at_ms
                ) VALUES ('owner', ?, '%s', 'abandoned', '%s', 0, 0, 0, 0, 11000, 200)
                """.formatted(entry, listBefore);
    }

    private static long insertPuzzle(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO training_puzzle (
                    provider, external_id, source_fen, source_moves_uci, setup_move_uci,
                    position_fen, solution_uci, game_url, license, created_at_ms
                ) VALUES ('lichess-puzzle-database', 'M0013', ?, 'g3f3 b2g7 g8g7 g2f3', 'g3f3',
                          ?, 'b2g7 g8g7 g2f3', 'https://lichess.org/gameM0013', 'CC0-1.0', 0)
                """, FEN, FEN);
        return jdbc.queryForObject(
                "SELECT id FROM training_puzzle WHERE external_id = 'M0013'", Long.class);
    }

    private static SQLiteDataSource dataSource(Path directory) {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("migration.db"));
        return dataSource;
    }

    private static void migrate(SQLiteDataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
