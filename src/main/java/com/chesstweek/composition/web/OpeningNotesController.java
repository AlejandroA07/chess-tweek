package com.chesstweek.composition.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chesstweek.identity.LichessSessionAuthorizer;
import com.chesstweek.identity.LichessSessionService;
import com.chesstweek.shared.chess.ExplorerPosition;
import com.chesstweek.training.OpeningNotes;

/**
 * Opening notes for the explorer: the starter notes and the owner's own notes, one per position.
 * Writes are same-origin only and validate the position and text before the session check.
 */
@RestController
public final class OpeningNotesController {

    private final LichessSessionAuthorizer sessionAuthorizer;
    private final OpeningNotes openingNotes;

    OpeningNotesController(LichessSessionAuthorizer sessionAuthorizer, OpeningNotes openingNotes) {
        this.sessionAuthorizer = sessionAuthorizer;
        this.openingNotes = openingNotes;
    }

    @GetMapping("/api/explorer/notes")
    public ResponseEntity<?> note(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "fen", defaultValue = "") String fen) {
        Optional<ExplorerPosition> position = ExplorerPosition.parse(fen);
        if (position.isEmpty()) {
            return TrainingRequests.error(HttpStatus.BAD_REQUEST, TrainingErrorCodes.INVALID_REQUEST);
        }
        return TrainingRequests.read(sessionAuthorizer, sealedSession, accountId -> TrainingRequests.ok(
                new NoteResponse(openingNotes.note(accountId, position.get()).map(NoteBody::from).orElse(null))));
    }

    @PutMapping("/api/explorer/notes")
    public ResponseEntity<?> save(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestBody(required = false) SaveRequest body) {
        Optional<ExplorerPosition> position = body == null
                ? Optional.empty()
                : ExplorerPosition.parse(body.fen());
        boolean valid = position.isPresent() && OpeningNotes.validText(body.text());
        return TrainingRequests.write(request, sessionAuthorizer, sealedSession, valid, accountId -> {
            openingNotes.save(accountId, position.get(), body.text());
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        });
    }

    @DeleteMapping("/api/explorer/notes")
    public ResponseEntity<?> reset(
            HttpServletRequest request,
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession,
            @RequestParam(name = "fen", defaultValue = "") String fen) {
        Optional<ExplorerPosition> position = ExplorerPosition.parse(fen);
        return TrainingRequests.write(request, sessionAuthorizer, sealedSession, position.isPresent(), accountId -> {
            openingNotes.reset(accountId, position.get());
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        });
    }

    @GetMapping("/api/explorer/notes/index")
    public ResponseEntity<?> index(
            @CookieValue(name = LichessSessionService.COOKIE_NAME, required = false)
                    String sealedSession) {
        return TrainingRequests.read(sessionAuthorizer, sealedSession, accountId -> TrainingRequests.ok(
                new IndexResponse(openingNotes.index(accountId).stream()
                        .map(entry -> new IndexEntryBody(
                                sourceName(entry.source()),
                                entry.opening(),
                                entry.title(),
                                entry.movesSan(),
                                entry.movesUci(),
                                entry.fen(),
                                entry.excerpt()))
                        .toList())));
    }

    private static String sourceName(OpeningNotes.Source source) {
        return source.name().toLowerCase(Locale.ROOT);
    }

    public record SaveRequest(String fen, String text) {}

    public record StarterBody(String opening, String title, List<String> moves, String text) {}

    public record NoteBody(
            String source, String opening, String title, String text, StarterBody starter, Instant updatedAt) {

        static NoteBody from(OpeningNotes.Note note) {
            OpeningNotes.StarterNote starter = note.starter();
            return new NoteBody(
                    sourceName(note.source()),
                    note.opening(),
                    note.title(),
                    note.text(),
                    starter == null
                            ? null
                            : new StarterBody(starter.opening(), starter.title(), starter.moves(), starter.text()),
                    note.updatedAt());
        }
    }

    public record NoteResponse(NoteBody note) {}

    public record IndexEntryBody(
            String source,
            String opening,
            String title,
            List<String> moves,
            List<String> uciMoves,
            String fen,
            String excerpt) {}

    public record IndexResponse(List<IndexEntryBody> entries) {}
}
