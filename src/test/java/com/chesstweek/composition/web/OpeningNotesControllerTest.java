package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chesstweek.identity.LichessSessionAuthorization;
import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningNotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpeningNotesControllerTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final Cookie SESSION = new Cookie("__Host-chess_tweek_session", "sealed-session");
    private static final LichessSessionAuthorizer CONNECTED = sealed ->
            LichessSessionAuthorization.connected("owner-id", "ChessOwner", "liu_token");

    @Test
    void returnsTheNoteAtAPositionWithItsStarterNote() throws Exception {
        var notes = new FakeNotes();
        notes.current = new OpeningNotes.Note(
                OpeningNotes.Source.OWNER, "London System", "The London setup", "Mine",
                new OpeningNotes.StarterNote("London System", "The London setup", List.of("d4", "d5", "Bf4"), List.of("d2d4", "d7d5", "c1f4"), "Starter"),
                Instant.parse("2026-11-27T10:00:00Z"));

        mvc(CONNECTED, notes).perform(get("/api/explorer/notes").param("fen", START).cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note.source").value("owner"))
                .andExpect(jsonPath("$.note.text").value("Mine"))
                .andExpect(jsonPath("$.note.starter.moves[2]").value("Bf4"))
                .andExpect(jsonPath("$.note.updatedAt").value("2026-11-27T10:00:00Z"));
        assertThat(notes.calls).containsExactly("note owner-id");

        notes.current = null;
        mvc(CONNECTED, notes).perform(get("/api/explorer/notes").param("fen", START).cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").isEmpty());
    }

    @Test
    void savesAndResetsOnlyFromTheAppWithValidInput() throws Exception {
        var notes = new FakeNotes();
        MockMvc mvc = mvc(CONNECTED, notes);

        mvc.perform(put("/api/explorer/notes").cookie(SESSION).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"" + START + "\",\"text\":\"Plan\\nTwo\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/explorer/notes").param("fen", START).cookie(SESSION).header("Origin", ORIGIN))
                .andExpect(status().isNoContent());
        assertThat(notes.calls).containsExactly("save owner-id Plan\nTwo", "reset owner-id");

        mvc.perform(put("/api/explorer/notes").cookie(SESSION).header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"" + START + "\",\"text\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/explorer/notes").param("fen", START).cookie(SESSION))
                .andExpect(status().isForbidden());
        for (String body : List.of(
                "{\"fen\":\"bad\",\"text\":\"x\"}",
                "{\"fen\":\"" + START + "\"}",
                "{\"fen\":\"" + START + "\",\"text\":\"" + "x".repeat(4001) + "\"}",
                "{\"fen\":\"" + START + "\",\"text\":\"bell\\u0007\"}")) {
            mvc.perform(put("/api/explorer/notes").cookie(SESSION).header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(notes.calls).hasSize(2);
    }

    @Test
    void requiresAConnectedSession() throws Exception {
        LichessSessionAuthorizer disconnected = sealed -> LichessSessionAuthorization.disconnected(false);
        var notes = new FakeNotes();
        MockMvc mvc = mvc(disconnected, notes);

        mvc.perform(get("/api/explorer/notes").param("fen", START)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/explorer/notes/index")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/explorer/notes").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"" + START + "\",\"text\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(notes.calls).isEmpty();
    }

    @Test
    void listsNotedPositions() throws Exception {
        var notes = new FakeNotes();

        mvc(CONNECTED, notes).perform(get("/api/explorer/notes/index").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].source").value("starter"))
                .andExpect(jsonPath("$.entries[0].moves[0]").value("e4"))
                .andExpect(jsonPath("$.entries[0].uciMoves[1]").value("c7c6"))
                .andExpect(jsonPath("$.entries[1].source").value("owner"))
                .andExpect(jsonPath("$.entries[1].title").isEmpty());
    }

    private static MockMvc mvc(LichessSessionAuthorizer authorizer, OpeningNotes notes) {
        return MockMvcBuilders.standaloneSetup(new OpeningNotesController(authorizer, notes)).build();
    }

    private static final class FakeNotes implements OpeningNotes {

        private final List<String> calls = new ArrayList<>();
        private Note current;

        @Override
        public Optional<Note> note(String accountId, ExplorerPosition position) {
            calls.add("note " + accountId);
            return Optional.ofNullable(current);
        }

        @Override
        public void save(String accountId, ExplorerPosition position, String text) {
            calls.add("save " + accountId + " " + text);
        }

        @Override
        public void reset(String accountId, ExplorerPosition position) {
            calls.add("reset " + accountId);
        }

        @Override
        public List<IndexEntry> index(String accountId) {
            return List.of(
                    new IndexEntry(Source.STARTER, "Caro-Kann Defense", "The Caro-Kann idea", List.of("e4", "c6"), List.of("e2e4", "c7c6"), START, "Black prepares"),
                    new IndexEntry(Source.OWNER, null, null, List.of(), List.of(), START, "Mine"));
        }
    }
}
