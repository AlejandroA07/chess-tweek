package com.chesstweek.training;

import java.nio.file.Path;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** V17 builds the pattern-ladder index and band counts of an existing library. */
class PatternLadderMigrationTest {

    private static final String FEN = "5rk1/5pb1/B1p4p/2p5/8/5RqP/PQ4P1/R5K1 b - - 0 26";

    @Test
    void indexesOnlyQualityGatedMembersOfPatternThemes(@TempDir Path directory) {
        var dataSource = dataSource(directory);
        migrate(dataSource, "16");
        var jdbc = new JdbcTemplate(dataSource);
        insertPuzzle(jdbc, "L0001", 1250, 80, 90, 300, "fork");
        insertPuzzle(jdbc, "L0002", 1299, 90, 80, 200, "fork");
        insertPuzzle(jdbc, "L0003", 1700, 60, 95, 900, "operaMate");
        // Below each gate in turn.
        insertPuzzle(jdbc, "L0004", 1250, 91, 90, 300, "fork");
        insertPuzzle(jdbc, "L0005", 1250, 80, 79, 300, "fork");
        insertPuzzle(jdbc, "L0006", 1250, 80, 90, 199, "fork");
        // A quality Puzzle in a Theme outside the pattern groups.
        insertPuzzle(jdbc, "L0007", 1250, 80, 90, 300, "mateIn1");

        migrate(dataSource, null);

        assertThat(jdbc.queryForList("""
                SELECT p.external_id FROM training_puzzle_ladder_member l
                JOIN training_puzzle p ON p.id = l.puzzle_id
                ORDER BY l.category_id, l.rating, l.puzzle_id
                """, String.class)).containsExactlyInAnyOrder("L0001", "L0002", "L0003");
        assertThat(jdbc.queryForList("""
                SELECT category.classification_key || ':' || band.band || ':' || band.puzzle_count
                FROM training_puzzle_ladder_band band
                JOIN training_puzzle_category category ON category.id = band.category_id
                ORDER BY 1
                """, String.class)).isEqualTo(List.of("fork:12:2", "operaMate:17:1"));
    }

    private static void insertPuzzle(
            JdbcTemplate jdbc, String externalId, int rating, int deviation, int popularity,
            int plays, String theme) {
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
                SELECT id, NULL, ?, ?, ?, ?, ?, '', NULL
                FROM training_puzzle WHERE external_id = ?
                """, rating, deviation, popularity, plays, theme, externalId);
        jdbc.update("""
                INSERT INTO training_puzzle_category_member (category_id, puzzle_id)
                SELECT category.id, puzzle.id
                FROM training_puzzle_category category, training_puzzle puzzle
                WHERE category.kind = 'theme' AND category.classification_key = ?
                  AND puzzle.external_id = ?
                """, theme, externalId);
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
