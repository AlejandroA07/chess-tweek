# Openings explorer page and starter notes

Stage 10 Part 2 adds the Openings destination: the explorer board over the Part 1 backend, and opening notes with starter notes for the London System and the Caro-Kann Defense (`docs/planning/03-delivery-plan.md`, Stage 10). No dependency was added.

Owner decisions, 2026-11-27:

- Openings is a seventh navigation item, after Puzzles. `DESIGN.md` and `AGENTS.md` were updated, and `DESIGN.md` has a new "Openings view" section.
- Notes belong to positions and are saved in the local database per account.
- The Lichess database starts with Rapid games in the 1600 and 1800 rating groups.

## Routes

- `#/openings` is the home page: an intro card and the list of positions with notes.
- `#/openings/explore[/from/{fen}][/line/{uci,...}]` is the explorer. The start position is the standard one unless `from` names another. The line holds at most 200 moves. The route is parsed strictly; anything else falls back to the Dashboard like other unknown routes.
- The explorer hides the sidebar like other board views and leads with "Back to Openings".

## Line

- `GET /api/explorer/line?from=&moves=e2e4,c7c6` plays the line on the server and returns each move in SAN with the FEN it reaches. The frontend's own move helper does not track castling rights or move counters, so the server is the only source of positions.
- `shared.chess.ExplorerLine` accepts castling written as the king taking its own rook (`e1h1`), the form the Lichess explorer can return, and stores it as `e1g1`.
- Back, Forward, and the move buttons move through the line without new line requests. A move played from an earlier position replaces the rest of the line.

## Notes

- `src/main/resources/training/opening-starter-notes.json` holds 10 starter notes written for this project (4 London, 6 Caro-Kann). They describe plans only. Each is keyed by the position its moves reach. The load fails at startup if a note has illegal moves, a duplicate position, or invalid text.
- Migration V21 adds `training_opening_note`, the owner's text per account and position key, with the FEN so the position can be opened from the notes list.
  - Saving replaces the starter note at that position.
  - Saving blank text hides the starter note there, or removes an owner note elsewhere.
  - Reset (`DELETE`) brings the starter note back.
- Text is at most 4,000 characters. Line breaks and tabs are the only control characters allowed.
- Endpoints:
  - `GET /api/explorer/notes?fen=`
  - `PUT /api/explorer/notes` with `{fen, text}`
  - `DELETE /api/explorer/notes?fen=`
  - `GET /api/explorer/notes/index`
- Writes are same-origin only. Every endpoint validates the position and text before checking the session, and uses the training error codes.
- The index lists starter notes in authored order, with their SAN and UCI moves, then the owner's notes elsewhere, oldest first.

## Page

- The board, Line, Note, and "Games from this position" panels follow `DESIGN.md`, "Openings view".
- Statistics requests are cancelled when the position, database, or filters change, so an older answer never replaces a newer one.
- A rate limit names the wait and says that My games still works. A stale answer gives its saved date.
- While the note editor is open, the board, the line, and the move buttons are disabled, so moving to another position never discards a draft.
- Restoring a starter note over the owner's own text confirms first. Restoring a hidden starter note does not.
- Move numbers start from the move number of the start position, so a line opened from a saved FEN is numbered correctly.
- `ExplorerLine` reads king-takes-rook as castling only when the king stands on its start square on the e-file.

## Tests

- Backend:
  - `ExplorerLineTest`: SAN and positions, castling as king-takes-rook (only from the king's start square), and rejected lines.
  - `StarterNotesLoaderTest`: the shipped notes, and rejection of illegal, duplicate, or invalid notes.
  - `OpeningNotesPersistenceTest`: starter, owner, hidden, and restored notes across a restart, account scoping, the index, and text limits.
  - `OpeningNotesControllerTest`: responses, same-origin writes, validation, and the session requirement.
  - `OpeningExplorerControllerTest`: the line endpoint.
- Frontend:
  - `Openings.test.tsx`: route parsing, the notes list, Masters statistics with the line and starter note, playing and stepping through moves, the default Lichess filter, the rate-limit message with My games still working, stale answers, editing, hiding, and restoring a starter note (with confirmation over own text), the editing lock, move numbers from a saved position, delete confirmation, a failed save, and a lost session.
  - `App.test.tsx`: the navigation order.
