package com.chesstweek.training.internal.library;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.chesstweek.training.PuzzleLibrary;
import com.chesstweek.training.internal.persistence.JdbcPuzzleLibraryStore;

@Service
public final class PuzzleLibraryService implements PuzzleLibrary {

    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern PROVIDER = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final Pattern CATEGORY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,120}");

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
    public Optional<Puzzle> puzzle(String provider, String externalId) {
        if (provider == null || provider.length() > 64 || !PROVIDER.matcher(provider).matches()
                || externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            return Optional.empty();
        }
        return store.puzzle(provider, externalId);
    }

    @Override
    public Optional<Puzzle> randomPuzzle() {
        return store.randomPuzzle(ThreadLocalRandom.current());
    }

    @Override
    public Optional<Puzzle> randomPuzzle(Category category) {
        if (category == null || category.kind() == null || category.key() == null
                || !CATEGORY_KEY.matcher(category.key()).matches()) {
            return Optional.empty();
        }
        PuzzleImportBatch.Kind kind = switch (category.kind()) {
            case THEME -> PuzzleImportBatch.Kind.THEME;
            case OPENING_FAMILY -> PuzzleImportBatch.Kind.OPENING_FAMILY;
            case OPENING_VARIATION -> PuzzleImportBatch.Kind.OPENING_VARIATION;
        };
        return store.randomPuzzle(kind, category.key(), ThreadLocalRandom.current());
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
    public List<CategoryEntry> categories() {
        return store.categories();
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
