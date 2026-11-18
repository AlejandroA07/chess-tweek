package com.chesstweek.training.internal.library;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.internal.persistence.JdbcPuzzleLibraryStore;

@Service
public final class PuzzleLibraryService implements PuzzleLibrary {

    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final JdbcPuzzleLibraryStore store;
    private final LichessPuzzleImporter importer;

    PuzzleLibraryService(
            JdbcPuzzleLibraryStore store,
            BuiltInPuzzleLibraryLoader builtIns,
            LichessPuzzleImporter importer) {
        this.store = store;
        this.importer = importer;
        store.persist(builtIns.batch());
    }

    @Override
    public Optional<Puzzle> puzzle(long puzzleId) {
        return puzzleId > 0 ? store.puzzle(puzzleId) : Optional.empty();
    }

    @Override
    public Optional<Puzzle> exercisePuzzle(
            String methodKey, int contentVersion, String exerciseKey) {
        if (!validKey(methodKey) || !validKey(exerciseKey)
                || contentVersion < 1 || contentVersion > 1000) {
            return Optional.empty();
        }
        return store.exercisePuzzle(methodKey, contentVersion, exerciseKey);
    }

    @Override
    public CatalogSummary summary() {
        return store.summary();
    }

    @Override
    public ImportResult importSnapshot(ImportRequest request) {
        PuzzleImportBatch batch = importer.read(request, store.themeNames());
        return store.persist(batch);
    }

    private static boolean validKey(String value) {
        return value != null && value.length() <= 64 && KEY.matcher(value).matches();
    }
}
