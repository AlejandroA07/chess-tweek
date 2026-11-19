package com.chesstweek.training.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.internal.library.PuzzleImportBatch;

@Repository
public class JdbcPuzzleLibraryStore {

    static final String PUZZLE_COLUMNS = """
            p.id, p.provider, p.external_id, p.source_fen, p.source_moves_uci, p.setup_move_uci,
            p.position_fen, p.solution_uci, p.game_url, p.license,
            c.rating, c.rating_deviation, c.popularity, c.play_count,
            c.source_themes, c.source_opening_tags,
            COALESCE(i.snapshot_date, (
                SELECT s.snapshot_date
                FROM training_puzzle_observation o
                JOIN training_puzzle_snapshot s ON s.id = o.snapshot_id
                WHERE o.puzzle_id = p.id
                ORDER BY s.snapshot_date DESC
                LIMIT 1
            )) AS snapshot_date
            """;
    static final String CURRENT_PUZZLE = """
            FROM training_puzzle_current c
            JOIN training_puzzle p ON p.id = c.puzzle_id
            LEFT JOIN training_puzzle_corpus_import i ON i.id = c.corpus_import_id
            """;

    private final JdbcTemplate jdbc;
    private final PuzzleCategoryIndex categories;

    public JdbcPuzzleLibraryStore(JdbcTemplate jdbc, PuzzleCategoryIndex categories) {
        this.jdbc = jdbc;
        this.categories = categories;
    }

    @Transactional
    public PuzzleLibrary.ImportResult persist(PuzzleImportBatch batch) {
        try {
            return persistBatch(batch);
        } catch (RuntimeException exception) {
            categories.reload();
            throw exception;
        }
    }

    private PuzzleLibrary.ImportResult persistBatch(PuzzleImportBatch batch) {
        Long existingSnapshot = snapshotId(batch.snapshot());
        if (existingSnapshot != null) {
            String checksum = jdbc.queryForObject(
                    "SELECT download_checksum FROM training_puzzle_snapshot WHERE id = ?",
                    String.class,
                    existingSnapshot);
            if (!java.util.Objects.equals(checksum, batch.snapshot().downloadChecksum())) {
                throw new IllegalStateException("A different Puzzle Library snapshot already uses this date");
            }
            return result(PuzzleLibrary.ImportStatus.ALREADY_IMPORTED, existingSnapshot);
        }

        jdbc.update("""
                INSERT INTO training_puzzle_snapshot (
                    provider, snapshot_date, download_checksum, importer_version, imported_at_ms
                ) VALUES (?, ?, ?, ?, ?)
                """,
                batch.snapshot().provider(), batch.snapshot().snapshotDate().toString(),
                batch.snapshot().downloadChecksum(), batch.snapshot().importerVersion(),
                batch.snapshot().importedAtMs());
        long snapshotId = java.util.Objects.requireNonNull(snapshotId(batch.snapshot()));

        for (PuzzleImportBatch.Classification classification : batch.classifications()) {
            jdbc.update("""
                    INSERT OR IGNORE INTO training_puzzle_classification (
                        kind, classification_key, parent_key, display_name, group_name, drill_oriented
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    classification.kind().databaseValue(), classification.key(),
                    classification.parentKey(), classification.displayName(),
                    classification.groupName(), classification.drillOriented() ? 1 : 0);
        }

        Map<String, Long> puzzleIds = new HashMap<>();
        for (PuzzleImportBatch.PuzzleRow puzzle : batch.puzzles()) {
            Long puzzleId = findPuzzleId(puzzle.provider(), puzzle.externalId());
            if (puzzleId == null) {
                jdbc.update("""
                        INSERT INTO training_puzzle (
                            provider, external_id, source_fen, source_moves_uci, setup_move_uci,
                            position_fen, solution_uci, game_url, license, created_at_ms
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        puzzle.provider(), puzzle.externalId(), puzzle.sourceFen(),
                        join(puzzle.sourceMoves()), puzzle.setupMove(), puzzle.positionFen(),
                        join(puzzle.solutionMoves()), puzzle.gameUrl(), puzzle.license(),
                        batch.snapshot().importedAtMs());
                puzzleId = findPuzzleId(puzzle.provider(), puzzle.externalId());
            } else {
                assertImmutable(puzzleId, puzzle);
            }
            puzzleIds.put(identity(puzzle.provider(), puzzle.externalId()), puzzleId);
            jdbc.update("""
                    INSERT INTO training_puzzle_observation (
                        puzzle_id, snapshot_id, rating, rating_deviation, popularity, play_count,
                        source_themes, source_opening_tags, daily_date
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    puzzleId, snapshotId, puzzle.rating(), puzzle.ratingDeviation(),
                    puzzle.popularity(), puzzle.playCount(), join(puzzle.themes()),
                    join(puzzle.openingTags()),
                    puzzle.dailyDate() == null ? null : puzzle.dailyDate().toString());
            for (String theme : puzzle.themes()) {
                if (classificationExists(PuzzleImportBatch.Kind.THEME, theme)) {
                    insertTag(puzzleId, snapshotId, PuzzleImportBatch.Kind.THEME, theme);
                }
            }
            for (String opening : puzzle.openingTags()) {
                Optional<PuzzleImportBatch.Kind> kind = classificationKind(opening);
                if (kind.isPresent()) {
                    insertTag(puzzleId, snapshotId, kind.get(), opening);
                }
            }
            ensureCurrent(puzzleId, puzzle);
        }

        for (PuzzleImportBatch.Collection collection : batch.collections()) {
            jdbc.update("""
                    INSERT INTO training_puzzle_collection (
                        snapshot_id, kind, classification_key, display_name,
                        target_count, actual_count, available
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    snapshotId, collection.kind().databaseValue(), collection.classificationKey(),
                    collection.displayName(), collection.targetCount(), collection.members().size(),
                    collection.members().size() >= 20 ? 1 : 0);
            long collectionId = jdbc.queryForObject("""
                    SELECT id FROM training_puzzle_collection
                    WHERE snapshot_id = ? AND kind = ? AND classification_key = ?
                    """, Long.class, snapshotId, collection.kind().databaseValue(),
                    collection.classificationKey());
            int ordinal = 1;
            for (PuzzleImportBatch.Member member : collection.members()) {
                Long puzzleId = puzzleIds.get(identity(member.provider(), member.externalId()));
                if (puzzleId == null) {
                    throw new IllegalStateException("Puzzle collection references missing material");
                }
                jdbc.update("""
                        INSERT INTO training_puzzle_collection_member (
                            collection_id, puzzle_id, ordinal, selection_score
                        ) VALUES (?, ?, ?, ?)
                        """, collectionId, puzzleId, ordinal++, member.selectionScore());
            }
        }

        for (PuzzleImportBatch.ExerciseReference reference : batch.exerciseReferences()) {
            Long puzzleId = puzzleIds.get(identity(reference.provider(), reference.externalId()));
            if (puzzleId == null) {
                puzzleId = findPuzzleId(reference.provider(), reference.externalId());
            }
            if (puzzleId == null) {
                throw new IllegalStateException("Course exercise references missing puzzle material");
            }
            jdbc.update("""
                    INSERT INTO training_method_exercise_puzzle (
                        method_key, content_version, exercise_key, puzzle_id, snapshot_id
                    ) VALUES (?, ?, ?, ?, ?)
                    """, reference.methodKey(), reference.contentVersion(), reference.exerciseKey(),
                    puzzleId, snapshotId);
        }
        return result(PuzzleLibrary.ImportStatus.IMPORTED, snapshotId);
    }

    public Optional<PuzzleLibrary.Puzzle> puzzle(long puzzleId) {
        return jdbc.query("SELECT " + PUZZLE_COLUMNS + CURRENT_PUZZLE + "WHERE c.puzzle_id = ?",
                JdbcPuzzleLibraryStore::mapPuzzle, puzzleId).stream().findFirst();
    }

    public Optional<PuzzleLibrary.Puzzle> puzzle(String provider, String externalId) {
        return jdbc.query("SELECT " + PUZZLE_COLUMNS + CURRENT_PUZZLE
                        + "WHERE p.provider = ? AND p.external_id = ?",
                JdbcPuzzleLibraryStore::mapPuzzle, provider, externalId).stream().findFirst();
    }

    /**
     * Picks a uniform point in the stored Puzzle ID range and seeks the first Puzzle at or after
     * it. Both bounds and the seek use the primary key; the corpus is never sorted or scanned.
     */
    public Optional<PuzzleLibrary.Puzzle> randomPuzzle(RandomGenerator random) {
        Long low = firstId("SELECT min(puzzle_id) FROM training_puzzle_current");
        Long high = firstId("SELECT max(puzzle_id) FROM training_puzzle_current");
        if (low == null || high == null) {
            return Optional.empty();
        }
        Long puzzleId = firstId("""
                SELECT puzzle_id FROM training_puzzle_current
                WHERE puzzle_id >= ? ORDER BY puzzle_id LIMIT 1
                """, random.nextLong(low, high + 1));
        return puzzleId == null ? Optional.empty() : puzzle(puzzleId);
    }

    /** The same uniform seek within one category's Puzzle ID range on its member primary key. */
    public Optional<PuzzleLibrary.Puzzle> randomPuzzle(
            PuzzleImportBatch.Kind kind, String key, RandomGenerator random) {
        Integer category = categories.category(kind, key);
        if (category == null) {
            return Optional.empty();
        }
        Long low = firstId(
                "SELECT min(puzzle_id) FROM training_puzzle_category_member WHERE category_id = ?",
                category);
        Long high = firstId(
                "SELECT max(puzzle_id) FROM training_puzzle_category_member WHERE category_id = ?",
                category);
        if (low == null || high == null) {
            return Optional.empty();
        }
        Long puzzleId = firstId("""
                SELECT puzzle_id FROM training_puzzle_category_member
                WHERE category_id = ? AND puzzle_id >= ?
                ORDER BY puzzle_id LIMIT 1
                """, category, random.nextLong(low, high + 1));
        return puzzleId == null ? Optional.empty() : puzzle(puzzleId);
    }

    public Optional<PuzzleLibrary.Puzzle> exercisePuzzle(
            String methodKey, int contentVersion, String exerciseKey) {
        return jdbc.query("""
                SELECT p.*, s.snapshot_date, o.rating, o.rating_deviation,
                       o.popularity, o.play_count, o.source_themes, o.source_opening_tags
                FROM training_method_exercise_puzzle r
                JOIN training_puzzle p ON p.id = r.puzzle_id
                JOIN training_puzzle_observation o
                  ON o.puzzle_id = r.puzzle_id AND o.snapshot_id = r.snapshot_id
                JOIN training_puzzle_snapshot s ON s.id = r.snapshot_id
                WHERE r.method_key = ? AND r.content_version = ? AND r.exercise_key = ?
                """, JdbcPuzzleLibraryStore::mapPuzzle,
                methodKey, contentVersion, exerciseKey).stream().findFirst();
    }

    public PuzzleLibrary.CatalogSummary summary() {
        return new PuzzleLibrary.CatalogSummary(
                count("SELECT COUNT(*) FROM training_puzzle"),
                count("SELECT COUNT(*) FROM training_puzzle_classification WHERE kind = 'theme'"),
                count("SELECT COUNT(*) FROM training_puzzle_classification WHERE kind LIKE 'opening_%'"),
                count("SELECT COUNT(*) FROM training_puzzle_collection"),
                count("SELECT COUNT(*) FROM training_puzzle_collection WHERE available = 1"),
                databaseBytes());
    }

    public Map<String, String> themeNames() {
        return Map.copyOf(jdbc.query(
                """
                SELECT classification_key, display_name
                FROM training_puzzle_classification WHERE kind = 'theme'
                """,
                (row, number) -> Map.entry(row.getString(1), row.getString(2))).stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private PuzzleLibrary.ImportResult result(PuzzleLibrary.ImportStatus status, long snapshotId) {
        return new PuzzleLibrary.ImportResult(
                status,
                snapshotId,
                count("SELECT COUNT(*) FROM training_puzzle_observation WHERE snapshot_id = " + snapshotId),
                count("SELECT COUNT(*) FROM training_puzzle_classification"),
                count("SELECT COUNT(*) FROM training_puzzle_collection WHERE snapshot_id = " + snapshotId),
                count("SELECT COUNT(*) FROM training_puzzle_collection WHERE snapshot_id = "
                        + snapshotId + " AND available = 1"),
                databaseBytes());
    }

    private long databaseBytes() {
        long pages = jdbc.queryForObject("PRAGMA page_count", Long.class);
        long pageSize = jdbc.queryForObject("PRAGMA page_size", Long.class);
        return pages * pageSize;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private Long snapshotId(PuzzleImportBatch.Snapshot snapshot) {
        return jdbc.query("""
                SELECT id FROM training_puzzle_snapshot WHERE provider = ? AND snapshot_date = ?
                """, (row, number) -> row.getLong(1),
                snapshot.provider(), snapshot.snapshotDate().toString()).stream().findFirst().orElse(null);
    }

    private Long findPuzzleId(String provider, String externalId) {
        return jdbc.query("SELECT id FROM training_puzzle WHERE provider = ? AND external_id = ?",
                (row, number) -> row.getLong(1), provider, externalId)
                .stream().findFirst().orElse(null);
    }

    private void assertImmutable(long puzzleId, PuzzleImportBatch.PuzzleRow puzzle) {
        int matches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM training_puzzle
                WHERE id = ? AND provider = ? AND external_id = ? AND source_fen = ?
                  AND source_moves_uci = ? AND setup_move_uci = ? AND position_fen = ?
                  AND solution_uci = ? AND game_url = ? AND license = ?
                """, Integer.class,
                puzzleId, puzzle.provider(), puzzle.externalId(), puzzle.sourceFen(),
                join(puzzle.sourceMoves()), puzzle.setupMove(), puzzle.positionFen(),
                join(puzzle.solutionMoves()), puzzle.gameUrl(), puzzle.license());
        if (matches != 1) {
            throw new IllegalStateException("A stable puzzle ID has conflicting source material");
        }
    }

    private void ensureCurrent(long puzzleId, PuzzleImportBatch.PuzzleRow puzzle) {
        if (firstId("SELECT puzzle_id FROM training_puzzle_current WHERE puzzle_id = ?",
                puzzleId) != null) {
            return;
        }
        jdbc.update("""
                INSERT INTO training_puzzle_current (
                    puzzle_id, corpus_import_id, rating, rating_deviation, popularity,
                    play_count, source_themes, source_opening_tags, daily_date
                ) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?)
                """, puzzleId, puzzle.rating(), puzzle.ratingDeviation(),
                puzzle.popularity(), puzzle.playCount(), join(puzzle.themes()),
                join(puzzle.openingTags()),
                puzzle.dailyDate() == null ? null : puzzle.dailyDate().toString());
        categories.insertMembers(puzzleId,
                categories.categories(puzzle.themes(), puzzle.openingTags(), true));
    }

    private Long firstId(String sql, Object... arguments) {
        return jdbc.query(sql, (row, number) -> row.getLong(1), arguments)
                .stream().findFirst().orElse(null);
    }

    private boolean classificationExists(PuzzleImportBatch.Kind kind, String key) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM training_puzzle_classification
                WHERE kind = ? AND classification_key = ?
                """, Integer.class, kind.databaseValue(), key) == 1;
    }

    private Optional<PuzzleImportBatch.Kind> classificationKind(String key) {
        return jdbc.query("""
                SELECT kind FROM training_puzzle_classification
                WHERE classification_key = ? AND kind LIKE 'opening_%'
                """, (row, number) -> switch (row.getString(1)) {
                    case "opening_family" -> PuzzleImportBatch.Kind.OPENING_FAMILY;
                    case "opening_variation" -> PuzzleImportBatch.Kind.OPENING_VARIATION;
                    default -> throw new IllegalStateException("Unknown Puzzle Library classification");
                }, key).stream().findFirst();
    }

    private void insertTag(long puzzleId, long snapshotId, PuzzleImportBatch.Kind kind, String key) {
        jdbc.update("""
                INSERT INTO training_puzzle_tag (
                    puzzle_id, snapshot_id, kind, classification_key
                ) VALUES (?, ?, ?, ?)
                """, puzzleId, snapshotId, kind.databaseValue(), key);
    }

    private static PuzzleLibrary.Puzzle mapPuzzle(ResultSet row, int rowNumber) throws SQLException {
        return new PuzzleLibrary.Puzzle(
                row.getLong("id"), row.getString("provider"), row.getString("external_id"),
                row.getString("source_fen"), split(row.getString("source_moves_uci")),
                row.getString("setup_move_uci"), row.getString("position_fen"),
                split(row.getString("solution_uci")), row.getString("game_url"),
                row.getString("license"), java.time.LocalDate.parse(row.getString("snapshot_date")),
                row.getInt("rating"), row.getInt("rating_deviation"),
                row.getInt("popularity"), row.getInt("play_count"),
                split(row.getString("source_themes")), split(row.getString("source_opening_tags")));
    }

    private static String join(List<String> values) {
        return String.join(" ", values);
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(" "));
    }

    private static String identity(String provider, String externalId) {
        return provider + '\u0000' + externalId;
    }
}
