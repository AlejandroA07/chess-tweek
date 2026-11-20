package com.chesstweek.training;

import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** V12 stores every category's Puzzle count for an existing library. */
class PuzzleCategoryCountsMigrationTest {

    private static final String FEN = "5rk1/5pb1/B1p4p/2p5/8/5RqP/PQ4P1/R5K1 b - - 0 26";

    @Test
    void countsTheMembersOfEveryExistingCategory(@TempDir Path directory) {
        var dataSource = dataSource(directory);
        migrate(dataSource, "11");
        var jdbc = new JdbcTemplate(dataSource);
        insertPuzzle(jdbc, "M0002", "fork mate");
        insertPuzzle(jdbc, "M0003", "fork short");
        int fork = jdbc.queryForObject("""
                SELECT id FROM training_puzzle_category
                WHERE kind = 'theme' AND classification_key = 'fork'
                """, Integer.class);
        for (String externalId : new String[] {"M0002", "M0003"}) {
            jdbc.update("""
                    INSERT INTO training_puzzle_category_member (category_id, puzzle_id)
                    SELECT ?, id FROM training_puzzle WHERE external_id = ?
                    """, fork, externalId);
        }

        migrate(dataSource, null);

        assertThat(count(jdbc, "fork")).isEqualTo(2);
        assertThat(count(jdbc, "pin")).isZero();
    }

    private static int count(JdbcTemplate jdbc, String theme) {
        return jdbc.queryForObject("""
                SELECT puzzle_count FROM training_puzzle_category
                WHERE kind = 'theme' AND classification_key = ?
                """, Integer.class, theme);
    }

    private static void insertPuzzle(JdbcTemplate jdbc, String externalId, String themes) {
        jdbc.update("""
                INSERT INTO training_puzzle (
                    provider, external_id, source_fen, source_moves_uci, setup_move_uci,
                    position_fen, solution_uci, game_url, license, created_at_ms
                ) VALUES ('lichess-puzzle-database', ?, ?, 'g3f3 b2g7 g8g7 g2f3', 'g3f3',
                          ?, 'b2g7 g8g7 g2f3', ?, 'CC0-1.0', 0)
                """, externalId, FEN, FEN, "https://lichess.org/game" + externalId);
        jdbc.update("""
                INSERT INTO training_puzzle_current (
                    puzzle_id, corpus_import_id, rating, rating_deviation, popularity,
                    play_count, source_themes, source_opening_tags, daily_date
                )
                SELECT id, NULL, 1500, 70, 90, 100, ?, '', NULL
                FROM training_puzzle WHERE external_id = ?
                """, themes, externalId);
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
