package com.chesstweek.training.internal.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** One stored Own Puzzle's source and tactic labels. */
    public record LabelRow(
            String accountId, String gameSource, String gameId, int sourcePly, long analysisVersionId,
            List<TacticRow> tactics) {

        public LabelRow {
            tactics = List.copyOf(tactics);
        }
    }

    /** Every Own Puzzle with its tactic labels, in source order. */
    public List<LabelRow> labels() {
        record Flat(long id, LabelRow puzzle, TacticRow tactic) {}
        List<Flat> flat = jdbc.query("""
                SELECT p.id, p.account_id, p.game_source, p.game_id, p.source_ply, p.analysis_version_id,
                       t.theme, t.kind
                FROM training_own_puzzle p
                LEFT JOIN training_own_puzzle_tactic t ON t.own_puzzle_id = p.id
                ORDER BY p.account_id, p.game_source, p.game_id, p.source_ply, t.theme, t.kind
                """, (row, index) -> new Flat(
                        row.getLong(1),
                        new LabelRow(row.getString(2), row.getString(3), row.getString(4), row.getInt(5),
                                row.getLong(6), List.of()),
                        row.getString(7) == null ? null : new TacticRow(row.getString(7), row.getString(8))));
        Map<Long, LabelRow> puzzles = new LinkedHashMap<>();
        Map<Long, List<TacticRow>> tactics = new HashMap<>();
        for (Flat row : flat) {
            puzzles.putIfAbsent(row.id(), row.puzzle());
            List<TacticRow> labels = tactics.computeIfAbsent(row.id(), ignored -> new ArrayList<>());
            if (row.tactic() != null) {
                labels.add(row.tactic());
            }
        }
        return puzzles.entrySet().stream()
                .map(entry -> {
                    LabelRow row = entry.getValue();
                    return new LabelRow(row.accountId(), row.gameSource(), row.gameId(), row.sourcePly(),
                            row.analysisVersionId(), tactics.get(entry.getKey()));
                })
                .toList();
    }

    /** The tactic labels of the Own Puzzle at one source position; empty when none is stored. */
    public List<TacticRow> tactics(String accountId, String gameSource, String gameId, int sourcePly) {
        return jdbc.query("""
                SELECT t.theme, t.kind
                FROM training_own_puzzle_tactic t
                JOIN training_own_puzzle p ON p.id = t.own_puzzle_id
                WHERE p.account_id = ? AND p.game_source = ? AND p.game_id = ? AND p.source_ply = ?
                """, (row, index) -> new TacticRow(row.getString(1), row.getString(2)),
                accountId, gameSource, gameId, sourcePly);
    }

    /**
     * Replaces every tactic label of the Own Puzzle at one source position in one transaction.
     * Returns false when no such puzzle is stored.
     */
    @Transactional
    public boolean replaceTactics(
            String accountId, String gameSource, String gameId, int sourcePly, List<TacticRow> tactics) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM training_own_puzzle
                WHERE account_id = ? AND game_source = ? AND game_id = ? AND source_ply = ?
                """, Long.class, accountId, gameSource, gameId, sourcePly);
        if (ids.isEmpty()) {
            return false;
        }
        long id = ids.getFirst();
        jdbc.update("DELETE FROM training_own_puzzle_tactic WHERE own_puzzle_id = ?", id);
        for (TacticRow tactic : tactics) {
            jdbc.update("""
                    INSERT INTO training_own_puzzle_tactic (own_puzzle_id, theme, kind)
                    VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, id, tactic.theme(), tactic.kind());
        }
        return true;
    }
}
