package com.chesstweek.training;

import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V16 holds one row: the tactic-rules version the stored Own Puzzle labels were refreshed with. */
class OwnPuzzleLabelRulesMigrationTest {

    @Test
    void holdsOnePositiveRulesVersion(@TempDir Path directory) {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("migration.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM training_own_puzzle_label_rules", Integer.class))
                .isZero();
        jdbc.update("INSERT INTO training_own_puzzle_label_rules (singleton, rules_version) VALUES (1, 2)");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO training_own_puzzle_label_rules (singleton, rules_version) VALUES (2, 2)"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE training_own_puzzle_label_rules SET rules_version = 0"))
                .isInstanceOf(DataAccessException.class);
    }
}
