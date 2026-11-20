package com.chesstweek.training;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;

import static org.assertj.core.api.Assertions.assertThat;

class PuzzleCorpusLifecycleTest {

    private static final String HEADER =
            "PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags,DailyDate";
    private static final String FEN = "5rk1/5pb1/B1p4p/2p5/8/5RqP/PQ4P1/R5K1 b - - 0 26";
    private static final String MOVES = "g3f3 b2g7 g8g7 g2f3";
    private static final String BUILT_IN_UNCHANGED =
            "lDWH9," + FEN + "," + MOVES + ",1536,74,100,3750,advantage endgame intermezzo short,"
                    + "https://lichess.org/game/OHeXKNku/black#52,,";
    private static final String BUILT_IN_CONFLICT =
            "xrHJJ," + FEN + "," + MOVES + ",1754,74,100,2747,crushing intermezzo,"
                    + "https://lichess.org/game/BmCP9xZ1/black#34,,";
    /** Official record 006E1: its legal line repeats f1e1, and its openings contain hyphens. */
    private static final String REPEATED_MOVE_HYPHENATED_OPENING =
            "H0001,5rk1/R4pp1/1p5p/3Q4/1PPp2q1/3P2P1/5P2/4nK2 w - - 0 34,"
                    + "f1e1 f8e8 e1f1 g4h3 d5g2 e8e1 f1e1 h3g2,1543,73,96,21487,"
                    + "crushing deflection endgame veryLong,https://lichess.org/game/KaLKLKD5#67,"
                    + "Caro-Kann_Defense Caro-Kann_Defense_Advance_Variation,";
    private static final String LEONARDIS = "Kings_Pawn_Game_Leonardis_Variation";
    private static final String OPENINGS = "Kings_Pawn_Game " + LEONARDIS;
    private static final String ZSTD = "/opt/homebrew/bin/zstd";

    @TempDir
    Path directory;

    private FakeLichess lichess;

    @BeforeEach
    void startServer() throws IOException {
        lichess = new FakeLichess();
    }

    @AfterEach
    void stopServer() {
        lichess.close();
    }

    @Test
    void importsVerifiesAndRefreshesTheCompleteSnapshotWithRetentionAndConflicts() throws Exception {
        lichess.publish(snapshot(
                BUILT_IN_UNCHANGED,
                BUILT_IN_CONFLICT,
                record("A0001", 2000, "fork short", OPENINGS),
                record("A0002", 2010, "fork short", OPENINGS),
                record("A0003", 2020, "fork short", ""),
                record("A0004", 2030, "fork short", ""),
                "BAD01," + FEN + ",a1a8 b2g7,2000,70,95,500,fork,https://lichess.org/bad00001,,",
                record("A0001", 2000, "fork short", OPENINGS),
                REPEATED_MOVE_HYPHENATED_OPENING),
                "\"s1\"", "2026-11-01T07:57:40Z");

        try (var application = start(database(), List.of())) {
            PuzzleCorpus corpus = application.getBean(PuzzleCorpus.class);
            PuzzleLibrary library = application.getBean(PuzzleLibrary.class);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            assertThat(lichess.heads).hasValue(0);
            assertThat(lichess.gets).hasValue(0);

            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.NO_UPDATE_AVAILABLE);
            PuzzleCorpus.UpdateCheckResult check = corpus.checkForUpdate();
            assertThat(check.outcome()).isEqualTo(PuzzleCorpus.UpdateCheckOutcome.CHECKED);
            assertThat(check.check().updateAvailable()).isTrue();
            assertThat(check.check().remoteSnapshotDate()).hasToString("2026-11-01");
            assertThat(lichess.heads).hasValue(1);
            assertThat(lichess.gets).hasValue(0);

            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.STARTED);
            PuzzleCorpus.ImportProgress first = awaitStopped(corpus);
            assertThat(first.phase()).isEqualTo(PuzzleCorpus.Phase.VERIFIED);
            assertThat(first.processedRecords()).isEqualTo(9);
            assertThat(first.insertedPuzzles()).isEqualTo(5);
            assertThat(first.unchangedPuzzles()).isEqualTo(1);
            assertThat(first.conflictingRecords()).isEqualTo(1);
            assertThat(first.rejectedRecords()).isEqualTo(2);
            assertThat(first.sourceDeleted()).isTrue();
            assertThat(corpusFiles()).isEmpty();

            PuzzleCorpus.Status status = corpus.status();
            // Six accepted records plus the conflicting built-in, which stays present and unchanged.
            assertThat(status.stored().recordCount()).isEqualTo(7);
            assertThat(status.stored().snapshotDate()).hasToString("2026-11-01");
            assertThat(status.stored().downloadChecksum()).hasSize(64);
            assertThat(status.stored().sourceUrl()).isEqualTo(lichess.url());
            assertThat(status.lastCheck().updateAvailable()).isFalse();
            assertThat(status.issues())
                    .extracting(PuzzleCorpus.Issue::externalId, PuzzleCorpus.Issue::reason)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("xrHJJ", "immutable_conflict"),
                            org.assertj.core.groups.Tuple.tuple("BAD01", "illegal_move_line"),
                            org.assertj.core.groups.Tuple.tuple("A0001", "duplicate_id"));

            PuzzleLibrary.Puzzle conflicted = library.puzzle(
                    "lichess-puzzle-database", "xrHJJ").orElseThrow();
            assertThat(conflicted.sourceFen()).startsWith("r2q1rk1");
            assertThat(library.puzzle("lichess-puzzle-database", "A0001").orElseThrow()
                    .snapshotDate()).hasToString("2026-11-01");
            assertThat(count(jdbc, "SELECT COUNT(*) FROM training_puzzle WHERE external_id = 'A0001'"))
                    .isEqualTo(1);
            assertThat(library.puzzle("lichess-puzzle-database", "BAD01")).isEmpty();

            Set<String> leonardis = new HashSet<>();
            for (int index = 0; index < 50; index++) {
                leonardis.add(library.randomPuzzle(new PuzzleLibrary.Category(
                        PuzzleLibrary.CategoryKind.OPENING_VARIATION, LEONARDIS))
                        .orElseThrow().externalId());
            }
            assertThat(leonardis).isSubsetOf("A0001", "A0002");
            assertThat(library.randomPuzzle()).isPresent();
            PuzzleLibrary.Puzzle caroKann = library.randomPuzzle(new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.OPENING_FAMILY, "Caro-Kann_Defense")).orElseThrow();
            assertThat(caroKann.externalId()).isEqualTo("H0001");
            assertThat(caroKann.solutionMoves()).containsExactly(
                    "f8e8", "e1f1", "g4h3", "d5g2", "e8e1", "f1e1", "h3g2");
            assertThat(library.randomPuzzle(new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.THEME, "notATheme"))).isEmpty();

            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.NO_UPDATE_AVAILABLE);

            lichess.publish(snapshot(
                    record("A0001", 1500, "pin short", ""),
                    record("A0002", 2010, "fork short", OPENINGS),
                    record("C0001", 1800, "skewer short", ""),
                    "A0003,5rk1/R4pp1/1p5p/3Q4/1PPp2q1/3P2P1/5P2/4nK2 w - - 0 34,"
                            + "f1e1 f8e8 e1f1 g4h3 d5g2 e8e1 f1e1 h3g2,2020,70,95,500,fork short,"
                            + "https://lichess.org/gameA0003,,"),
                    "\"s2\"", "2026-11-15T07:00:00Z");
            assertThat(corpus.checkForUpdate().check().updateAvailable()).isTrue();
            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.STARTED);
            PuzzleCorpus.ImportProgress refreshed = awaitStopped(corpus);

            assertThat(refreshed.phase()).isEqualTo(PuzzleCorpus.Phase.VERIFIED);
            assertThat(refreshed.insertedPuzzles()).isEqualTo(1);
            assertThat(refreshed.updatedPuzzles()).isEqualTo(1);
            assertThat(refreshed.unchangedPuzzles()).isEqualTo(1);
            assertThat(refreshed.conflictingRecords()).isEqualTo(1);
            assertThat(refreshed.removedPuzzles()).isEqualTo(2);
            // lDWH9 and xrHJJ are missing but referenced by the guided course.
            assertThat(refreshed.retainedPuzzles()).isEqualTo(2);
            assertThat(corpus.status().stored().recordCount()).isEqualTo(4);
            assertThat(corpus.status().stored().snapshotDate()).hasToString("2026-11-15");

            PuzzleLibrary.Puzzle conflictedCorpusPuzzle = library.puzzle(
                    "lichess-puzzle-database", "A0003").orElseThrow();
            assertThat(conflictedCorpusPuzzle.sourceFen()).isEqualTo(FEN);
            assertThat(conflictedCorpusPuzzle.snapshotDate()).hasToString("2026-11-15");
            assertThat(corpus.status().issues())
                    .extracting(PuzzleCorpus.Issue::externalId, PuzzleCorpus.Issue::reason)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("A0003", "immutable_conflict"));
            assertThat(library.puzzle("lichess-puzzle-database", "A0004")).isEmpty();
            assertThat(library.randomPuzzle(new PuzzleLibrary.Category(
                    PuzzleLibrary.CategoryKind.OPENING_FAMILY, "Caro-Kann_Defense"))).isEmpty();
            PuzzleLibrary.Puzzle retained = library.puzzle(
                    "lichess-puzzle-database", "lDWH9").orElseThrow();
            assertThat(library.exercisePuzzle(
                    GuidedCourses.COURSE_KEY, 2, "intermezzo-after-queen-capture")
                    .orElseThrow().id()).isEqualTo(retained.id());
            PuzzleLibrary.Puzzle updated = library.puzzle(
                    "lichess-puzzle-database", "A0001").orElseThrow();
            assertThat(updated.rating()).isEqualTo(1500);
            assertThat(updated.themes()).containsExactly("pin", "short");
            for (int index = 0; index < 20; index++) {
                assertThat(library.randomPuzzle(new PuzzleLibrary.Category(
                        PuzzleLibrary.CategoryKind.OPENING_VARIATION, LEONARDIS))
                        .orElseThrow().externalId()).isEqualTo("A0002");
            }
            assertThat(count(jdbc, """
                    SELECT COUNT(*) FROM training_puzzle_category_member m
                    JOIN training_puzzle_category c ON c.id = m.category_id
                    JOIN training_puzzle p ON p.id = m.puzzle_id
                    WHERE c.kind = 'theme' AND c.classification_key = 'fork'
                      AND p.external_id GLOB '[AC]0*'
                    """)).isEqualTo(2); // A0002, and conflicting A0003 keeps its stored observation
            assertThat(count(jdbc, """
                    SELECT COUNT(*) FROM training_puzzle_category_member m
                    LEFT JOIN training_puzzle_current c ON c.puzzle_id = m.puzzle_id
                    WHERE c.puzzle_id IS NULL
                    """)).isZero();
            assertThat(lichess.heads).hasValue(2);
            assertThat(lichess.gets).hasValue(2);
        }
    }

    @Test
    void keepsAttemptedAndClassifiedPuzzlesThatARefreshNoLongerContains() throws Exception {
        lichess.publish(snapshot(
                record("A0001", 2000, "fork short", OPENINGS),
                record("A0002", 2010, "fork short", "Caro-Kann_Defense"),
                record("A0003", 2020, "fork short", "")),
                "\"s1\"", "2026-11-01T07:57:40Z");

        try (var application = start(database(), List.of())) {
            PuzzleCorpus corpus = application.getBean(PuzzleCorpus.class);
            PuzzleLibrary library = application.getBean(PuzzleLibrary.class);
            PuzzleTraining training = application.getBean(PuzzleTraining.class);
            assertThat(corpus.checkForUpdate().check().updateAvailable()).isTrue();
            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.STARTED);
            assertThat(awaitStopped(corpus).phase()).isEqualTo(PuzzleCorpus.Phase.VERIFIED);

            // An unfinished attempt on A0001; A0002 classified through Show solution.
            PuzzleTraining.Attempt unfinished = training.startInCategory("owner-id",
                    new PuzzleLibrary.Category(PuzzleLibrary.CategoryKind.OPENING_VARIATION, LEONARDIS))
                    .orElseThrow();
            assertThat(unfinished.externalId()).isEqualTo("A0001");
            PuzzleTraining.Attempt classified = training.startInCategory("other-id",
                    new PuzzleLibrary.Category(
                            PuzzleLibrary.CategoryKind.OPENING_FAMILY, "Caro-Kann_Defense"))
                    .orElseThrow();
            assertThat(classified.externalId()).isEqualTo("A0002");
            training.reveal("other-id", classified.attemptId());

            lichess.publish(snapshot(record("A0004", 2030, "fork short", "")),
                    "\"s2\"", "2026-11-15T07:00:00Z");
            assertThat(corpus.checkForUpdate().check().updateAvailable()).isTrue();
            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.STARTED);
            PuzzleCorpus.ImportProgress refreshed = awaitStopped(corpus);

            assertThat(refreshed.phase()).isEqualTo(PuzzleCorpus.Phase.VERIFIED);
            assertThat(refreshed.removedPuzzles()).isEqualTo(1);
            assertThat(refreshed.retainedPuzzles()).isEqualTo(2);
            assertThat(library.puzzle("lichess-puzzle-database", "A0001")).isPresent();
            assertThat(library.puzzle("lichess-puzzle-database", "A0002")).isPresent();
            assertThat(library.puzzle("lichess-puzzle-database", "A0003")).isEmpty();
            assertThat(training.performance("other-id", classified.puzzleId()))
                    .contains(new PuzzleTraining.Performance(
                            PuzzleTraining.TrainingList.HARD_TO_SOLVE, 0, 0));
        }
    }

    @Test
    void resumesAnInterruptedDownloadWithARangeRequest() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            rows.add(record("D%04d".formatted(index), 1500 + index, "fork short", ""));
        }
        lichess.publish(snapshot(rows.toArray(String[]::new)), "\"r1\"", "2026-11-01T07:57:40Z");
        lichess.truncateNextGet.set(true);

        try (var application = start(database(), List.of())) {
            PuzzleCorpus corpus = application.getBean(PuzzleCorpus.class);
            corpus.checkForUpdate();
            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.STARTED);
            PuzzleCorpus.ImportProgress paused = awaitStopped(corpus);
            assertThat(paused.phase()).isEqualTo(PuzzleCorpus.Phase.PAUSED);
            assertThat(paused.failureCode()).isEqualTo("download_failed");
            assertThat(paused.downloadedBytes()).isPositive().isLessThan(paused.sourceBytes());
            assertThat(corpusFiles()).hasSize(1);

            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.RESUMED);
            PuzzleCorpus.ImportProgress verified = awaitStopped(corpus);
            assertThat(verified.phase()).isEqualTo(PuzzleCorpus.Phase.VERIFIED);
            assertThat(verified.insertedPuzzles()).isEqualTo(200);
            assertThat(lichess.ranges).containsExactly("bytes=" + paused.downloadedBytes() + "-");
            assertThat(corpusFiles()).isEmpty();
        }
    }

    @Test
    void resumesAnImportInterruptedByShutdownWithoutDuplicates() throws Exception {
        int total = 3_000;
        List<String> rows = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            rows.add(record("R%04d".formatted(index), 1500, "fork short", ""));
        }
        lichess.publish(snapshot(rows.toArray(String[]::new)), "\"i1\"", "2026-11-01T07:57:40Z");
        Path database = database();
        List<String> smallChunks = List.of("--chess-tweek.puzzle-corpus.chunk-records=1");

        long interruptedAt;
        try (var application = start(database, smallChunks)) {
            PuzzleCorpus corpus = application.getBean(PuzzleCorpus.class);
            corpus.checkForUpdate();
            corpus.startImport();
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (progress(corpus).processedRecords() < 10) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(2);
            }
        }

        try (var application = start(database, List.of())) {
            PuzzleCorpus corpus = application.getBean(PuzzleCorpus.class);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            PuzzleCorpus.ImportProgress paused = progress(corpus);
            assertThat(paused.phase()).isEqualTo(PuzzleCorpus.Phase.PAUSED);
            assertThat(paused.failureCode()).isEqualTo("interrupted");
            interruptedAt = paused.processedRecords();
            assertThat(interruptedAt).isPositive().isLessThan(total);
            assertThat(corpusFiles()).hasSize(1);

            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.RESUMED);
            PuzzleCorpus.ImportProgress verified = awaitStopped(corpus);
            assertThat(verified.phase()).isEqualTo(PuzzleCorpus.Phase.VERIFIED);
            assertThat(verified.processedRecords()).isEqualTo(total);
            assertThat(verified.insertedPuzzles()).isEqualTo(total);
            assertThat(corpus.status().stored().recordCount()).isEqualTo(total);
            assertThat(count(jdbc,
                    "SELECT COUNT(*) FROM training_puzzle WHERE external_id GLOB 'R[0-9]*'"))
                    .isEqualTo(total);
            assertThat(lichess.gets).hasValue(1);
        }
    }

    @Test
    void failsSafelyWhenTheSnapshotChangesBeforeDownload() throws Exception {
        lichess.publish(snapshot(record("S0001", 1500, "fork", "")), "\"c1\"", "2026-11-01T07:57:40Z");
        try (var application = start(database(), List.of())) {
            PuzzleCorpus corpus = application.getBean(PuzzleCorpus.class);
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            corpus.checkForUpdate();
            lichess.publish(snapshot(record("S0002", 1500, "fork", "")), "\"c2\"", "2026-11-02T07:57:40Z");

            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.STARTED);
            PuzzleCorpus.ImportProgress failed = awaitStopped(corpus);
            assertThat(failed.phase()).isEqualTo(PuzzleCorpus.Phase.FAILED);
            assertThat(failed.failureCode()).isEqualTo("source_changed");
            assertThat(corpusFiles()).isEmpty();
            assertThat(corpus.status().stored()).isNull();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM training_puzzle WHERE external_id LIKE 'S000%'"))
                    .isZero();
        }
    }

    @Test
    void refusesToStartWithoutDiskSpaceOrZstdAndNeverDownloads() throws Exception {
        lichess.publish(snapshot(record("S0001", 1500, "fork", "")), "\"d1\"", "2026-11-01T07:57:40Z");
        try (var application = start(database(),
                List.of("--chess-tweek.puzzle-corpus.required-free-bytes=" + Long.MAX_VALUE / 4))) {
            PuzzleCorpus corpus = application.getBean(PuzzleCorpus.class);
            corpus.checkForUpdate();
            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.INSUFFICIENT_DISK);
            assertThat(corpus.status().latestImport()).isNull();
        }
        try (var application = start(database(),
                List.of("--chess-tweek.puzzle-corpus.zstd-path=" + directory.resolve("missing/zstd")))) {
            PuzzleCorpus corpus = application.getBean(PuzzleCorpus.class);
            assertThat(corpus.status().zstdAvailable()).isFalse();
            corpus.checkForUpdate();
            assertThat(corpus.startImport()).isEqualTo(PuzzleCorpus.StartResult.ZSTD_MISSING);
            assertThat(corpus.status().latestImport()).isNull();
        }
        assertThat(lichess.gets).hasValue(0);
    }

    @Test
    void selectsRandomAndCategoryPuzzlesWithIndexSeeksOnly() {
        try (var application = start(database(), List.of())) {
            JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
            List<String> random = plan(jdbc, """
                    SELECT puzzle_id FROM training_puzzle_current
                    WHERE puzzle_id >= 5 ORDER BY puzzle_id LIMIT 1
                    """);
            List<String> randomBound = plan(jdbc,
                    "SELECT max(puzzle_id) FROM training_puzzle_current");
            List<String> category = plan(jdbc, """
                    SELECT puzzle_id FROM training_puzzle_category_member
                    WHERE category_id = 3 AND puzzle_id >= 5 ORDER BY puzzle_id LIMIT 1
                    """);
            List<String> categoryBound = plan(jdbc, """
                    SELECT max(puzzle_id) FROM training_puzzle_category_member
                    WHERE category_id = 3
                    """);
            List<String> removal = plan(jdbc, """
                    SELECT puzzle_id FROM training_puzzle_current
                    WHERE (corpus_import_id, puzzle_id) > (0, 0) AND corpus_import_id < 2
                    ORDER BY corpus_import_id, puzzle_id LIMIT 10
                    """);
            assertThat(random).anyMatch(line -> line.contains("INTEGER PRIMARY KEY"));
            assertThat(category).anyMatch(line -> line.contains("PRIMARY KEY"));
            assertThat(categoryBound).anyMatch(line -> line.contains("PRIMARY KEY"));
            assertThat(removal).anyMatch(line -> line.contains("training_puzzle_current_import"));
            for (List<String> plan : List.of(random, randomBound, category, categoryBound, removal)) {
                assertThat(plan).noneMatch(line -> line.contains("TEMP B-TREE"));
                assertThat(plan).noneMatch(line -> line.matches(".*SCAN training_puzzle_\\w+$"));
            }
        }
    }

    private static List<String> plan(JdbcTemplate jdbc, String sql) {
        return jdbc.query("EXPLAIN QUERY PLAN " + sql, (row, number) -> row.getString("detail"));
    }

    private static PuzzleCorpus.ImportProgress progress(PuzzleCorpus corpus) {
        return corpus.status().latestImport();
    }

    private static PuzzleCorpus.ImportProgress awaitStopped(PuzzleCorpus corpus)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            PuzzleCorpus.ImportProgress progress = progress(corpus);
            // A verified import deletes its source just after the verified state commits.
            if (progress != null && switch (progress.phase()) {
                case PAUSED, FAILED -> true;
                case VERIFIED -> progress.sourceDeleted();
                default -> false;
            }) {
                return progress;
            }
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private List<Path> corpusFiles() throws IOException {
        Path files = directory.resolve("corpus");
        if (!Files.exists(files)) {
            return List.of();
        }
        try (var listing = Files.list(files)) {
            return listing.toList();
        }
    }

    private Path database() {
        return directory.resolve("runtime/chess-tweek.db");
    }

    private ConfigurableApplicationContext start(Path database, List<String> extra) {
        List<String> arguments = new ArrayList<>(List.of(
                "--chess-tweek.database.path=" + database,
                "--chess-tweek.puzzle-corpus.source-url=" + lichess.url(),
                "--chess-tweek.puzzle-corpus.directory=" + directory.resolve("corpus"),
                "--chess-tweek.puzzle-corpus.required-free-bytes=0",
                "--chess-tweek.puzzle-corpus.minimum-free-bytes=0",
                "--chess-tweek.puzzle-corpus.download-stall-seconds=1",
                "--logging.level.root=OFF"));
        for (String override : extra) {
            String key = override.substring(0, override.indexOf('=') + 1);
            arguments.removeIf(argument -> argument.startsWith(key));
            arguments.add(override);
        }
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
    }

    private static String record(String id, int rating, String themes, String openings) {
        return "%s,%s,%s,%d,70,95,500,%s,https://lichess.org/game%s,%s,"
                .formatted(id, FEN, MOVES, rating, themes, id, openings);
    }

    private byte[] snapshot(String... records) throws IOException, InterruptedException {
        Path csv = Files.createTempFile(directory, "snapshot", ".csv");
        Files.writeString(csv, HEADER + "\n" + String.join("\n", records) + "\n");
        Process process = new ProcessBuilder(ZSTD, "-q", "-c", "--", csv.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        byte[] compressed = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        Files.delete(csv);
        return compressed;
    }

    /** A loopback stand-in for the official file: HEAD metadata, GET, and byte ranges. */
    private static final class FakeLichess implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger heads = new AtomicInteger();
        private final AtomicInteger gets = new AtomicInteger();
        private final AtomicBoolean truncateNextGet = new AtomicBoolean();
        private final List<String> ranges = new CopyOnWriteArrayList<>();
        private volatile byte[] body = new byte[0];
        private volatile String etag = "\"none\"";
        private volatile String lastModified = "";

        FakeLichess() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/lichess_db_puzzle.csv.zst", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/lichess_db_puzzle.csv.zst";
        }

        void publish(byte[] snapshot, String newEtag, String modified) {
            body = snapshot;
            etag = newEtag;
            lastModified = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    ZonedDateTime.parse(modified).withZoneSameInstant(ZoneOffset.UTC));
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] current = body;
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Last-Modified", lastModified);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                heads.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(current.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            gets.incrementAndGet();
            String range = exchange.getRequestHeaders().getFirst("Range");
            String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
            int offset = 0;
            int status = 200;
            if (range != null) {
                ranges.add(range);
                if (etag.equals(ifRange)) {
                    offset = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
                    status = 206;
                    exchange.getResponseHeaders().set("Content-Range",
                            "bytes %d-%d/%d".formatted(offset, current.length - 1, current.length));
                }
            }
            int length = current.length - offset;
            exchange.sendResponseHeaders(status, length);
            try (OutputStream output = exchange.getResponseBody()) {
                if (truncateNextGet.getAndSet(false)) {
                    output.write(current, offset, length / 2);
                    output.flush();
                    throw new IOException("Simulated connection loss");
                }
                output.write(current, offset, length);
            } catch (IOException exception) {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
