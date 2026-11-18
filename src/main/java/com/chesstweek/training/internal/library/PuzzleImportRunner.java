package com.chesstweek.training.internal.library;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.chesstweek.training.PuzzleLibrary;

/** Explicit, opt-in local import operation. It is absent from normal application startup. */
@Component
@ConditionalOnProperty(name = "chess-tweek.puzzle-import.file")
final class PuzzleImportRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PuzzleImportRunner.class);

    private final PuzzleLibrary library;
    private final String root;
    private final String file;
    private final String snapshotDate;
    private final String checksum;
    private final int minimumRating;
    private final int maximumRating;
    private final int target;

    PuzzleImportRunner(
            PuzzleLibrary library,
            @Value("${chess-tweek.puzzle-import.root:}") String root,
            @Value("${chess-tweek.puzzle-import.file}") String file,
            @Value("${chess-tweek.puzzle-import.snapshot-date:}") String snapshotDate,
            @Value("${chess-tweek.puzzle-import.checksum:}") String checksum,
            @Value("${chess-tweek.puzzle-import.minimum-rating:1200}") int minimumRating,
            @Value("${chess-tweek.puzzle-import.maximum-rating:1700}") int maximumRating,
            @Value("${chess-tweek.puzzle-import.target-per-collection:50}") int target) {
        this.library = library;
        this.root = root;
        this.file = file;
        this.snapshotDate = snapshotDate;
        this.checksum = checksum;
        this.minimumRating = minimumRating;
        this.maximumRating = maximumRating;
        this.target = target;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            PuzzleLibrary.ImportResult result = library.importSnapshot(new PuzzleLibrary.ImportRequest(
                    Path.of(root),
                    file,
                    LocalDate.parse(snapshotDate),
                    checksum.isBlank() ? null : checksum,
                    minimumRating,
                    maximumRating,
                    target));
            LOGGER.info(
                    "Puzzle import {}: {} puzzles, {} collections, {} available, {} database bytes",
                    result.status(), result.puzzles(), result.collections(),
                    result.availableCollections(), result.databaseBytes());
        } catch (InvalidPathException | DateTimeParseException exception) {
            throw new IllegalArgumentException("Puzzle import configuration is invalid", exception);
        }
    }
}
