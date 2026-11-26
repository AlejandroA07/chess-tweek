package com.chesstweek.training.internal.persistence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chesstweek.training.PatternLadders.Pattern;
import com.chesstweek.training.PatternLadders.Progress;
import com.chesstweek.training.PatternLadders.RatingPackage;

/**
 * The pattern-ladder index (migration V17) and each account's place in it. The index is rebuilt
 * by {@link PuzzleCategoryIndex#refreshCounts()} after every import; requests only seek it on its
 * primary key and read stored band counts, so they never sort or count a category.
 */
@Repository
public class JdbcPatternLadderStore {

    private static final String PATTERN_CATEGORY = """
            FROM training_puzzle_category category
            JOIN training_puzzle_classification classification
              ON classification.kind = category.kind
             AND classification.classification_key = category.classification_key
            WHERE category.kind = 'theme'
              AND classification.group_name IN (%s)
            """.formatted(PuzzleCategoryIndex.LADDER_GROUP_PLACEHOLDERS);

    private final JdbcTemplate jdbc;

    JdbcPatternLadderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A pattern Theme's category ID, or empty for any other key. */
    public Optional<PatternCategory> pattern(String key) {
        List<Object> arguments = new ArrayList<>(PuzzleCategoryIndex.LADDER_GROUPS);
        arguments.add(key);
        return jdbc.query("""
                SELECT category.id, category.classification_key, classification.display_name,
                       classification.group_name
                """ + PATTERN_CATEGORY + " AND category.classification_key = ?",
                (row, number) -> new PatternCategory(row.getInt(1), new Pattern(
                        row.getString(2), row.getString(3), row.getString(4),
                        total(row.getInt(1)))),
                arguments.toArray()).stream().findFirst();
    }

    /**
     * Every pattern Theme with at least one ladder Puzzle, with its ladder size, ordered by group
     * name and display name; the interface arranges the groups in Themes page order.
     */
    public List<Pattern> patterns() {
        return jdbc.query("""
                SELECT category.classification_key, classification.display_name,
                       classification.group_name, sum(band.puzzle_count)
                FROM training_puzzle_category category
                JOIN training_puzzle_classification classification
                  ON classification.kind = category.kind
                 AND classification.classification_key = category.classification_key
                JOIN training_puzzle_ladder_band band ON band.category_id = category.id
                WHERE category.kind = 'theme'
                  AND classification.group_name IN (%s)
                GROUP BY category.id
                ORDER BY classification.group_name, classification.display_name
                """.formatted(PuzzleCategoryIndex.LADDER_GROUP_PLACEHOLDERS),
                (row, number) -> new Pattern(
                        row.getString(1), row.getString(2), row.getString(3), row.getInt(4)),
                PuzzleCategoryIndex.LADDER_GROUPS.toArray());
    }

    /** The number of ladder Puzzles in each rating package of one pattern. */
    public Map<RatingPackage, Integer> packageSizes(int categoryId) {
        Map<RatingPackage, Integer> sizes = new EnumMap<>(RatingPackage.class);
        for (RatingPackage ratingPackage : RatingPackage.values()) {
            sizes.put(ratingPackage, 0);
        }
        jdbc.query("""
                SELECT band, puzzle_count FROM training_puzzle_ladder_band WHERE category_id = ?
                """, row -> {
                    RatingPackage ratingPackage = packageOf(row.getInt(1) * 100);
                    sizes.merge(ratingPackage, row.getInt(2), Integer::sum);
                }, categoryId);
        return sizes;
    }

    /** The account's place in every package of one pattern it has started. */
    public Map<RatingPackage, Progress> progress(String accountId, int categoryId) {
        Map<RatingPackage, Progress> progress = new EnumMap<>(RatingPackage.class);
        jdbc.query("""
                SELECT rating_package, presented, last_rating FROM training_puzzle_ladder_progress
                WHERE account_id = ? AND category_id = ?
                """, row -> {
                    RatingPackage ratingPackage = RatingPackage.fromKey(row.getString(1)).orElse(null);
                    if (ratingPackage != null) {
                        progress.put(ratingPackage, new Progress(row.getInt(2), row.getInt(3)));
                    }
                }, accountId, categoryId);
        return progress;
    }

    /**
     * Finds the package's first Puzzle after the account's place that is still current and not
     * Mastered for the account, and moves the place there. Empty when none remains; the place is
     * then unchanged.
     */
    @Transactional
    public Optional<Advance> advance(
            String accountId, int categoryId, RatingPackage ratingPackage, long nowMillis) {
        Cursor cursor = jdbc.query("""
                SELECT last_rating, last_puzzle_id, presented FROM training_puzzle_ladder_progress
                WHERE account_id = ? AND category_id = ? AND rating_package = ?
                """, (row, number) -> new Cursor(row.getInt(1), row.getLong(2), row.getInt(3)),
                accountId, categoryId, ratingPackage.key()).stream().findFirst()
                .orElse(new Cursor(ratingPackage.low(), 0, 0));
        List<Found> found = jdbc.query("""
                SELECT l.rating, l.puzzle_id FROM training_puzzle_ladder_member l
                WHERE l.category_id = ? AND l.rating >= ? AND l.rating < ?
                  AND (l.rating > ? OR l.puzzle_id > ?)
                  AND EXISTS (
                      SELECT 1 FROM training_puzzle_current c WHERE c.puzzle_id = l.puzzle_id)
                  AND NOT EXISTS (
                      SELECT 1 FROM training_puzzle_performance s
                      WHERE s.account_id = ? AND s.puzzle_id = l.puzzle_id
                        AND s.list = 'mastered')
                ORDER BY l.rating, l.puzzle_id LIMIT 1
                """, (row, number) -> new Found(row.getInt(1), row.getLong(2)),
                categoryId, Math.max(ratingPackage.low(), cursor.rating()), ratingPackage.high(),
                cursor.rating(), cursor.puzzleId(), accountId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int rating = found.getFirst().rating();
        long puzzleId = found.getFirst().puzzleId();
        int presented = cursor.presented() + 1;
        saveProgress(accountId, categoryId, ratingPackage, found.getFirst(), presented, nowMillis);
        return Optional.of(new Advance(puzzleId, new Progress(presented, rating)));
    }

    /** Forgets the account's place in one package. */
    public void restart(String accountId, int categoryId, RatingPackage ratingPackage) {
        jdbc.update("""
                DELETE FROM training_puzzle_ladder_progress
                WHERE account_id = ? AND category_id = ? AND rating_package = ?
                """, accountId, categoryId, ratingPackage.key());
    }

    /**
     * Moves the account's place so that the package's next Puzzle is the one at {@code position}
     * in rating order; position 1 forgets the place. The caller checks the position against the
     * package size. Returns false when no Puzzle is stored at that position.
     */
    @Transactional
    public boolean jump(
            String accountId, int categoryId, RatingPackage ratingPackage, int position,
            long nowMillis) {
        if (position == 1) {
            restart(accountId, categoryId, ratingPackage);
            return true;
        }
        List<Found> before = jdbc.query("""
                SELECT rating, puzzle_id FROM training_puzzle_ladder_member
                WHERE category_id = ? AND rating >= ? AND rating < ?
                ORDER BY rating, puzzle_id LIMIT 1 OFFSET ?
                """, (row, number) -> new Found(row.getInt(1), row.getLong(2)),
                categoryId, ratingPackage.low(), ratingPackage.high(), position - 2);
        if (before.isEmpty()) {
            return false;
        }
        saveProgress(accountId, categoryId, ratingPackage, before.getFirst(), position - 1, nowMillis);
        return true;
    }

    /** Every ladder pattern Theme by key, whether or not it has ladder Puzzles. */
    public Map<String, PatternCategory> patternCategories() {
        Map<String, PatternCategory> patterns = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT category.id, category.classification_key, classification.display_name,
                       classification.group_name
                """ + PATTERN_CATEGORY, row -> {
                    patterns.put(row.getString(2), new PatternCategory(row.getInt(1), new Pattern(
                            row.getString(2), row.getString(3), row.getString(4), 0)));
                }, PuzzleCategoryIndex.LADDER_GROUPS.toArray());
        return patterns;
    }

    /**
     * A random current, non-Mastered ladder Puzzle of one pattern within {@code [low, high)} that
     * is not in {@code exclude}, or empty when there is none. A uniform offset over the stored
     * band counts picks a start on the index alone; the first eligible Puzzle at or after it wins,
     * wrapping to the start of the range. Selection never counts members, and only the walk from
     * the start checks Mastered and current state.
     */
    public Optional<Long> randomInRange(
            String accountId, int categoryId, int low, int high, Set<Long> exclude,
            RandomGenerator random) {
        Integer size = jdbc.queryForObject("""
                SELECT coalesce(sum(puzzle_count), 0) FROM training_puzzle_ladder_band
                WHERE category_id = ? AND band >= ? AND band < ?
                """, Integer.class, categoryId, low / 100, high / 100);
        if (size == null || size == 0) {
            return Optional.empty();
        }
        List<Found> start = jdbc.query("""
                SELECT rating, puzzle_id FROM training_puzzle_ladder_member
                WHERE category_id = ? AND rating >= ? AND rating < ?
                ORDER BY rating, puzzle_id LIMIT 1 OFFSET ?
                """, (row, number) -> new Found(row.getInt(1), row.getLong(2)),
                categoryId, low, high, random.nextInt(size));
        Found from = start.isEmpty() ? new Found(low, 0) : start.getFirst();
        String seek = """
                SELECT l.puzzle_id FROM training_puzzle_ladder_member l
                WHERE l.category_id = ? AND l.rating >= ? AND l.rating < ?
                  AND (l.rating > ? OR l.puzzle_id >= ?)
                  AND EXISTS (
                      SELECT 1 FROM training_puzzle_current c WHERE c.puzzle_id = l.puzzle_id)
                  AND NOT EXISTS (
                      SELECT 1 FROM training_puzzle_performance s
                      WHERE s.account_id = ? AND s.puzzle_id = l.puzzle_id
                        AND s.list = 'mastered')
                ORDER BY l.rating, l.puzzle_id LIMIT ?
                """;
        // The window covers the exclusions, so an excluded Puzzle cannot hide the next one.
        int window = exclude.size() + 1;
        for (Found origin : List.of(from, new Found(low, 0))) {
            for (Long puzzleId : jdbc.queryForList(seek, Long.class, categoryId,
                    Math.max(low, origin.rating()), high, origin.rating(), origin.puzzleId(),
                    accountId, window)) {
                if (!exclude.contains(puzzleId)) {
                    return Optional.of(puzzleId);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * For each wanted other pattern (by Theme key), one random current, non-Mastered ladder Puzzle
     * of {@code categoryId} within {@code [low, high)} whose Themes include it, by reservoir
     * sampling over one pass of the range. Puzzles in {@code exclude} are skipped.
     */
    public Map<String, Long> combinationsInRange(
            String accountId, int categoryId, int low, int high, Set<String> wanted,
            Set<Long> exclude, RandomGenerator random) {
        Map<String, Long> picked = new java.util.HashMap<>();
        Map<String, Integer> seen = new java.util.HashMap<>();
        jdbc.query("""
                SELECT l.puzzle_id, c.source_themes FROM training_puzzle_ladder_member l
                JOIN training_puzzle_current c ON c.puzzle_id = l.puzzle_id
                WHERE l.category_id = ? AND l.rating >= ? AND l.rating < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM training_puzzle_performance s
                      WHERE s.account_id = ? AND s.puzzle_id = l.puzzle_id
                        AND s.list = 'mastered')
                """, row -> {
                    long puzzleId = row.getLong(1);
                    if (exclude.contains(puzzleId)) {
                        return;
                    }
                    for (String theme : row.getString(2).split(" ")) {
                        if (wanted.contains(theme)) {
                            int count = seen.merge(theme, 1, Integer::sum);
                            if (random.nextInt(count) == 0) {
                                picked.put(theme, puzzleId);
                            }
                        }
                    }
                }, categoryId, low, high, accountId);
        return picked;
    }

    /** Stores a new fast ladder run, replacing the account's previous runs. */
    @Transactional
    public long createFast(
            String accountId, int categoryId, List<FastItem> items, long nowMillis) {
        jdbc.update("""
                DELETE FROM training_puzzle_fast_ladder_item WHERE fast_ladder_id IN (
                    SELECT id FROM training_puzzle_fast_ladder WHERE account_id = ?)
                """, accountId);
        jdbc.update("DELETE FROM training_puzzle_fast_ladder WHERE account_id = ?", accountId);
        jdbc.update("""
                INSERT INTO training_puzzle_fast_ladder (account_id, category_id, created_at_ms)
                VALUES (?, ?, ?)
                """, accountId, categoryId, nowMillis);
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        int position = 1;
        for (FastItem item : items) {
            jdbc.update("""
                    INSERT INTO training_puzzle_fast_ladder_item (
                        fast_ladder_id, position, puzzle_id, rating_low, rating_high,
                        combined_category_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, id, position++, item.puzzleId(), item.ratingLow(), item.ratingHigh(),
                    item.combinedCategoryId());
        }
        return id;
    }

    /**
     * Marks the run's first unpresented item as presented and returns it with the run size;
     * {@link FastPresent#item()} is null when every item was presented, and the result is empty
     * when the run is not the account's.
     */
    @Transactional
    public Optional<FastPresent> presentNextFast(String accountId, long fastLadderId, long nowMillis) {
        Integer total = jdbc.query("""
                SELECT COUNT(i.position) FROM training_puzzle_fast_ladder f
                LEFT JOIN training_puzzle_fast_ladder_item i ON i.fast_ladder_id = f.id
                WHERE f.id = ? AND f.account_id = ?
                GROUP BY f.id
                """, (row, number) -> row.getInt(1), fastLadderId, accountId)
                .stream().findFirst().orElse(null);
        if (total == null) {
            return Optional.empty();
        }
        List<FastItem> next = jdbc.query("""
                SELECT position, puzzle_id, rating_low, rating_high, combined_category_id
                FROM training_puzzle_fast_ladder_item
                WHERE fast_ladder_id = ? AND presented_at_ms IS NULL
                ORDER BY position LIMIT 1
                """, (row, number) -> new FastItem(
                        row.getInt(1), row.getLong(2),
                        (Integer) row.getObject(3), (Integer) row.getObject(4),
                        (Integer) row.getObject(5)),
                fastLadderId);
        if (next.isEmpty()) {
            return Optional.of(new FastPresent(null, total));
        }
        FastItem item = next.getFirst();
        jdbc.update("""
                UPDATE training_puzzle_fast_ladder_item SET presented_at_ms = ?
                WHERE fast_ladder_id = ? AND position = ?
                """, nowMillis, fastLadderId, item.position());
        return Optional.of(new FastPresent(item, total));
    }

    private void saveProgress(
            String accountId, int categoryId, RatingPackage ratingPackage, Found last,
            int presented, long nowMillis) {
        jdbc.update("""
                INSERT INTO training_puzzle_ladder_progress (
                    account_id, category_id, rating_package, last_rating, last_puzzle_id,
                    presented, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, category_id, rating_package) DO UPDATE SET
                    last_rating = excluded.last_rating,
                    last_puzzle_id = excluded.last_puzzle_id,
                    presented = excluded.presented,
                    updated_at_ms = excluded.updated_at_ms
                """, accountId, categoryId, ratingPackage.key(), last.rating(), last.puzzleId(),
                presented, nowMillis);
    }

    private int total(int categoryId) {
        Integer total = jdbc.queryForObject("""
                SELECT coalesce(sum(puzzle_count), 0) FROM training_puzzle_ladder_band
                WHERE category_id = ?
                """, Integer.class, categoryId);
        return total == null ? 0 : total;
    }

    static RatingPackage packageOf(int rating) {
        for (RatingPackage ratingPackage : RatingPackage.values()) {
            if (rating >= ratingPackage.low() && rating < ratingPackage.high()) {
                return ratingPackage;
            }
        }
        throw new IllegalStateException("Rating is outside every package");
    }

    public record PatternCategory(int categoryId, Pattern pattern) {}

    public record Advance(long puzzleId, Progress progress) {}

    private record Cursor(int rating, long puzzleId, int presented) {}

    private record Found(int rating, long puzzleId) {}

    /**
     * One fast ladder item: a rating step has {@code ratingLow} and {@code ratingHigh}, a
     * combination step the other pattern's category. {@code position} is 0 before it is stored.
     */
    public record FastItem(
            int position, long puzzleId, Integer ratingLow, Integer ratingHigh,
            Integer combinedCategoryId) {}

    /** The presented item (null when the run is complete) and the run size. */
    public record FastPresent(FastItem item, int total) {}
}
