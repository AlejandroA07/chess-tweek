package com.chesstweek.training.internal.persistence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.chesstweek.training.internal.library.LichessPuzzleRecords;
import com.chesstweek.training.internal.library.PuzzleImportBatch;

/**
 * Maps source Themes and opening tags to compact category IDs and maintains the Puzzle-ID-ordered
 * category membership of a Puzzle's current observation.
 */
@Component
class PuzzleCategoryIndex {

    static final int MAX_OPENING_CLASSIFICATIONS = 20_000;

    private final JdbcTemplate jdbc;
    private final Map<String, Integer> themes = new ConcurrentHashMap<>();
    private final Map<String, Integer> openings = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    PuzzleCategoryIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Clears cached IDs; call after a rolled-back transaction may have registered openings. */
    synchronized void reload() {
        themes.clear();
        openings.clear();
        jdbc.query("SELECT id, kind, classification_key FROM training_puzzle_category", row -> {
            String kind = row.getString(2);
            if (PuzzleImportBatch.Kind.THEME.databaseValue().equals(kind)) {
                themes.put(row.getString(3), row.getInt(1));
            } else {
                openings.putIfAbsent(row.getString(3), row.getInt(1));
            }
        });
        loaded = true;
    }

    Integer category(PuzzleImportBatch.Kind kind, String key) {
        return jdbc.query("""
                SELECT id FROM training_puzzle_category WHERE kind = ? AND classification_key = ?
                """, (row, number) -> row.getInt(1), kind.databaseValue(), key)
                .stream().findFirst().orElse(null);
    }

    /**
     * The category IDs of one current observation. Unknown Themes are ignored. The first opening
     * tag is the family and later tags are its variations; unseen openings are registered in the
     * caller's transaction until the bounded opening catalog is full.
     */
    List<Integer> categories(List<String> sourceThemes, List<String> sourceOpenings, boolean register) {
        ensureLoaded();
        Set<Integer> ids = new LinkedHashSet<>();
        for (String theme : sourceThemes) {
            Integer id = themes.get(theme);
            if (id != null) {
                ids.add(id);
            }
        }
        if (!sourceOpenings.isEmpty()) {
            String family = sourceOpenings.getFirst();
            Integer familyId = opening(family, null, PuzzleImportBatch.Kind.OPENING_FAMILY, register);
            if (familyId != null) {
                ids.add(familyId);
            }
            for (int index = 1; index < sourceOpenings.size(); index++) {
                Integer variation = opening(sourceOpenings.get(index), family,
                        PuzzleImportBatch.Kind.OPENING_VARIATION, register);
                if (variation != null) {
                    ids.add(variation);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Recounts every category's Puzzles in one pass over the membership primary key, then rebuilds
     * the pattern-ladder index and its rating-band counts. Call once after an import changes
     * membership, never on a request.
     */
    void refreshCounts() {
        jdbc.update("""
                UPDATE training_puzzle_category
                SET puzzle_count = (
                    SELECT COUNT(*) FROM training_puzzle_category_member m
                    WHERE m.category_id = training_puzzle_category.id)
                """);
        refreshLadders();
    }

    /**
     * Rebuilds the quality-gated, rating-ordered members of every pattern Theme and their counts
     * per 100-point rating band (migration V17 holds the same definition for existing libraries).
     */
    private void refreshLadders() {
        jdbc.update("DELETE FROM training_puzzle_ladder_band");
        jdbc.update("DELETE FROM training_puzzle_ladder_member");
        jdbc.update("""
                INSERT INTO training_puzzle_ladder_member (category_id, rating, puzzle_id)
                SELECT m.category_id, c.rating, m.puzzle_id
                FROM training_puzzle_category category
                JOIN training_puzzle_classification classification
                  ON classification.kind = category.kind
                 AND classification.classification_key = category.classification_key
                JOIN training_puzzle_category_member m ON m.category_id = category.id
                JOIN training_puzzle_current c ON c.puzzle_id = m.puzzle_id
                WHERE category.kind = 'theme'
                  AND classification.group_name IN (%s)
                  AND c.rating_deviation <= ?
                  AND c.popularity >= ?
                  AND c.play_count >= ?
                """.formatted(LADDER_GROUP_PLACEHOLDERS),
                ladderGateArguments());
        jdbc.update("""
                INSERT INTO training_puzzle_ladder_band (category_id, band, puzzle_count)
                SELECT category_id, rating / 100, COUNT(*)
                FROM training_puzzle_ladder_member
                GROUP BY category_id, rating / 100
                """);
    }

    /** The Theme catalog groups whose Themes are pattern ladders (owner decision, 2026-11-26). */
    static final List<String> LADDER_GROUPS = List.of("Mate themes", "Motifs", "Advanced");
    /** One {@code ?} per ladder group, for an {@code IN (...)} over {@link #LADDER_GROUPS}. */
    static final String LADDER_GROUP_PLACEHOLDERS =
            String.join(", ", java.util.Collections.nCopies(LADDER_GROUPS.size(), "?"));
    /** The guided-course quality gates that every ladder Puzzle meets. */
    static final int LADDER_MAX_RATING_DEVIATION = 90;
    static final int LADDER_MIN_POPULARITY = 80;
    static final int LADDER_MIN_PLAYS = 200;

    private static Object[] ladderGateArguments() {
        List<Object> arguments = new ArrayList<>(LADDER_GROUPS);
        arguments.add(LADDER_MAX_RATING_DEVIATION);
        arguments.add(LADDER_MIN_POPULARITY);
        arguments.add(LADDER_MIN_PLAYS);
        return arguments.toArray();
    }

    void insertMembers(long puzzleId, List<Integer> categories) {
        if (categories.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT OR IGNORE INTO training_puzzle_category_member (category_id, puzzle_id)
                VALUES (?, ?)
                """, categories, categories.size(), (statement, category) -> {
                    statement.setInt(1, category);
                    statement.setLong(2, puzzleId);
                });
    }

    private Integer opening(
            String key, String parent, PuzzleImportBatch.Kind kind, boolean register) {
        Integer id = openings.get(key);
        if (id != null || !register || openings.size() >= MAX_OPENING_CLASSIFICATIONS) {
            return id;
        }
        jdbc.update("""
                INSERT OR IGNORE INTO training_puzzle_classification (
                    kind, classification_key, parent_key, display_name, group_name, drill_oriented
                ) VALUES (?, ?, ?, ?, 'Openings', 1)
                """, kind.databaseValue(), key, parent, LichessPuzzleRecords.displayName(key));
        jdbc.update("""
                INSERT OR IGNORE INTO training_puzzle_category (kind, classification_key)
                VALUES (?, ?)
                """, kind.databaseValue(), key);
        Integer created = jdbc.query("""
                SELECT id FROM training_puzzle_category
                WHERE classification_key = ? AND kind LIKE 'opening_%'
                ORDER BY id LIMIT 1
                """, (row, number) -> row.getInt(1), key).stream().findFirst().orElse(null);
        if (created != null) {
            openings.put(key, created);
        }
        return created;
    }

    private void ensureLoaded() {
        if (!loaded) {
            reload();
        }
    }
}
