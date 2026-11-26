package com.chesstweek.gamereview;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V6 keys games and analysis versions by source and game ID. Games and analyses stored before it
 * are Lichess games and must carry over unchanged, with their version IDs and moves intact.
 */
class MultiSourceCatalogMigrationTest {

    private static final String INSERT_V5_GAME = """
            INSERT INTO game_review_game (
                account_id, lichess_game_id, played_at_ms, last_move_at_ms, status,
                owner_result, owner_color, owner_rating, owner_rating_change,
                opponent_kind, opponent_user_id, opponent_name, speed, rated, variant,
                opening_eco, opening_name, moves_san, pgn, clocks_json, analysis_eligible,
                retrieved_at_ms
            ) VALUES (
                'owner', ?, 1000, 2000, 'mate',
                'win', 'black', 1500, 7,
                'user', 'rival', 'Rival', 'rapid', 1, 'standard',
                'B12', 'Caro-Kann Defense', 'e4 c6 d4 d5', '1. e4 c6 2. d4 d5', '[600,600]', 1,
                3000
            )
            """;

    private static final String INSERT_V5_VERSION = """
            INSERT INTO game_review_analysis_version (
                id, account_id, lichess_game_id, side, requested_perspective, analyzed_color,
                profile_id, rules_version, phase_source, middlegame_start, endgame_start,
                move_count, deep_move_count, inaccuracies, mistakes, blunders, total_loss,
                started_at_ms, completed_at_ms
            ) VALUES (?, 'owner', ?, 'owner', 'my_moves', 'black', 1, 1, 'inferred', 10, NULL,
                      2, 1, 0, 1, 0, 300, 100, 200)
            """;

    private static final String INSERT_V5_MOVE = """
            INSERT INTO game_review_analysis_move (
                version_id, ply, san, uci, fen_before, phase, pass, label, mate_event,
                played_best, best_move, expected_before, expected_after, loss, after_terminal,
                before_lines_json, after_lines_json
            ) VALUES (?, ?, 'c6', 'c7c6', 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1',
                      'opening', 'deep', 'mistake', 'none', 0, 'e7e5', 1000, 700, 300, 'none',
                      '[]', '[]')
            """;

    private static final String INSERT_GAME = """
            INSERT INTO game_review_game (
                account_id, source, game_id, played_at_ms, last_move_at_ms, status,
                owner_result, owner_color, opponent_kind, opponent_user_id, opponent_name,
                speed, rated, variant, moves_san, clocks_json, analysis_eligible, retrieved_at_ms
            ) VALUES (
                'owner', ?, ?, 0, 0, 'resign', 'loss', 'white', 'user', 'rival', 'Rival',
                'blitz', 1, 'standard', 'e4', '[]', 1, 0
            )
            """;

    @Test
    void carriesLichessGamesAndAnalysesOverUnchanged(@TempDir Path directory) {
        var dataSource = dataSource(directory);
        migrate(dataSource, "5");
        var jdbc = new JdbcTemplate(dataSource);
        insertProfile(jdbc);
        jdbc.update(INSERT_V5_GAME, "ye7Uy9js");
        jdbc.update(INSERT_V5_GAME, "nMp7giWQ");
        jdbc.update(INSERT_V5_VERSION, 4, "ye7Uy9js");
        jdbc.update(INSERT_V5_VERSION, 9, "nMp7giWQ");
        jdbc.update(INSERT_V5_MOVE, 4, 2);
        jdbc.update(INSERT_V5_MOVE, 4, 4);
        jdbc.update(INSERT_V5_MOVE, 9, 2);

        migrate(dataSource, null);

        assertThat(jdbc.queryForList(
                "SELECT source, game_id, owner_rating_change, opening_name, pgn "
                        + "FROM game_review_game ORDER BY game_id"))
                .containsExactly(
                        Map.of("source", "lichess", "game_id", "nMp7giWQ",
                                "owner_rating_change", 7, "opening_name", "Caro-Kann Defense",
                                "pgn", "1. e4 c6 2. d4 d5"),
                        Map.of("source", "lichess", "game_id", "ye7Uy9js",
                                "owner_rating_change", 7, "opening_name", "Caro-Kann Defense",
                                "pgn", "1. e4 c6 2. d4 d5"));
        assertThat(jdbc.queryForList(
                "SELECT id, source, game_id, mistakes FROM game_review_analysis_version ORDER BY id"))
                .containsExactly(
                        Map.of("id", 4, "source", "lichess", "game_id", "ye7Uy9js", "mistakes", 1),
                        Map.of("id", 9, "source", "lichess", "game_id", "nMp7giWQ", "mistakes", 1));
        assertThat(jdbc.queryForList(
                "SELECT version_id, ply FROM game_review_analysis_move ORDER BY version_id, ply"))
                .containsExactly(
                        Map.of("version_id", 4, "ply", 2),
                        Map.of("version_id", 4, "ply", 4),
                        Map.of("version_id", 9, "ply", 2));
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        assertThat(jdbc.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
        assertThat(columns(jdbc, "game_review_game")).doesNotContain("lichess_game_id");
        assertThat(columns(jdbc, "game_review_analysis_version")).doesNotContain("lichess_game_id");

        jdbc.update("""
                INSERT INTO game_review_analysis_version (
                    account_id, source, game_id, side, requested_perspective, analyzed_color,
                    profile_id, rules_version, phase_source, middlegame_start, move_count,
                    deep_move_count, inaccuracies, mistakes, blunders, total_loss,
                    started_at_ms, completed_at_ms
                ) VALUES ('owner', 'lichess', 'ye7Uy9js', 'opponent', 'opponent_moves', 'white',
                          1, 1, 'inferred', 10, 0, 0, 0, 0, 0, 0, 0, 0)
                """);
        assertThat(jdbc.queryForObject(
                "SELECT max(id) FROM game_review_analysis_version", Integer.class)).isEqualTo(10);
    }

    @Test
    void keysGamesBySourceAndKeepsTheStoredEvidenceRules(@TempDir Path directory) {
        var dataSource = dataSource(directory);
        migrate(dataSource, null);
        var jdbc = new JdbcTemplate(dataSource);
        insertProfile(jdbc);

        jdbc.update(INSERT_GAME, "lichess", "12345678");
        jdbc.update(INSERT_GAME, "chesscom", "12345678");
        jdbc.update(INSERT_GAME, "chesscom", "1234567");

        assertThatThrownBy(() -> jdbc.update(INSERT_GAME, "chesscom", "12345678"))
                .hasMessageContaining("UNIQUE");
        assertThatThrownBy(() -> jdbc.update(INSERT_GAME, "chesscom", "12ab5678"))
                .hasMessageContaining("CHECK constraint failed");
        assertThatThrownBy(() -> jdbc.update(INSERT_GAME, "lichess", "abc"))
                .hasMessageContaining("CHECK constraint failed");
        assertThatThrownBy(() -> jdbc.update(INSERT_GAME, "chess.com", "1234567"))
                .hasMessageContaining("CHECK constraint failed");

        jdbc.update("""
                INSERT INTO game_review_analysis_version (
                    account_id, source, game_id, side, requested_perspective, analyzed_color,
                    profile_id, rules_version, phase_source, middlegame_start, move_count,
                    deep_move_count, inaccuracies, mistakes, blunders, total_loss,
                    started_at_ms, completed_at_ms
                ) VALUES ('owner', 'chesscom', '1234567', 'owner', 'my_moves', 'white', 1, 1,
                          'inferred', 10, 0, 0, 0, 0, 0, 0, 0, 0)
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO game_review_analysis_version (
                    account_id, source, game_id, side, requested_perspective, analyzed_color,
                    profile_id, rules_version, phase_source, middlegame_start, move_count,
                    deep_move_count, inaccuracies, mistakes, blunders, total_loss,
                    started_at_ms, completed_at_ms
                ) VALUES ('owner', 'lichess', '1234567', 'owner', 'my_moves', 'white', 1, 1,
                          'inferred', 10, 0, 0, 0, 0, 0, 0, 0, 0)
                """)).hasMessageContaining("CHECK constraint failed");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE game_review_analysis_version SET mistakes = 1"))
                .hasMessageContaining("analysis versions are immutable");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM game_review_game WHERE source = 'chesscom' AND game_id = '1234567'"))
                .hasMessageContaining("FOREIGN KEY constraint failed");
        assertThat(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type IN ('index', 'trigger') "
                        + "AND name NOT LIKE 'sqlite_%' AND tbl_name LIKE 'game_review_%' ORDER BY name",
                String.class))
                .containsExactly(
                        "game_review_analysis_move_immutable",
                        "game_review_analysis_version_current",
                        "game_review_analysis_version_immutable",
                        "game_review_analysis_version_profile",
                        "game_review_game_account_date",
                        "game_review_game_position_key");
    }

    private static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT name FROM pragma_table_info(?)", String.class, table);
    }

    private static void insertProfile(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO game_review_engine_profile (
                    id, engine_name, threads, hash_mb, multi_pv, benchmark_positions,
                    benchmark_nodes_per_position, nodes_per_second, quick_target_ms,
                    deep_target_ms, quick_nodes, deep_nodes, created_at_ms
                ) VALUES (1, 'Stockfish 18', 2, 256, 3, 6, 1000000, 1000000, 750, 3000,
                          750000, 3000000, 0)
                """);
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
