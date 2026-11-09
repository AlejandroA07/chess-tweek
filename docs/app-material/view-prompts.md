# Google Stitch view prompts

Use one prompt at a time. Each prompt is deliberately standalone so it can begin a fresh Google Stitch generation without depending on another chat. Generate desktop screens only for this first design pass. Save selected results in this folder using the screen names below.

## 1. Lichess connection and first run

```text
Create a high-fidelity 1440px desktop web-app screen for “Chess Tweek,” a local-first macOS chess analysis and training application. The visual tone is a calm professional analysis studio: focused, trustworthy, refined, and welcoming to a learning chess player without looking childish or game-like. Use dark espresso brown for navigation, typography, and primary controls; warm beige for the page canvas; and white or warm off-white for elevated surfaces. Use restrained serif headings and a highly legible sans-serif for interface text. Avoid purple, gradients, neon, glassmorphism, emoji headings, excessive shadows, oversized marketing typography, and generic centered-card SaaS styling. Use clear keyboard focus, accessible contrast, and controls at least 44px high.

Design the first-run “Connect Lichess” view before the main application is available. Use a narrow but substantial left-side brand/story column and a larger right-side setup workspace rather than one centered floating card. The Chess Tweek name and a simple typographic chess mark appear at top left. Explain in plain language that games stay on this Mac, analysis uses local Stockfish, there is no paid AI, and the app never gives help during live games.

The setup workspace must show three ordered readiness rows: “Lichess account,” “Local Stockfish,” and “Local database.” Each row has an icon, status label, short explanation, and room for success, checking, warning, and failure states. The Lichess row contains the primary “Connect with Lichess” button. Stockfish shows either “Ready — Stockfish 18” or a missing-state action “View installation instructions.” Database shows “Local storage ready” and a compact privacy note.

Include these visible controls: Connect with Lichess, Retry connection, View installation instructions, View privacy details, and a disabled Continue button that becomes enabled only when required checks pass. Include a small “What access is requested?” disclosure explaining identity and read-only puzzles, with no play or write permissions. Show a secondary error variation where Lichess authorization was canceled: calm inline error copy, Retry connection, and no raw technical details. Annotate that successful connection transitions to the Dashboard. Do not add sign-up, pricing, testimonials, deployment language, cloud-storage claims, or social login alternatives.
```

## 2. Dashboard

```text
Create a high-fidelity 1440px desktop dashboard for “Chess Tweek,” a local-first macOS chess analysis and training app. Use a calm analysis-studio design: espresso-brown navigation and typography, warm-beige canvas, white/off-white surfaces, restrained serif headings, legible sans-serif body text, subtle borders, and minimal shadows. Avoid gradients, purple, neon, glass effects, emoji, childish badges, confetti, and generic admin-template styling. Every status must use text or icon as well as color.

Use a persistent left navigation rail with Chess Tweek at the top and Dashboard, Games, Reports, Puzzles, and Admin. Dashboard is selected. The top bar shows the connected Lichess username/avatar placeholder, connection status, a small local-only indicator, and current date—no global search.

Build an information-rich but breathable overview. The first row contains current Lichess rating cards for Blitz, Rapid, and Classical with rating, recent change if available, and a small historical sparkline. Do not invent a Chess Tweek rating. Beside them show a “Bot record” card with wins, draws, losses, games counted, and opponent-rating range.

The main action area contains a prominent “Get Lichess Games” button and explanatory text that retrieval is manual. Add a “Recent games” section with five compact game rows/cards showing result, owner color, opponent and rating, clock, opening, date, and analysis status. Each row has Open Game and, when eligible, Analyze.

Add an “Analysis queue” card showing either no active work or current game, side being analyzed, progress, elapsed estimate, and Open Queue. Add “Recent Overall Games Analysis” with the latest saved report cards and Open Report. Add a “Continue training” area with Daily Puzzle, Mixed Puzzles, and any unfinished report-owned puzzle package.

Visible buttons and links: Get Lichess Games, View All Games, Open Game, Analyze, Open Queue, View All Reports, Open Report, Daily Puzzle, Mixed Puzzles, Continue Package, and Open Admin. Include clearly designed empty states for no games, no reports, and no puzzle attempts; a loading skeleton; and a non-blocking Lichess-unavailable banner with Retry Connection. Use realistic chess content such as London System, 5+0 Blitz, and named opponents rather than lorem ipsum.
```

## 3. Games library and Get Lichess Games

```text
Create a high-fidelity 1440px desktop “Games” view for Chess Tweek. The app is a calm professional chess-analysis studio. Use an espresso-brown navigation rail and primary actions, warm-beige page canvas, white/off-white card surfaces, restrained serif page titles, precise sans-serif UI text, subtle borders, and accessible focus states. Avoid purple, gradients, neon, glassmorphism, excessive shadows, emoji, and playful reward styling.

Keep the persistent left navigation with Dashboard, Games, Reports, Puzzles, and Admin; Games is selected. The page header contains “Lichess games,” a short statement that completed games are stored locally, and a primary “Get Lichess Games” button.

When Get Lichess Games is opened, show a right-side panel rather than a modal. It contains a numeric field labeled “Number of games” with helper text “1–50,” an optional “Games before” date picker for older batches, a summary of the connected Lichess account, and buttons Get Games and Cancel. Include progress state “Receiving 18 of 50,” a successful summary with new/updated/duplicate counts, a rate-limit state with retry time, and a safe network error with Retry.

The library toolbar must include: result filter (All, Wins, Losses, Draws), color filter, opening combobox, opponent type (All, Human, Lichess AI, Bot), speed, date range, variant, analysis status, Clear Filters, and sort by newest/oldest. Show the number of matching games. Include checkboxes, Select All Filtered, Clear Selection, and a sticky selection bar showing the count.

Use a three-column desktop card grid. Every card shows result prominently with text, owner color, opponent name/title/rating, owner rating and rating change if present, exact clock and Lichess speed, rated/casual, date, ECO and opening name, variant, and analysis badges for My Moves and Opponent Moves. Unsupported variants show a clear “Catalog only — analysis unavailable” label.

Card actions: Open Game, Analyze My Moves, and a More menu with Analyze Opponent, Analyze Both, and Delete when permitted. The selection bar offers Analyze Selected and explains that bulk analysis always grades My Moves and is capped at 20; disable it with a reason when the selection is invalid. Include pagination or Load Older, loading skeletons, no-results with Clear Filters, empty catalog with Get Lichess Games, and per-card failure badges. Show a confirmation dialog for deletion and a blocked-deletion state listing reports that reference the game.
```

## 4. Analysis queue

```text
Create a high-fidelity 1440px desktop “Analysis Queue” view for Chess Tweek, a local Mac chess-analysis application. Use a calm professional studio style with espresso brown, warm beige, white/off-white, restrained serif headings, legible sans-serif controls, subtle borders, minimal shadows, and accessible focus. No gradients, purple, neon, glass effects, emoji, or arcade-like progress visuals.

Use the shared left navigation and a header titled “Stockfish analysis queue.” At the top show a compact engine-status strip: Stockfish version, two threads, 256 MB hash, MultiPV 3, profile name, and a healthy/offline indicator. Include “Run Health Check” and “Open Engine Settings” secondary actions.

The active-job panel is the visual focus. Show the current game matchup, result, owner color, selected perspective, opening, move progress such as “24 of 41 decisions,” overall batch progress, elapsed time, estimated remaining range, and a restrained progress bar. Explain that analysis is local and can warm the Mac. Provide Pause, Resume when paused, Cancel Current, and Open Game. Cancel Current must open a confirmation dialog explaining that completed games remain saved.

Below, show queue groups for Pending, Completed, Failed, and Canceled. Each row displays game, perspective, queued time, progress/status, and relevant action. Pending rows can be reordered only if that capability is planned; otherwise do not show drag handles. Provide Cancel Remaining, Retry Failed, Clear Completed, and Return to Games. Failed rows show a safe reason such as “Stockfish stopped unexpectedly,” never raw process output, and offer Retry and View Details.

Include states for: empty queue with Go to Games; paused queue; Stockfish missing with View Installation Instructions; application restarted while a job was running with a reconciliation notice; storage cap reached with Open Storage Settings; and batch validation failure. Use text and icons in addition to tonal status colors. Avoid celebratory completion effects.
```

## 5. Individual game review

```text
Create a high-fidelity 1440px desktop “Game Review” screen for Chess Tweek. The interface should feel like a calm, serious chess study desk: espresso-brown navigation and typography, warm-beige canvas, white/off-white panels, restrained serif headings, precise sans-serif data text, subtle borders, minimal shadows, and excellent focus/contrast. Avoid purple, gradients, neon, glassmorphism, emoji, oversized evaluation numbers, and decorative clutter.

Use the persistent left navigation. The page header shows players, ratings, result, owner color, time control/speed, date, rated/casual, ECO and opening. Include Back to Games and a More menu with Reanalyze and Delete where allowed.

Use a balanced three-part workspace: a large chessboard on the left, move list/evaluation progression beside it, and analysis details on the right. The board has previous move, next move, jump to start/end, board flip, and keyboard navigation. Highlight the played move, best move arrow, and selected error without showing multiple noisy arrows at once.

When available, display tabs “My Moves” and “Opponent Moves”; if only one side was analyzed, show the missing tab disabled with an Analyze Opponent or Analyze My Moves action. At top of the analysis panel, show summary statistics: inaccuracies, mistakes, blunders, average expected-score loss, average centipawn loss, and phase counts. Follow with opening, middlegame, and endgame breakdowns and conservative theme chips.

List every finding chronologically. Each finding shows move number, severity text, exact phase, played move, best move, expected-score loss, evaluation before/after, verified theme, and View Position. Selecting one moves the board and opens a coach card with a short evidence-based explanation and buttons Why?, What was the threat?, What should I train?, and Show Engine Line. The engine line must be readable and collapsible.

Primary controls: Analyze My Moves, Analyze Opponent, Analyze Both, Reanalyze Selected Side, Cancel Analysis, Open Queue, board navigation, board flip, and Return to Games. Include states for not analyzed, analysis queued/running/failed, no classified errors, unsupported variant, missing Stockfish, and referenced-game deletion blocked by reports. Use realistic London System content and long explanation text to test layout.
```

## 6. Overall Games Analysis builder

```text
Create a high-fidelity 1440px desktop “Create Overall Games Analysis” screen for Chess Tweek, a local-first macOS chess study application. Use a calm professional analysis-studio visual system: dark espresso-brown navigation, typography, and primary actions; a warm-beige canvas; white or warm off-white surfaces; restrained serif headings; and precise sans-serif interface text. Use subtle borders, minimal shadows, strong keyboard focus, and controls at least 44px high. Avoid purple, gradients, neon, glassmorphism, emoji, confetti, playful game styling, and generic dashboard templates.

Use the persistent left navigation with Dashboard, Games, Reports, Puzzles, and Admin; Reports is selected. The header says “Create Overall Games Analysis” and explains that a report requires exactly 10 distinct games with completed My Moves analysis. Show a prominent selection counter such as “7 of 10 games selected,” a restrained progress ring or bar, and a primary “Generate Report” button that is disabled until exactly 10 eligible games are selected. Beside the disabled button, state the exact reason.

Build a filter toolbar with result, owner color, opening, opponent type, speed, and date range, plus Clear Filters and sort by newest/oldest. Show an “Eligible only” control that is on by default. Provide a compact explanation of eligibility and a disclosure for excluded games. Exclusion reasons include not analyzed, opponent-only analysis, unsupported variant, already selected duplicate, and analysis failed. Do not exclude a game merely because it belongs to another report.

Show a two-column workspace. The wider left column is a selectable game table or dense card list with checkbox, result, owner color, opponent and rating, date, speed, ECO/opening, analysis version and completion date, and an eligibility status. Include Select Visible up to Limit and Clear Selection. The right column is a sticky “Selected 10” tray showing the exact games in selection order, each with Remove and Open Game. Include a report-name field with a sensible generated default and helper text that the report is an immutable snapshot of these exact analysis versions.

Visible controls: Generate Report, Cancel, Clear Filters, Select Visible up to Limit, Clear Selection, Remove, Open Game, and View Eligibility Details. Include empty, loading, no-filter-results, fewer-than-10, exactly-10, and over-limit-prevented states. Include generation progress with steps “Saving snapshot,” “Computing patterns,” and “Creating puzzle packages,” plus a recoverable error that preserves the selection. Make clear that only the owner’s findings influence this report and opponent analysis is never included. Successful generation transitions to the Overall Games Analysis detail view.
```

## 7. Saved reports library

```text
Create a high-fidelity 1440px desktop “Saved Reports” library for Chess Tweek, a private local chess analysis app. The design is a calm, refined analysis studio: espresso-brown navigation, type, and primary controls; warm-beige canvas; white/off-white cards; restrained serif page headings; clean sans-serif interface text; subtle borders; minimal shadows; and accessible focus and contrast. Avoid gradients, purple, neon, glass effects, emoji, noisy charts, and generic admin-template styling.

Use the persistent left navigation with Reports selected. The page header contains “Overall Games Analysis,” a short explanation that each saved report is an immutable snapshot of exactly 10 games, and a primary “Create Report” button.

Add a compact toolbar with search by report name or opening, date range, dominant opening, status of Mistake/Blunder package, status of Related Lichess package, sort by newest/oldest, and Clear Filters. Display the matching report count. Do not add sharing, cloud sync, collaboration, or export controls.

Use a two-column report-card layout suited to desktop. Every card shows report name, created date and time, 10-game scope, owner rating range, speeds represented, result summary, top opening, and compact owner-only counts for inaccuracies, mistakes, and blunders. Show two clearly separate package rows: “Mistake/Blunder Puzzles” with exercise count and progress, and “Related Lichess Puzzles” with puzzle count and ready/fetching/failed status. The two packages must never look merged.

Card actions: Open Report, Start or Continue Mistake/Blunder Puzzles, Start or Continue Related Lichess Puzzles, and a More menu with View Dependencies and Delete Report. A failed related-package row offers Retry Retrieval without implying that the report failed. Deletion opens a confirmation showing that the report and its report-owned packages and attempts will be removed, while the source games remain unless explicitly handled in Admin.

Include a loading skeleton, empty library with Create First Report, no filter results with Clear Filters, retrieval-in-progress state, related-puzzle failure state, and safe report-open error with Retry. Add pagination or Load More if needed. Use realistic report names such as “Rapid weaknesses — July 2026” and real openings rather than lorem ipsum.
```

## 8. Overall Games Analysis detail

```text
Create a high-fidelity 1440px desktop “Overall Games Analysis” detail screen for Chess Tweek. Use a serious, calm chess-study aesthetic: espresso-brown navigation and controls, warm-beige canvas, white/off-white surfaces, restrained serif headings, legible sans-serif data text, precise spacing, subtle borders, minimal shadows, and accessible focus. Avoid gradients, purple, neon, glassmorphism, emoji, trophy styling, and sensational claims about coaching or AI.

Use the persistent left navigation with Reports selected. The header shows the saved report name, creation timestamp, immutable-snapshot label, exactly 10 games, analysis profile/version, owner rating range, and Back to Reports. Include View Source Games and View Dependencies secondary actions. Make clear throughout that all findings are for the owner’s moves only.

Start with a compact summary band showing total owner inaccuracies, mistakes, blunders, normalized error rate per 100 owner decisions, average expected-score loss, results, colors, speeds, and human-versus-bot context. Follow with three analysis sections: “By game phase” for opening/middlegame/endgame, “By opening” with ECO/name and normalized rates, and “Supported tactical themes” with occurrence counts and weighted severity. Charts must include exact text values and remain understandable without color.

Below, show the 10 source games in a collapsible table with opponent, result, color, date, speed, opening, exact analysis version, and Open Game. Then show “Every error position,” a filterable chronological list containing game, move, phase, severity, played move, best move, expected-score loss, verified theme when available, and View Position. Selecting View Position opens an in-page board drawer with position, move context, principal variation, and Open Full Game Review.

Give the two report-owned training packages equal but distinct cards. “Mistake/Blunder Puzzles” shows one custom exercise per owner mistake and blunder, progress, Start/Continue, and attempt summary. “Related Lichess Puzzles” shows up to 20 puzzles, selected themes and allocation, progress, Start/Continue, and Retry Retrieval if unavailable. Also show concise evidence-based “Training priorities” derived deterministically from the report, with links to the relevant errors or package—do not present free-form chatbot text.

Visible controls: Back to Reports, View Source Games, View Dependencies, filter controls, View Position, Open Full Game Review, Start/Continue each package, Retry Retrieval, and Return to Top. Include loading, no-errors, partial-theme-classification, related-package-fetching, related-package-failed, and source-game-unavailable integrity-warning states. Existing report figures must never appear to change after a game is reanalyzed.
```

## 9. Mistake/Blunder Puzzles

```text
Create a high-fidelity 1440px desktop puzzle-player view named “Mistake/Blunder Puzzles” for Chess Tweek. This is a report-owned training package built from the owner’s actual errors, not a generic puzzle feed. Use a calm chess-study desk style with espresso brown, warm beige, white/off-white, restrained serif headings, precise sans-serif UI text, subtle borders, minimal shadows, excellent keyboard focus, and accessible status communication. Avoid gradients, purple, neon, glass effects, emoji, arcade scoring, streak fireworks, and childish gamification.

Use the persistent left navigation with Reports selected. The header shows the source report name, package title “Mistake/Blunder Puzzles,” progress such as “8 of 14,” solved/failed/remaining counts, and Return to Report. Add a compact package navigator with Previous, puzzle number selector, and Next; Next is available only after resolution or an explicit Give Up.

Make a large chessboard the main focus. Show whose move it is, board coordinates, previous/next position controls after resolution, board flip, and keyboard navigation. The exercise begins immediately before the original owner mistake or blunder and expects a five-ply line: user, opponent, user, opponent, user. Beside the board show the original game context: opponent, date, opening, original move number, phase, and severity, but do not reveal the erroneous move or solution before the attempt.

The response panel shows step progress “Your move 1 of 3,” a neutral move-history area, Submit Move if click-to-move does not auto-submit, Undo before submission, Hint, and Give Up. Hint 1 identifies the piece to move; Hint 2 identifies the destination square. Clearly show that using any hint affects local difficulty. After each move, display accepted, accepted alternative, or incorrect with concise language. Accept close alternatives without claiming there is only one move.

On solution, reveal the original played move, best line, up to three prepared acceptable lines, evaluation or expected-score change, verified theme if available, and a concise explanation linked to the original error. Show local result classification, attempts, hints used, Review in Original Game, Next Puzzle, and Retry Puzzle. On Give Up, mark Failed and reveal the line without pretending it was solved.

Include first-attempt, one-incorrect-attempt, hint-one, hint-two, accepted-alternative, solved, failed, resumed-session, package-complete, corrupted-position, and no-eligible-errors states. Package completion offers Return to Report and Review Failed Puzzles, with no confetti or global leaderboard.
```

## 10. Related Lichess Puzzles

```text
Create a high-fidelity 1440px desktop puzzle-package view titled “Related Lichess Puzzles” for Chess Tweek. This package belongs to one Overall Games Analysis report and contains at most 20 Lichess puzzles related to the owner’s report mistakes and blunders. Use a calm, professional analysis-studio style: espresso-brown navigation and primary controls, warm-beige canvas, white/off-white surfaces, restrained serif headings, clear sans-serif interface text, subtle borders, minimal shadows, and accessible focus. Avoid gradients, purple, neon, glassmorphism, emoji, casino-like streaks, and generic gamification.

Use the persistent left navigation with Reports selected. The header shows the source report, package title, current progress, exact stored puzzle count such as “16 related puzzles,” and Return to Report. Include a transparent “Why these puzzles?” summary listing up to four selected themes and proportional allocations. Explain compactly that report themes are weighted by three points per blunder and two per mistake, unique related puzzles are used, and unrelated filler is never added.

The main player uses a large chessboard with coordinates, whose-turn label, board flip, and keyboard navigation. A right-side panel shows current puzzle number, Lichess source attribution and puzzle ID link, supported theme or opening mapping, local attempts, and step progress. Controls: Hint, Give Up, Retry Puzzle after resolution, Previous Resolved, and Next Puzzle. Hint 1 names the piece; Hint 2 names the destination. Never show the solution in browser-visible data before it is needed for server-side validation.

Show accepted, incorrect, solved, and failed feedback without celebratory animation. After resolution reveal the line, themes, local difficulty classification, attempts, hints used, and Next Puzzle. Make it explicit in a small privacy note that results are stored only on this Mac and are never submitted to Lichess.

Include retrieval states before play: fetching with a progress summary, fewer-than-20 result with an honest explanation, Lichess unavailable with Retry Retrieval and Return to Report, no validated mapping with no unrelated fallback, and deduplication-shortfall state. Include resumed session, package complete, puzzle-load failure, and locally missing puzzle states. Retrieval failure must not make the parent report look failed or unavailable.
```

## 11. Normal puzzles hub

```text
Create a high-fidelity 1440px desktop “Puzzles” hub for Chess Tweek, a private local chess training app. Use the shared calm analysis-studio design: espresso-brown navigation, typography, and actions; warm-beige page canvas; white or warm off-white cards; restrained serif headings; highly legible sans-serif text; subtle borders; minimal shadows; and accessible keyboard focus. Avoid gradients, purple, neon, glass effects, emoji, trophy walls, fake social features, and childish gamification.

Use the persistent left navigation with Puzzles selected. The header says “Normal puzzles” and explains that Daily and Mixed Lichess puzzles are independent from report-owned training packages and all attempts stay local.

Feature two large, clearly separate entry cards. “Daily Puzzle” shows today’s availability, Lichess attribution, last result if already attempted, and Start Daily Puzzle or Review Today’s Puzzle. “Mixed Puzzles” shows Start Mixed Session, an optional session-length selector with modest choices, and Resume Session when applicable. Do not include rating submission, puzzle battles, puzzle streak competitions, or Lichess write actions.

Below, show local training summaries: attempts in the last 7 and 30 days, solved and failed counts, and Easy/Medium/Hard/Failed distribution using exact labels and values. Add a recent-attempts table with date, source Daily or Mixed, supported themes, local difficulty, result, attempts, hints, and Review. Include filters for source, result, local difficulty, theme, and date, plus Clear Filters.

Add a restrained informational panel explaining local difficulty: Easy is first attempt with no hints; Medium is one incorrect attempt with no hints; Hard is two or more incorrect attempts or any hint; Failed is Give Up. Include a secondary link “Open report puzzle packages” that goes to Reports but never mixes those histories into the normal-puzzle statistics.

Visible actions: Start Daily Puzzle, Review Today’s Puzzle, Start Mixed Session, Resume Session, Review, Clear Filters, Open Report Puzzle Packages, and Retry Lichess. Include loading, first-use empty history, daily unavailable, Lichess offline, mixed-session retrieval failure, and no filter results states. Clearly indicate connection issues without losing already stored history.
```

## 12. Normal puzzle player

```text
Create a high-fidelity 1440px desktop “Normal Puzzle” player for Chess Tweek. This view serves either a Lichess Daily puzzle or an independent Mixed session and must remain visually distinct from report-owned puzzle packages. Use espresso brown, warm beige, white/off-white, restrained serif headings, clear sans-serif UI text, subtle borders, minimal shadows, strong accessible focus, and a calm study-room tone. Avoid gradients, purple, neon, glassmorphism, emoji, confetti, countdown pressure, leaderboards, and arcade styling.

Use the persistent left navigation with Puzzles selected. The header identifies the source as “Daily Puzzle” or “Mixed Puzzles,” shows session progress for Mixed, and includes Return to Puzzles. Show a small Lichess source attribution without exposing the solution.

The large chessboard is central and includes board coordinates, whose-turn text, board flip, and keyboard move support. A side panel shows move-step progress, a neutral attempt count, hints-used status, and move history. Controls: Hint, Give Up, Undo before move submission when appropriate, Retry after resolution, Previous Resolved for a Mixed session, and Next Puzzle. Hint 1 identifies the piece and Hint 2 identifies the destination square.

Give immediate but restrained accepted/incorrect feedback. Do not reveal future solution moves to browser JavaScript. When solved or given up, reveal the full line, supported Lichess themes, local difficulty classification and its reason, attempts, and hints used. Clearly state “Saved locally — not submitted to Lichess.” For Daily, completion offers Return to Puzzles and Review Position. For Mixed, completion offers Next Puzzle or Finish Session.

Include states for loading, first move, incorrect attempt, Hint 1, Hint 2, solved Easy, solved Medium, solved Hard, Failed by Give Up, resumed Mixed session, final puzzle in session, puzzle unavailable, invalid puzzle payload, and lost Lichess connection. Previously completed local attempts must remain viewable while offline.
```

## 13. Admin control center

```text
Create a high-fidelity 1440px desktop “Admin” control center for Chess Tweek, a local-first macOS application operated by one owner. Use a calm, operational analysis-studio design: espresso-brown navigation and controls, warm-beige canvas, white/off-white surfaces, restrained serif headings, crisp sans-serif technical text, subtle borders, minimal shadows, accessible focus, and clear textual statuses. Avoid gradients, purple, neon, glass effects, emoji, enterprise-team features, user-role management, and generic cloud-admin styling.

Use the persistent left navigation with Admin selected. The header says “Admin control center,” shows “Local Mac only,” current application health, and last health-check time. Include Run All Health Checks as the primary action.

Create a structured overview with these visible sections. “Lichess connection” shows connected username, requested read-only scopes, token health without displaying a token, Reconnect, and Log Out. “Stockfish” shows detected binary/version, path shortened safely, profile, threads, hash, MultiPV, last benchmark, Run Health Check, Benchmark Again, and View Engine Settings. “Game retrieval” shows last sync, stored game count, Get Lichess Games, and recent retrieval errors. “Analysis queue” shows queued/running/paused/failed counts with Open Queue and Retry Failed.

Add “Data overview” cards with games, analysis versions, saved reports, custom puzzle packages, related Lichess packages, normal puzzle attempts, and database size. Each links to a focused management view. Add “Recent issues” with safe summaries for revoked connection, crashed Stockfish, failed job, storage warning, and puzzle retrieval failure; each has a direct recovery action and no raw secrets or process dumps.

Add a lower “Maintenance” section linking to Game–Report Dependencies and Storage & Database Maintenance. Show backup freshness, database integrity status, cap usage, and free disk. Dangerous operations must not be available as unlabeled icon buttons.

Visible actions: Run All Health Checks, Reconnect, Log Out, Run Stockfish Health Check, Benchmark Again, View Engine Settings, Get Lichess Games, Open Queue, Retry Failed, Manage Games, Manage Analysis Versions, Manage Reports, Manage Puzzle Histories, Open Dependencies, and Open Storage Maintenance. Include healthy, checking, degraded, offline, no-data, partial-check-failure, and storage-blocked states. Use explicit status text alongside icons and color.
```

## 14. Admin game–report dependency manager

```text
Create a high-fidelity 1440px desktop “Game–Report Dependencies” admin screen for Chess Tweek. It must make restrictive deletion relationships unmistakable and safe. Use a calm professional operations style with espresso-brown navigation and primary controls, warm-beige canvas, white/off-white surfaces, restrained serif headings, precise sans-serif data text, subtle borders, minimal shadows, high contrast, and strong keyboard focus. Avoid gradients, purple, neon, glassmorphism, emoji, vague destructive icons, and generic database-admin styling.

Use the persistent left navigation with Admin selected and breadcrumbs Admin / Game–Report Dependencies. The header explains that reports are immutable snapshots and a game referenced by any report cannot be deleted directly. Include Return to Admin.

Build a true side-by-side dependency workspace. The left pane is “Games” with search, result, date, opening, and dependency-status filters. Each row shows checkbox, players/result, date, opening, analysis versions, and number of blocking reports. The right pane is “Reports” with search/date filters. Each row shows checkbox, report name, created date, exactly 10 games, two package counts/statuses, and number of selected games it references. Selecting either side visually highlights the exact cross-pane relationships with lines or matched emphasis that remains understandable without color.

Add a sticky selection summary listing exact selected games, reports, report-owned packages, and attempt records. Present three explicit guarded actions: “Delete Report Only,” “Delete Report and Both Packages,” and “Delete Report, Packages, and Selected Games.” If report-owned packages always cascade with report deletion in the data model, clarify in the interface that Delete Report Only means the report and its owned package data are removed while source games remain; do not falsely promise orphaned packages. Disable an action whenever its full dependency closure is not selected and state why.

Every destructive action opens a detailed confirmation drawer showing exact names and counts, retained items, cascaded items, blocking references, and a required typed confirmation phrase. Include Cancel and Confirm Deletion. The most destructive option uses strong but restrained danger styling. Show transaction progress without allowing double submission.

Include blocked direct deletion listing every report, a clean no-dependencies state, multi-report overlap, a report referencing only some selected games, partial-selection validation, transaction success, and transaction failure with “No changes were made” rollback confirmation. Include Refresh Dependencies and Open Game/Open Report actions. Never offer force delete, disable foreign keys, or bypass references.
```

## 15. Admin storage, backup, restore, and compaction

```text
Create a high-fidelity 1440px desktop “Storage & Database Maintenance” screen for Chess Tweek, a local-first macOS chess application using local SQLite storage. Use a calm, trustworthy maintenance-console style consistent with the app: espresso-brown navigation and controls, warm-beige canvas, white/off-white panels, restrained serif headings, precise sans-serif technical text, subtle borders, minimal shadows, accessible focus, and status communicated by words and icons as well as color. Avoid gradients, purple, neon, glass effects, emoji, scary terminal aesthetics, and cloud-backup claims.

Use the persistent left navigation with Admin selected and breadcrumbs Admin / Storage & Database Maintenance. The header says all data and backups remain on this Mac and includes Return to Admin.

At top, show a storage overview with current database plus WAL/journal usage, configurable cap defaulting to 5 GB, percent used, free disk space, and the protected minimum of 10 GB free. Use an exact meter with labeled thresholds at 80%, 90%, and 100%. Show current state text: Healthy, Warning, Critical, or Persistence Paused. Include Edit Storage Cap with validation and Save/Cancel. Explain that nothing is deleted automatically.

Create three clear maintenance panels. “Backups” shows backup location, last successful backup, size, integrity-check result, Create Backup, and a timestamped backup table with filename, created time, size, status, Restore, and Reveal in Finder. “Restore” explains that a new safety backup is always created first, shows the selected backup and compatibility/integrity checks, and uses a guarded Begin Restore action. “Compaction” shows reclaimable space estimate, last compacted date, expected temporary free-space requirement, Compact Database, and a note that compaction reclaims deleted space but does not delete live records.

Restore requires a confirmation drawer listing selected backup, automatic pre-restore backup, exact database target, checks, service interruption, typed confirmation, Cancel, and Confirm Restore. Compaction has its own confirmation with space requirement and Cancel/Compact. Show progress steps for backup, pre-restore backup, validation, replacement, post-restore integrity check, and compaction. Disable conflicting actions while one maintenance operation runs.

Include states for 80% warning, 90% critical warning, 100% persistence stop, less-than-10-GB disk stop, backup success/failure, corrupt backup rejected, incompatible backup rejected, pre-restore-backup failure that stops restore, restore failure with original database still usable, post-restore success, compaction success with reclaimed amount, compaction failure, and no backups yet. Provide Retry, View Safe Details, and Run Integrity Check where relevant, but never show secrets or destructive “delete all data” shortcuts.
```
