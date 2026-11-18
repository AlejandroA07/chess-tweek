package com.chesstweek.training;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PuzzleLibraryPersistenceTest {

    private static final String HEADER =
            "PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags,DailyDate\n";
    private static final String FEN = "5rk1/5pb1/B1p4p/2p5/8/5RqP/PQ4P1/R5K1 b - - 0 26";
    private static final String MOVES = "g3f3 b2g7 g8g7 g2f3";

    @Test
    void installsStableBuiltInPuzzlesAndResolvesCourseReferencesAcrossRestart(
            @TempDir Path directory) {
        Path database = directory.resolve("runtime/chess-tweek.db");
        long puzzleId;
        try (var application = start(database)) {
            PuzzleLibrary library = application.getBean(PuzzleLibrary.class);
            assertThat(library.summary())
                    .extracting(
                            PuzzleLibrary.CatalogSummary::puzzles,
                            PuzzleLibrary.CatalogSummary::themeNames,
                            PuzzleLibrary.CatalogSummary::openingNames)
                    .containsExactly(12, 74, 0);
            PuzzleLibrary.Puzzle puzzle = library.exercisePuzzle(
                            GuidedCourses.COURSE_KEY, 2, "intermezzo-after-queen-capture")
                    .orElseThrow();
            puzzleId = puzzle.id();
            assertThat(puzzle.externalId()).isEqualTo("lDWH9");
            assertThat(puzzle.solutionMoves()).containsExactly("b2g7", "g8g7", "g2f3");
            assertThat(application.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM training_method_exercise_puzzle", Integer.class))
                    .isEqualTo(12);
        }

        try (var restarted = start(database)) {
            PuzzleLibrary library = restarted.getBean(PuzzleLibrary.class);
            assertThat(library.exercisePuzzle(
                            GuidedCourses.COURSE_KEY, 2, "intermezzo-after-queen-capture")
                    .orElseThrow().id()).isEqualTo(puzzleId);
            assertThat(library.summary().puzzles()).isEqualTo(12);
        }
    }

    @Test
    void importsBoundedCollectionsIdempotentlyAndRejectsUnsafePaths(@TempDir Path directory)
            throws Exception {
        Path database = directory.resolve("runtime/chess-tweek.db");
        Path importRoot = Files.createDirectory(directory.resolve("imports"));
        Path snapshot = importRoot.resolve("lichess.csv");
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 0; index < 20; index++) {
            csv.append("A%04d,%s,%s,2000,70,95,500,fork short,https://lichess.org/game%04d,Kings_Pawn_Game Kings_Pawn_Game_Leonardis_Variation,%n"
                    .formatted(index, FEN, MOVES, index));
        }
        Files.writeString(snapshot, csv);

        try (var application = start(database)) {
            PuzzleLibrary library = application.getBean(PuzzleLibrary.class);
            var request = new PuzzleLibrary.ImportRequest(
                    importRoot, "lichess.csv", LocalDate.of(2026, 11, 2),
                    null, 1900, 2100, 20);

            assertThatThrownBy(() -> library.importSnapshot(new PuzzleLibrary.ImportRequest(
                    importRoot, "lichess.csv", LocalDate.of(2026, 11, 2),
                    "0".repeat(64), 1900, 2100, 20)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Puzzle snapshot checksum does not match");

            PuzzleLibrary.ImportResult imported = library.importSnapshot(request);
            assertThat(imported.status()).isEqualTo(PuzzleLibrary.ImportStatus.IMPORTED);
            assertThat(imported.puzzles()).isEqualTo(20);
            assertThat(imported.collections()).isEqualTo(5);
            assertThat(imported.availableCollections()).isEqualTo(5);
            assertThat(imported.databaseBytes()).isPositive();

            PuzzleLibrary.ImportResult repeated = library.importSnapshot(request);
            assertThat(repeated.status()).isEqualTo(PuzzleLibrary.ImportStatus.ALREADY_IMPORTED);
            assertThat(library.summary().puzzles()).isEqualTo(32);

            assertThatThrownBy(() -> library.importSnapshot(new PuzzleLibrary.ImportRequest(
                    importRoot, "../lichess.csv", LocalDate.of(2026, 11, 3),
                    null, 1900, 2100, 20)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Puzzle import request is invalid");

            Path linked = importRoot.resolve("linked.csv");
            Files.createSymbolicLink(linked, snapshot);
            assertThatThrownBy(() -> library.importSnapshot(new PuzzleLibrary.ImportRequest(
                    importRoot, "linked.csv", LocalDate.of(2026, 11, 3),
                    null, 1900, 2100, 20)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Puzzle snapshot file is invalid");
        }
    }

    @Test
    void leavesTheDatabaseUnchangedWhenSelectedPuzzleMaterialIsIllegal(@TempDir Path directory)
            throws Exception {
        Path database = directory.resolve("runtime/chess-tweek.db");
        Path importRoot = Files.createDirectory(directory.resolve("imports"));
        Files.writeString(importRoot.resolve("bad.csv"), HEADER
                + "BAD01," + FEN + ",a1a8 b2g7,2000,70,95,500,fork,"
                + "https://lichess.org/bad,Kings_Pawn_Game,\n");

        try (var application = start(database)) {
            PuzzleLibrary library = application.getBean(PuzzleLibrary.class);
            assertThatThrownBy(() -> library.importSnapshot(new PuzzleLibrary.ImportRequest(
                    importRoot, "bad.csv", LocalDate.of(2026, 11, 4),
                    null, 1900, 2100, 20)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("illegal move line");
            assertThat(library.summary().puzzles()).isEqualTo(12);
            assertThat(application.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM training_puzzle_snapshot", Integer.class)).isEqualTo(1);
        }
    }

    private static ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + database, "--logging.level.root=OFF");
    }
}
