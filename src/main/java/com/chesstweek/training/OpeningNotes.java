package com.chesstweek.training;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.chesstweek.shared.chess.ExplorerPosition;

/**
 * Notes on opening positions: the project's starter notes for the London System and the
 * Caro-Kann Defense, and the owner's own notes, which replace a starter note or add one elsewhere.
 */
public interface OpeningNotes {

    /** The longest note the owner can save. */
    int MAX_TEXT_LENGTH = 4000;

    /**
     * The note at a position, if any. An owner note with empty text means the owner hid the starter
     * note there; {@link Note#starter()} then holds what a reset brings back.
     */
    Optional<Note> note(String accountId, ExplorerPosition position);

    /**
     * Saves the owner's text for a position. Blank text hides a starter note there, or removes an
     * owner note where there is no starter note.
     *
     * @throws IllegalArgumentException when the text is too long or holds control characters
     */
    void save(String accountId, ExplorerPosition position, String text);

    /** Forgets the owner's text for a position, so a starter note there shows again. */
    void reset(String accountId, ExplorerPosition position);

    /** Every position with a visible note: starter notes in their authored order, then the owner's. */
    List<IndexEntry> index(String accountId);

    enum Source {
        STARTER,
        OWNER
    }

    /**
     * A visible note. {@code starter} is the starter note at this position, if there is one, so the
     * owner can see what a reset brings back.
     */
    record Note(Source source, String opening, String title, String text, StarterNote starter, Instant updatedAt) {

        public Note {
            Objects.requireNonNull(source);
            Objects.requireNonNull(text);
        }
    }

    /** A shipped note, written for this project, with its moves from the start in SAN and UCI. */
    record StarterNote(String opening, String title, List<String> moves, List<String> uciMoves, String text) {

        public StarterNote {
            Objects.requireNonNull(opening);
            Objects.requireNonNull(title);
            moves = List.copyOf(moves);
            uciMoves = List.copyOf(uciMoves);
            if (moves.size() != uciMoves.size()) {
                throw new IllegalArgumentException("Each move needs both notations");
            }
            Objects.requireNonNull(text);
        }
    }

    /**
     * A position with a note. Starter entries carry their moves from the start position; an owner
     * note elsewhere carries only the FEN it was saved at.
     */
    record IndexEntry(
            Source source,
            String opening,
            String title,
            List<String> movesSan,
            List<String> movesUci,
            String fen,
            String excerpt) {

        public IndexEntry {
            Objects.requireNonNull(source);
            movesSan = List.copyOf(movesSan);
            movesUci = List.copyOf(movesUci);
            Objects.requireNonNull(fen);
            Objects.requireNonNull(excerpt);
        }
    }

    /** Whether text may be saved as a note: bounded, with only line breaks and tabs as controls. */
    static boolean validText(String text) {
        return text != null
                && text.length() <= MAX_TEXT_LENGTH
                && text.codePoints().noneMatch(point ->
                        Character.isISOControl(point) && point != '\n' && point != '\t');
    }
}
