# Chess Tweek

Chess Tweek is a local-first chess analysis and training application for macOS. It imports completed Lichess games, analyzes selected games with native Stockfish, identifies recurring weaknesses, and turns those findings into focused practice.

## Status

The verified macOS application foundation is complete. The next slice is the Lichess connection; the app is not deployed and does not provide live-game assistance or in-app play.

## Product principles

- Game data, analysis results, reports, training history, and backups stay on the local computer.
- Lichess retrieval and Stockfish analysis happen only when explicitly requested.
- Analysis findings remain reproducible through versioned engine profiles and immutable reports.
- Features are delivered as small, testable vertical slices.
