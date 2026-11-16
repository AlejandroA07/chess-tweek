# Chess.com interface

Stage 5 Part 3 puts chess.com games in the interface. With it, Stage 5 is implemented.

## Admin

A Chess.com account card above Stockfish (`AdminChessCom.tsx`) reads, saves, and removes the username through `/api/chesscom/player`. The input is checked against the same rule as the server (3 to 25 letters, digits, `_`, `-`) before anything is sent, and surrounding spaces are trimmed. Removing the username keeps every stored chess.com game. Loading, Retry, saving, removing, and session loss are covered.

## Games

- The library heading is now "Stored games" with two actions: Get Lichess Games and Get Chess.com Games. Focus returns to the button that opened the panel.
- `GameRetrievalPanel` serves both sites (`site` prop). The chess.com panel reads the saved username first; without one it links to Admin instead of showing the form. It explains that chess.com publishes games with a delay of up to a day and stores only standard chess, and reports skipped games, an unknown player, the rate-limit wait, and other failures without changing the library.
- Every card shows its site as a bordered text badge next to the date.
- A Site filter (All sites, Lichess, Chess.com) is the first filter and is sent as `source` to `/api/games`. The Correspondence speed option notes that it holds chess.com Daily games.
- An opening known only by its ECO code shows the code alone (`openingLabel`), on cards and on the review page. Before this, such a card made the whole library response invalid.

## Dashboard

Below the Lichess ratings, a Chess.com ratings section (`DashboardChessComRatings`) reads `/api/dashboard/rating-context?source=chesscom` and shows Rapid, Blitz, and Bullet: the latest rating in the stored games, labeled as such, with the stored history. It shows no bot record (chess.com archives contain no computer games). Without rated chess.com games it explains how to add them; cards appear only after loading, so nothing reads "Not rated" by mistake.

## Server

- `GameCatalogQuery` has an optional `source`; `/api/games?source=lichess|chesscom` filters by site and rejects any other value with `400 invalid_catalog_filter` before the session check.
- `GameRatingContext.ratingContext(accountId, source)` builds history and bot record from one site's games; `/api/dashboard/rating-context?source=` defaults to `lichess` and answers `400 invalid_rating_context_request` for an unknown site before the session check.

Analysis and game review already worked for chess.com games after Part 1; review links are `#/games/chesscom/{id}`.

## Tests

- Backend: the source filter in `GameCatalogControllerTest` and `GameCatalogPersistenceTest`; per-site ratings in `DashboardRatingContextControllerTest` and `GameRatingContextPersistenceTest`.
- Frontend: `ChessCom.test.tsx` (12) covers the Admin card, the chess.com retrieval panel, the Dashboard section, and an ECO-only opening; `App.test.tsx` checks the new heading and button.

The connected screens were not driven in a browser by the agent (they need the owner's Lichess session); the owner's live check covers them.
