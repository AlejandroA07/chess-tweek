package com.chesstweek.training;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The public interface for the reusable training Puzzle Library. Imported source material is
 * immutable; methods and collections reference its stable internal puzzle IDs.
 */
public interface PuzzleLibrary {

    Optional<Puzzle> puzzle(long puzzleId);

    /** Exact reference by provider and stable external ID, independent of any snapshot. */
    Optional<Puzzle> puzzle(String provider, String externalId);

    Optional<Puzzle> exercisePuzzle(String methodKey, int contentVersion, String exerciseKey);

    /**
     * One uniformly keyed library Puzzle, chosen by an index seek over stable random keys. It never
     * sorts or scans the full corpus.
     */
    Optional<Puzzle> randomPuzzle();

    /** One uniformly keyed library Puzzle in an exact Theme or opening classification. */
    Optional<Puzzle> randomPuzzle(Category category);

    CatalogSummary summary();

    ImportResult importSnapshot(ImportRequest request);

    record Puzzle(
            long id,
            String provider,
            String externalId,
            String sourceFen,
            List<String> sourceMoves,
            String setupMove,
            String positionFen,
            List<String> solutionMoves,
            String gameUrl,
            String license,
            LocalDate snapshotDate,
            int rating,
            int ratingDeviation,
            int popularity,
            int playCount,
            List<String> themes,
            List<String> openingTags) {}

    record Category(CategoryKind kind, String key) {}

    enum CategoryKind {
        THEME,
        OPENING_FAMILY,
        OPENING_VARIATION
    }

    record ImportRequest(
            Path approvedRoot,
            String fileName,
            LocalDate snapshotDate,
            String downloadChecksum,
            int minimumRating,
            int maximumRating,
            int targetPerCollection) {}

    record ImportResult(
            ImportStatus status,
            long snapshotId,
            int puzzles,
            int classifications,
            int collections,
            int availableCollections,
            long databaseBytes) {}

    enum ImportStatus {
        IMPORTED,
        ALREADY_IMPORTED
    }

    record CatalogSummary(
            int puzzles,
            int themeNames,
            int openingNames,
            int collections,
            int availableCollections,
            long databaseBytes) {}
}
