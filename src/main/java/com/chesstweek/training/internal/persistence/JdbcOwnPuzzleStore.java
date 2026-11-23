package com.chesstweek.training.internal.persistence;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Own Puzzle rows, stored apart from the Lichess Puzzle Library. */
@Repository
public class JdbcOwnPuzzleStore {

    private final JdbcTemplate jdbc;

    JdbcOwnPuzzleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A validated Own Puzzle; {@code solutionUci} is one or three space-separated moves. */
    public record Row(
            String accountId,
            String gameSource,
            String gameId,
            int sourcePly,
            long analysisVersionId,
            String positionFen,
            String previousMoveUci,
            String solutionUci,
            String label,
            String phase,
            String openingEco,
            String openingName,
            long gamePlayedAtMillis,
            List<TacticRow> tactics) {

        public Row {
            tactics = List.copyOf(tactics);
        }
    }

    public record TacticRow(String theme, String kind) {}

    /**
     * Inserts every row whose source position is not stored yet, with its tactic labels, in one
     * transaction. Returns how many rows were new.
     */
    @Transactional
    public int insertNew(List<Row> rows, long createdAtMillis) {
        int created = 0;
        for (Row row : rows) {
            int inserted = jdbc.update("""
                    INSERT INTO training_own_puzzle (
                        account_id, game_source, game_id, source_ply, analysis_version_id,
                        position_fen, previous_move_uci, solution_uci, label, phase, opening_eco,
                        opening_name, game_played_at_ms, created_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (account_id, game_source, game_id, source_ply) DO NOTHING
                    """,
                    row.accountId(), row.gameSource(), row.gameId(), row.sourcePly(),
                    row.analysisVersionId(), row.positionFen(), row.previousMoveUci(),
                    row.solutionUci(), row.label(), row.phase(), row.openingEco(),
                    row.openingName(), row.gamePlayedAtMillis(), createdAtMillis);
            if (inserted == 0) {
                continue;
            }
            created++;
            long id = jdbc.queryForObject("""
                    SELECT id FROM training_own_puzzle
                    WHERE account_id = ? AND game_source = ? AND game_id = ? AND source_ply = ?
                    """, Long.class,
                    row.accountId(), row.gameSource(), row.gameId(), row.sourcePly());
            for (TacticRow tactic : row.tactics()) {
                jdbc.update("""
                        INSERT INTO training_own_puzzle_tactic (own_puzzle_id, theme, kind)
                        VALUES (?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """, id, tactic.theme(), tactic.kind());
            }
        }
        return created;
    }
}
