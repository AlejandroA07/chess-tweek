package com.chesstweek.training.internal.library;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;

@Component
final class BuiltInPuzzleLibraryLoader {

    private static final String RESOURCE = "/training/builtin-puzzle-library.json";
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9]{5,8}");
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    private static final Pattern THEME = Pattern.compile("[A-Za-z0-9]{1,40}");
    private static final String PROVIDER = "lichess-puzzle-database";

    private final PuzzleImportBatch batch;

    BuiltInPuzzleLibraryLoader(ObjectMapper mapper) {
        this.batch = load(mapper);
    }

    PuzzleImportBatch batch() {
        return batch;
    }

    private static PuzzleImportBatch load(ObjectMapper mapper) {
        try (InputStream input = BuiltInPuzzleLibraryLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Built-in Puzzle Library is missing");
            }
            return validate(mapper.readValue(input, Definition.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Built-in Puzzle Library could not be loaded", exception);
        }
    }

    private static PuzzleImportBatch validate(Definition definition) {
        if (definition.snapshot() == null || definition.puzzles() == null
                || definition.puzzles().isEmpty() || definition.exerciseReferences() == null
                || definition.exerciseReferences().isEmpty()) {
            throw new IllegalStateException("Built-in Puzzle Library is incomplete");
        }
        SnapshotDefinition sourceSnapshot = definition.snapshot();
        LocalDate snapshotDate = date(sourceSnapshot.snapshotDate(), "snapshot date");
        long importedAt;
        try {
            importedAt = Instant.parse(sourceSnapshot.importedAt()).toEpochMilli();
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalStateException("Built-in import time is invalid", exception);
        }
        if (!PROVIDER.equals(sourceSnapshot.provider())
                || sourceSnapshot.downloadChecksum() != null
                || sourceSnapshot.importerVersion() == null
                || sourceSnapshot.importerVersion().length() > 64) {
            throw new IllegalStateException("Built-in snapshot metadata is invalid");
        }
        var snapshot = new PuzzleImportBatch.Snapshot(
                PROVIDER,
                snapshotDate,
                null,
                sourceSnapshot.importerVersion(),
                importedAt);

        Set<String> puzzleIds = new HashSet<>();
        List<PuzzleImportBatch.PuzzleRow> puzzles = new ArrayList<>();
        for (PuzzleDefinition puzzle : definition.puzzles()) {
            if (puzzle == null || !PROVIDER.equals(puzzle.provider())
                    || puzzle.externalId() == null
                    || !EXTERNAL_ID.matcher(puzzle.externalId()).matches()
                    || !puzzleIds.add(puzzle.externalId())
                    || !"CC0-1.0".equals(puzzle.license())
                    || puzzle.rating() < 400 || puzzle.rating() > 4000
                    || puzzle.ratingDeviation() < 0 || puzzle.ratingDeviation() > 500
                    || puzzle.popularity() < -100 || puzzle.popularity() > 100
                    || puzzle.playCount() < 0
                    || !lichessUrl(puzzle.gameUrl())) {
                throw new IllegalStateException("Built-in puzzle identity is invalid");
            }
            List<String> sourceMoves = moves(puzzle.sourceMoves(), 2, 120);
            List<String> solutionMoves = moves(puzzle.solutionMoves(), 1, 119);
            if (!sourceMoves.getFirst().equals(puzzle.setupMove())
                    || !sourceMoves.subList(1, sourceMoves.size()).equals(solutionMoves)) {
                throw new IllegalStateException("Built-in puzzle source line is inconsistent");
            }
            validatePosition(puzzle, sourceMoves);
            List<String> themes = tags(puzzle.themes(), THEME, 1, 30, "theme");
            List<String> openings = tags(
                    puzzle.openingTags(), Pattern.compile("[A-Za-z0-9_]{1,120}"), 0, 16, "opening");
            puzzles.add(new PuzzleImportBatch.PuzzleRow(
                    PROVIDER,
                    puzzle.externalId(),
                    puzzle.sourceFen(),
                    sourceMoves,
                    puzzle.setupMove(),
                    puzzle.positionFen(),
                    solutionMoves,
                    puzzle.gameUrl(),
                    puzzle.license(),
                    puzzle.rating(),
                    puzzle.ratingDeviation(),
                    puzzle.popularity(),
                    puzzle.playCount(),
                    themes,
                    openings,
                    puzzle.dailyDate() == null ? null : date(puzzle.dailyDate(), "daily date")));
        }

        Set<String> exerciseKeys = new HashSet<>();
        List<PuzzleImportBatch.ExerciseReference> references = new ArrayList<>();
        for (ExerciseReferenceDefinition reference : definition.exerciseReferences()) {
            if (reference == null
                    || reference.methodKey() == null || !KEY.matcher(reference.methodKey()).matches()
                    || reference.exerciseKey() == null || !KEY.matcher(reference.exerciseKey()).matches()
                    || !exerciseKeys.add(reference.methodKey() + ":" + reference.exerciseKey())
                    || reference.contentVersion() < 1 || reference.contentVersion() > 1000
                    || !PROVIDER.equals(reference.provider())
                    || !puzzleIds.contains(reference.externalId())) {
                throw new IllegalStateException("Built-in exercise reference is invalid");
            }
            references.add(new PuzzleImportBatch.ExerciseReference(
                    reference.methodKey(),
                    reference.contentVersion(),
                    reference.exerciseKey(),
                    reference.provider(),
                    reference.externalId()));
        }
        if (puzzles.size() != references.size()) {
            throw new IllegalStateException("Every built-in puzzle must have one exercise reference");
        }
        return new PuzzleImportBatch(
                snapshot,
                List.copyOf(puzzles),
                List.of(),
                List.of(),
                List.copyOf(references));
    }

    private static void validatePosition(PuzzleDefinition puzzle, List<String> sourceMoves) {
        try {
            ChessGame game = new ChessGame(puzzle.sourceFen());
            for (int index = 0; index < sourceMoves.size(); index++) {
                String uci = sourceMoves.get(index);
                Move move = game.getMove(NotationType.UCI, uci);
                if (move == null || !game.isLegalMove(move)) {
                    throw new IllegalStateException("Built-in puzzle line is illegal: " + puzzle.externalId());
                }
                game.playMove(move);
                if (index == 0 && !game.getFen().equals(puzzle.positionFen())) {
                    throw new IllegalStateException("Built-in puzzle position does not follow its setup move");
                }
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Built-in puzzle position is invalid", exception);
        }
    }

    private static List<String> moves(List<String> values, int minimum, int maximum) {
        return tags(values, UCI, minimum, maximum, "move");
    }

    private static List<String> tags(
            List<String> values,
            Pattern pattern,
            int minimum,
            int maximum,
            String name) {
        if (values == null || values.size() < minimum || values.size() > maximum
                || values.stream().anyMatch(value -> value == null || !pattern.matcher(value).matches())
                || new HashSet<>(values).size() != values.size()) {
            throw new IllegalStateException("Built-in puzzle " + name + " list is invalid");
        }
        return List.copyOf(values);
    }

    private static LocalDate date(String value, String name) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalStateException("Built-in " + name + " is invalid", exception);
        }
    }

    private static boolean lichessUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme())
                    && "lichess.org".equals(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record Definition(
            SnapshotDefinition snapshot,
            List<PuzzleDefinition> puzzles,
            List<ExerciseReferenceDefinition> exerciseReferences) {}

    private record SnapshotDefinition(
            String provider,
            String snapshotDate,
            String downloadChecksum,
            String importerVersion,
            String importedAt) {}

    private record PuzzleDefinition(
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
            String dailyDate) {}

    private record ExerciseReferenceDefinition(
            String methodKey,
            int contentVersion,
            String exerciseKey,
            String provider,
            String externalId) {}
}
