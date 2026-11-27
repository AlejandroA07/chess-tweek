package com.chesstweek.training;

import java.nio.file.Path;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.notation.NotationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.chesstweek.ChessTweekApplication;
import com.chesstweek.shared.chess.ExplorerPosition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpeningNotesPersistenceTest {

    @Test
    void showsStarterNotesAndLetsTheOwnerReplaceHideAndRestoreThem(@TempDir Path directory) {
        Path database = directory.resolve("runtime/chess-tweek.db");
        ExplorerPosition london = after("d4 d5 Bf4");
        try (var application = start(database)) {
            var notes = application.getBean(OpeningNotes.class);

            var starter = notes.note("owner", london).orElseThrow();
            assertThat(starter.source()).isEqualTo(OpeningNotes.Source.STARTER);
            assertThat(starter.title()).isEqualTo("The London setup");

            notes.save("owner", london, "My plan: Ne5 and f4.");
            var own = notes.note("owner", london).orElseThrow();
            assertThat(own.source()).isEqualTo(OpeningNotes.Source.OWNER);
            assertThat(own.text()).isEqualTo("My plan: Ne5 and f4.");
            assertThat(own.starter().title()).isEqualTo("The London setup");
            assertThat(notes.note("someone-else", london).orElseThrow().source())
                    .isEqualTo(OpeningNotes.Source.STARTER);

            notes.save("owner", london, "  ");
            var hidden = notes.note("owner", london).orElseThrow();
            assertThat(hidden.text()).isEmpty();
            assertThat(notes.index("owner")).noneMatch(entry -> "The London setup".equals(entry.title()));
        }
        try (var restarted = start(database)) {
            var notes = restarted.getBean(OpeningNotes.class);
            assertThat(notes.note("owner", london).orElseThrow().text()).isEmpty();

            notes.reset("owner", london);
            assertThat(notes.note("owner", london).orElseThrow().source()).isEqualTo(OpeningNotes.Source.STARTER);
        }
    }

    @Test
    void addsAndRemovesNotesAtOtherPositionsAndListsThemAfterTheStarterNotes(@TempDir Path directory) {
        ExplorerPosition sicilian = after("e4 c5");
        try (var application = start(directory.resolve("runtime/chess-tweek.db"))) {
            var notes = application.getBean(OpeningNotes.class);
            assertThat(notes.note("owner", sicilian)).isEmpty();

            notes.save("owner", sicilian, "Line one\nLine two");
            var index = notes.index("owner");
            assertThat(index.getFirst().title()).isEqualTo("The London setup");
            assertThat(index.getLast().source()).isEqualTo(OpeningNotes.Source.OWNER);
            assertThat(index.getLast().fen()).isEqualTo(sicilian.fen());
            assertThat(index.getLast().excerpt()).isEqualTo("Line one Line two");
            assertThat(notes.index("someone-else")).noneMatch(entry -> entry.fen().equals(sicilian.fen()));

            notes.save("owner", sicilian, "");
            assertThat(notes.note("owner", sicilian)).isEmpty();

            assertThatThrownBy(() -> notes.save("owner", sicilian, "x".repeat(4001)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> notes.save("owner", sicilian, "bell\u0007"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static ExplorerPosition after(String moves) {
        var game = new ChessGame();
        game.playMoves(NotationType.SAN, moves);
        return ExplorerPosition.of(game);
    }

    private static ConfigurableApplicationContext start(Path database) {
        return new SpringApplicationBuilder(ChessTweekApplication.class)
                .web(WebApplicationType.NONE)
                .run("--chess-tweek.database.path=" + database, "--logging.level.root=OFF");
    }
}
