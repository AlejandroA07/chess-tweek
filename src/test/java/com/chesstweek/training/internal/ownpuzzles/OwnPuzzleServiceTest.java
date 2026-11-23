package com.chesstweek.training.internal.ownpuzzles;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.training.OwnPuzzles;
import com.chesstweek.training.OwnPuzzles.NewOwnPuzzle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnPuzzleServiceTest {

    private static final String OWNER = "owner-id";
    private static final String OTHER = "other-id";
    /** After 1.e4 e5 2.Nf3: Black to move. */
    private static final String BEFORE_NC6 =
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2";
    /** After 1.e4 e5 2.Nf3 Nc6: White to move at ply 5. */
    private static final String AFTER_NC6 =
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3";
    private static final String START =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final Instant PLAYED = Instant.parse("2026-11-20T18:30:00Z");

    @TempDir
    Path directory;

    @Test
    void storesThePositionTheFirstThreeMovesOfTheTopLineAndItsGrouping() {
        try (var application = start()) {
            OwnPuzzles puzzles = application.getBean(OwnPuzzles.class);

            int created = puzzles.create(OWNER, List.of(puzzle(5, List.of("f1b5", "a7a6", "b5a4", "g8f6"))));

            assertThat(created).isEqualTo(1);
            Map<String, Object> row = jdbc(application).queryForMap(
                    "SELECT * FROM training_own_puzzle WHERE account_id = ?", OWNER);
            assertThat(row).containsEntry("game_source", "lichess")
                    .containsEntry("game_id", "abcd1234")
                    .containsEntry("source_ply", 5)
                    .containsEntry("analysis_version_id", 3)
                    .containsEntry("position_fen", AFTER_NC6)
                    .containsEntry("previous_move_uci", "b8c6")
                    .containsEntry("solution_uci", "f1b5 a7a6 b5a4")
                    .containsEntry("label", "mistake")
                    .containsEntry("phase", "opening")
                    .containsEntry("opening_eco", "C44")
                    .containsEntry("opening_name", "King's Pawn Game")
                    .containsEntry("game_played_at_ms", PLAYED.toEpochMilli());
            assertThat(jdbc(application).queryForList(
                    "SELECT theme || '/' || kind FROM training_own_puzzle_tactic", String.class))
                    .containsExactly("fork/missed");
        }
    }

    @Test
    void creatingTheSamePuzzlesAgainStoresNothingNew() {
        try (var application = start()) {
            OwnPuzzles puzzles = application.getBean(OwnPuzzles.class);
            var line = List.of(puzzle(5, List.of("f1b5", "a7a6", "b5a4")));

            assertThat(puzzles.create(OWNER, line)).isEqualTo(1);
            assertThat(puzzles.create(OWNER, line)).isZero();

            assertThat(count(application, OWNER)).isEqualTo(1);
            assertThat(jdbc(application).queryForObject(
                    "SELECT COUNT(*) FROM training_own_puzzle_tactic", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void aLineShorterThanThreeMovesBecomesAOneMovePuzzle() {
        try (var application = start()) {
            OwnPuzzles puzzles = application.getBean(OwnPuzzles.class);

            assertThat(puzzles.create(OWNER, List.of(
                    puzzle(5, List.of("f1c4")),
                    puzzle(7, List.of("d2d4", "e5d4"))))).isEqualTo(2);

            assertThat(jdbc(application).queryForList(
                    "SELECT solution_uci FROM training_own_puzzle ORDER BY source_ply", String.class))
                    .containsExactly("f1c4", "d2d4");
        }
    }

    @Test
    void theGamesFirstMoveHasNoPreviousMove() {
        try (var application = start()) {
            var first = new NewOwnPuzzle("lichess", "abcd1234", 1, 3, START, null, null,
                    List.of("e2e4", "e7e5", "g1f3"), "inaccuracy", "opening", null, null, PLAYED,
                    List.of());

            assertThat(application.getBean(OwnPuzzles.class).create(OWNER, List.of(first)))
                    .isEqualTo(1);

            Map<String, Object> row = jdbc(application).queryForMap(
                    "SELECT previous_move_uci, opening_eco FROM training_own_puzzle");
            assertThat(row.get("previous_move_uci")).isNull();
            assertThat(row.get("opening_eco")).isNull();
        }
    }

    @Test
    void skipsPuzzlesWhoseLineOrPreviousMoveCannotBeReplayed() {
        try (var application = start()) {
            OwnPuzzles puzzles = application.getBean(OwnPuzzles.class);

            int created = puzzles.create(OWNER, List.of(
                    // An illegal top move, an illegal later move, and no line at all.
                    puzzle(5, List.of("e1e3")),
                    puzzle(7, List.of("f1b5", "a7a6", "b5b7")),
                    puzzle(9, List.of()),
                    // A previous move that does not lead to the puzzle's position.
                    withPrevious(puzzle(11, List.of("f1c4")), BEFORE_NC6, "g8f6"),
                    withPrevious(puzzle(13, List.of("f1c4")), BEFORE_NC6, null),
                    puzzle(15, List.of("f1c4"))));

            assertThat(created).isEqualTo(1);
            assertThat(jdbc(application).queryForList(
                    "SELECT source_ply FROM training_own_puzzle", Integer.class)).containsExactly(15);
        }
    }

    @Test
    void refusesInvalidAccountsAndFieldsAndStoresNothing() {
        try (var application = start()) {
            OwnPuzzles puzzles = application.getBean(OwnPuzzles.class);
            NewOwnPuzzle valid = puzzle(5, List.of("f1c4"));

            for (String account : new String[] {null, "", "bad account", "x".repeat(33)}) {
                assertThatThrownBy(() -> puzzles.create(account, List.of(valid)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> puzzles.create(OWNER, null))
                    .isInstanceOf(IllegalArgumentException.class);
            for (NewOwnPuzzle invalid : List.of(
                    copy(valid, "elsewhere", "abcd1234", 5, "mistake", "opening", "C44", "fork"),
                    copy(valid, "lichess", "abc", 5, "mistake", "opening", "C44", "fork"),
                    copy(valid, "chesscom", "abcd1234", 5, "mistake", "opening", "C44", "fork"),
                    copy(valid, "lichess", "abcd1234", 0, "mistake", "opening", "C44", "fork"),
                    copy(valid, "lichess", "abcd1234", 1201, "mistake", "opening", "C44", "fork"),
                    copy(valid, "lichess", "abcd1234", 5, "none", "opening", "C44", "fork"),
                    copy(valid, "lichess", "abcd1234", 5, "mistake", "late", "C44", "fork"),
                    copy(valid, "lichess", "abcd1234", 5, "mistake", "opening", "Z9", "fork"),
                    copy(valid, "lichess", "abcd1234", 5, "mistake", "opening", "C44", "Fork!"))) {
                // One invalid puzzle refuses the whole call, including the valid one before it.
                assertThatThrownBy(() -> puzzles.create(OWNER, List.of(valid, invalid)))
                        .isInstanceOf(IllegalArgumentException.class);
            }

            assertThat(count(application, OWNER)).isZero();
        }
    }

    @Test
    void ownPuzzlesBelongToOneAccount() {
        try (var application = start()) {
            OwnPuzzles puzzles = application.getBean(OwnPuzzles.class);
            var line = List.of(puzzle(5, List.of("f1c4")));

            assertThat(puzzles.create(OWNER, line)).isEqualTo(1);
            assertThat(puzzles.create(OTHER, line)).isEqualTo(1);

            assertThat(count(application, OWNER)).isEqualTo(1);
            assertThat(count(application, OTHER)).isEqualTo(1);
            // They never become rows of the Lichess Puzzle Library.
            assertThat(jdbc(application).queryForObject(
                    "SELECT COUNT(*) FROM training_puzzle WHERE position_fen = ?", Integer.class,
                    AFTER_NC6)).isZero();
        }
    }

    /** White's move at {@code ply}, from the position after 1.e4 e5 2.Nf3 Nc6. */
    private static NewOwnPuzzle puzzle(int ply, List<String> line) {
        return new NewOwnPuzzle("lichess", "abcd1234", ply, 3, AFTER_NC6, BEFORE_NC6, "b8c6", line,
                "mistake", "opening", "C44", "King's Pawn Game", PLAYED,
                List.of(new OwnPuzzles.Tactic("fork", "missed")));
    }

    private static NewOwnPuzzle withPrevious(NewOwnPuzzle puzzle, String fen, String move) {
        return new NewOwnPuzzle(puzzle.gameSource(), puzzle.gameId(), puzzle.sourcePly(),
                puzzle.analysisVersionId(), puzzle.positionFen(), fen, move, puzzle.engineLine(),
                puzzle.label(), puzzle.phase(), puzzle.openingEco(), puzzle.openingName(),
                puzzle.gamePlayedAt(), puzzle.tactics());
    }

    private static NewOwnPuzzle copy(
            NewOwnPuzzle puzzle, String source, String gameId, int ply, String label, String phase,
            String eco, String theme) {
        return new NewOwnPuzzle(source, gameId, ply, puzzle.analysisVersionId(),
                puzzle.positionFen(), puzzle.previousPositionFen(), puzzle.previousMoveUci(),
                puzzle.engineLine(), label, phase, eco, puzzle.openingName(), puzzle.gamePlayedAt(),
                List.of(new OwnPuzzles.Tactic(theme, "missed")));
    }

    private static int count(ConfigurableApplicationContext application, String account) {
        return jdbc(application).queryForObject(
                "SELECT COUNT(*) FROM training_own_puzzle WHERE account_id = ?", Integer.class,
                account);
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext application) {
        return application.getBean(JdbcTemplate.class);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + directory.resolve("runtime/chess-tweek.db"),
                        "--logging.level.root=OFF");
    }
}
