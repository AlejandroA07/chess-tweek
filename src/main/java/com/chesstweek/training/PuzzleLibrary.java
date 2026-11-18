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

    Optional<Puzzle> exercisePuzzle(String methodKey, int contentVersion, String exerciseKey);

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
