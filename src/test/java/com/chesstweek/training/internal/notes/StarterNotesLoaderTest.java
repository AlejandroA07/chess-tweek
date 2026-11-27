package com.chesstweek.training.internal.notes;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chesstweek.training.OpeningNotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StarterNotesLoaderTest {

    @Test
    void loadsTheShippedLondonAndCaroKannNotes() {
        var notes = StarterNotesLoader.load();

        assertThat(notes.values()).extracting(OpeningNotes.StarterNote::opening)
                .containsOnly("London System", "Caro-Kann Defense");
        assertThat(notes.values()).extracting(OpeningNotes.StarterNote::title)
                .contains("The London setup", "The Caro-Kann idea", "Advance Variation");
        assertThat(notes.values().iterator().next().moves()).containsExactly("d4", "d5", "Bf4");
        assertThat(notes.values().iterator().next().uciMoves()).containsExactly("d2d4", "d7d5", "c1f4");
        assertThat(notes.values()).allSatisfy(note ->
                assertThat(OpeningNotes.validText(note.text())).isTrue());
    }

    @Test
    void rejectsIllegalMovesDuplicatePositionsAndBadText() {
        var illegal = new StarterNotesLoader.Definition(1, List.of(note("e4 e4", "Text")));
        var duplicate = new StarterNotesLoader.Definition(1, List.of(note("Nf3 Nf6 Ng1 Ng8 e4", "A"), note("e4", "B")));
        var control = new StarterNotesLoader.Definition(1, List.of(note("e4", "Bad\u0007text")));
        var version = new StarterNotesLoader.Definition(2, List.of(note("e4", "Text")));

        for (var definition : List.of(illegal, duplicate, control, version)) {
            assertThatThrownBy(() -> StarterNotesLoader.keyed(definition))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private static StarterNotesLoader.NoteDefinition note(String moves, String text) {
        return new StarterNotesLoader.NoteDefinition("Opening", "Title", moves, text);
    }
}
