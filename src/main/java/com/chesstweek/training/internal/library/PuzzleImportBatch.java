package com.chesstweek.training.internal.library;

import java.time.LocalDate;
import java.util.List;

public record PuzzleImportBatch(
        Snapshot snapshot,
        List<PuzzleRow> puzzles,
        List<Classification> classifications,
        List<Collection> collections,
        List<ExerciseReference> exerciseReferences) {

    public record Snapshot(
            String provider,
            LocalDate snapshotDate,
            String downloadChecksum,
            String importerVersion,
            long importedAtMs) {}

    public record PuzzleRow(
            String provider,
            String externalId,
            String sourceFen,
            List<String> sourceMoves,
            String setupMove,
            String positionFen,
            List<String> solutionMoves,
            String gameUrl,
            String license,
            int rating,
            int ratingDeviation,
            int popularity,
            int playCount,
            List<String> themes,
            List<String> openingTags,
            LocalDate dailyDate) {}

    public record Classification(
            Kind kind,
            String key,
            String parentKey,
            String displayName,
            String groupName,
            boolean drillOriented) {}

    public enum Kind {
        THEME("theme"),
        OPENING_FAMILY("opening_family"),
        OPENING_VARIATION("opening_variation");

        private final String databaseValue;

        Kind(String databaseValue) {
            this.databaseValue = databaseValue;
        }

        public String databaseValue() {
            return databaseValue;
        }
    }

    public record Collection(
            Kind kind,
            String classificationKey,
            String displayName,
            int targetCount,
            List<Member> members) {}

    public record Member(String provider, String externalId, long selectionScore) {}

    public record ExerciseReference(
            String methodKey,
            int contentVersion,
            String exerciseKey,
            String provider,
            String externalId) {}
}
