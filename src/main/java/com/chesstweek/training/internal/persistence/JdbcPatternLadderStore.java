package com.chesstweek.training.internal.persistence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** Every pattern Theme with at least one ladder Puzzle, with its ladder size. */
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
        List<long[]> found = jdbc.query("""
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
                """, (row, number) -> new long[] {row.getLong(1), row.getLong(2)},
                categoryId, Math.max(ratingPackage.low(), cursor.rating()), ratingPackage.high(),
                cursor.rating(), cursor.puzzleId(), accountId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int rating = (int) found.getFirst()[0];
        long puzzleId = found.getFirst()[1];
        int presented = cursor.presented() + 1;
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
                """, accountId, categoryId, ratingPackage.key(), rating, puzzleId, presented,
                nowMillis);
        return Optional.of(new Advance(puzzleId, new Progress(presented, rating)));
    }

    /** Forgets the account's place in one package. */
    public void restart(String accountId, int categoryId, RatingPackage ratingPackage) {
        jdbc.update("""
                DELETE FROM training_puzzle_ladder_progress
                WHERE account_id = ? AND category_id = ? AND rating_package = ?
                """, accountId, categoryId, ratingPackage.key());
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
}
