package com.chesstweek.training.internal.notes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.wolfraam.chessgame.ChessGame;
import io.github.wolfraam.chessgame.move.Move;
import io.github.wolfraam.chessgame.notation.NotationType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningNotes;

/** Reads the shipped starter notes and keys each by the position its moves reach. */
public final class StarterNotesLoader {

    public static final String RESOURCE = "/training/opening-starter-notes.json";
    private static final int MAX_MOVES = 40;

    private StarterNotesLoader() {}

    /** The starter notes by position key, in authored order; any invalid note fails the load. */
    public static Map<String, OpeningNotes.StarterNote> load() {
        try (InputStream input = StarterNotesLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Starter opening notes are missing");
            }
            ObjectMapper mapper = JsonMapper.builder().build();
            return keyed(mapper.readValue(input, Definition.class));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Starter opening notes are invalid", exception);
        }
    }

    static Map<String, OpeningNotes.StarterNote> keyed(Definition definition) {
        if (definition == null || definition.version() != 1 || definition.notes() == null
                || definition.notes().isEmpty()) {
            throw new IllegalArgumentException("Unsupported starter notes");
        }
        var notes = new LinkedHashMap<String, OpeningNotes.StarterNote>();
        for (NoteDefinition note : definition.notes()) {
            if (note == null || !label(note.opening()) || !label(note.title())
                    || note.text() == null || note.text().isBlank()
                    || !OpeningNotes.validText(note.text()) || note.moves() == null) {
                throw new IllegalArgumentException("Invalid starter note");
            }
            var game = new ChessGame();
            var sans = new ArrayList<String>();
            var ucis = new ArrayList<String>();
            String[] moves = note.moves().strip().split("\\s+");
            if (moves.length == 0 || moves.length > MAX_MOVES) {
                throw new IllegalArgumentException("Invalid starter note moves");
            }
            for (String san : moves) {
                Move move = game.getMove(NotationType.SAN, san);
                if (move == null || !game.isLegalMove(move)) {
                    throw new IllegalArgumentException("Illegal starter note move: " + san);
                }
                sans.add(game.getNotation(NotationType.SAN, move));
                ucis.add(game.getNotation(NotationType.UCI, move));
                game.playMove(move);
            }
            String key = ExplorerPosition.of(game).key();
            var starter = new OpeningNotes.StarterNote(note.opening(), note.title(), sans, ucis, note.text());
            if (notes.putIfAbsent(key, starter) != null) {
                throw new IllegalArgumentException("Two starter notes share a position");
            }
        }
        return notes;
    }

    private static boolean label(String value) {
        return value != null && !value.isBlank() && value.length() <= 80
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    record Definition(int version, List<NoteDefinition> notes) {}

    record NoteDefinition(String opening, String title, String moves, String text) {}
}
