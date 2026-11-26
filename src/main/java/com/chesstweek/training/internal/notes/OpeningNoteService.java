package com.chesstweek.training.internal.notes;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningNotes;
import com.chesstweek.training.internal.persistence.OpeningNoteStore;

@Service
public class OpeningNoteService implements OpeningNotes {

    private static final int EXCERPT_LENGTH = 140;

    private final OpeningNoteStore store;
    private final Map<String, StarterNote> starters;
    /** The starter positions in authored order; {@code starters} itself does not keep order. */
    private final List<String> orderedStarterKeys;
    private final Clock clock;

    @Autowired
    public OpeningNoteService(OpeningNoteStore store) {
        this(store, StarterNotesLoader.load(), Clock.systemUTC());
    }

    OpeningNoteService(OpeningNoteStore store, Map<String, StarterNote> starters, Clock clock) {
        this.store = store;
        this.starters = Map.copyOf(starters);
        this.clock = clock;
        this.orderedStarterKeys = List.copyOf(starters.keySet());
    }

    @Override
    public Optional<Note> note(String accountId, ExplorerPosition position) {
        Objects.requireNonNull(position);
        StarterNote starter = starters.get(position.key());
        Optional<OpeningNoteStore.StoredNote> own = store.find(accountId, position.key());
        if (own.isPresent()) {
            if (own.get().text().isBlank() && starter == null) {
                return Optional.empty();
            }
            return Optional.of(new Note(
                    Source.OWNER,
                    starter == null ? null : starter.opening(),
                    starter == null ? null : starter.title(),
                    own.get().text(),
                    starter,
                    own.get().updatedAt()));
        }
        return Optional.ofNullable(starter).map(note -> new Note(
                Source.STARTER, note.opening(), note.title(), note.text(), note, null));
    }

    @Override
    public void save(String accountId, ExplorerPosition position, String text) {
        Objects.requireNonNull(position);
        if (!OpeningNotes.validText(text)) {
            throw new IllegalArgumentException("Invalid note text");
        }
        boolean hasStarter = starters.containsKey(position.key());
        if (text.isBlank() && !hasStarter) {
            store.delete(accountId, position.key());
            return;
        }
        store.put(accountId, position.key(), position.fen(), text.isBlank() ? "" : text, clock.instant());
    }

    @Override
    public void reset(String accountId, ExplorerPosition position) {
        Objects.requireNonNull(position);
        store.delete(accountId, position.key());
    }

    @Override
    public List<IndexEntry> index(String accountId) {
        var owned = new LinkedHashMap<String, OpeningNoteStore.StoredNote>();
        for (OpeningNoteStore.StoredNote note : store.all(accountId)) {
            owned.put(note.positionKey(), note);
        }
        var entries = new ArrayList<IndexEntry>();
        for (String key : orderedStarterKeys) {
            StarterNote starter = starters.get(key);
            OpeningNoteStore.StoredNote own = owned.remove(key);
            if (own != null && own.text().isBlank()) {
                continue;
            }
            String fen = own == null ? startFenOf(key) : own.fen();
            entries.add(new IndexEntry(
                    own == null ? Source.STARTER : Source.OWNER,
                    starter.opening(),
                    starter.title(),
                    starter.moves(),
                    starter.uciMoves(),
                    fen,
                    excerpt(own == null ? starter.text() : own.text())));
        }
        for (OpeningNoteStore.StoredNote own : owned.values()) {
            if (!own.text().isBlank()) {
                entries.add(new IndexEntry(Source.OWNER, null, null, List.of(), List.of(), own.fen(), excerpt(own.text())));
            }
        }
        return entries;
    }

    /** A FEN for a starter position, rebuilt from its key with zeroed move counters. */
    private static String startFenOf(String key) {
        return key + " 0 1";
    }

    private static String excerpt(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH - 1).stripTrailing() + "…";
    }
}
