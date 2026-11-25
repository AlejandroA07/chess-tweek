# Chess Tweek design system

- **Status:** Approved canonical direction
- **Approved:** 2026-07-22
- **Primary reference:** [`reference-dashboard-shell.png`](reference-dashboard-shell.png), the Hero / Grandmaster Studio dashboard
- **Initial target:** Desktop web application at 1280–1440 px

This document is the visual source of truth for implementation. The supplied screenshots and exports are reference material; when they conflict with this document or the product specification, this document and the product specification win.

## Design intent

Chess Tweek should feel like a calm chess analysis studio: warm, focused, precise, and crafted without becoming ornate. Its visual language combines the geometry of a chessboard with the restrained character of mahogany and maple wood.

The interface must not look like a generic SaaS dashboard or an arcade chess site. Avoid purple, neon, gradients, glassmorphism, oversized marketing text, excessive shadows, emoji headings, reward effects, and decorative clutter.

## Canonical decisions

- Use the Hero / Grandmaster Studio visual language across every route.
- Apply the low-contrast light chessboard pattern behind every main workspace.
- Keep navigation, cards, forms, tables, dialogs, and chessboards on solid surfaces so the pattern never reduces readability.
- Use the hero's typographic Chess Tweek wordmark with the subtitle “Analysis Studio.” Do not use “Grandmaster Studio” as the product name.
- Use the approved application navigation: Dashboard, Training, Games, Reports, Puzzles, and Admin.
- There is one light theme in v1. Do not add an incomplete dark theme.

## Color tokens

Implementation should expose these as semantic CSS custom properties rather than scattering raw values through CSS Modules.

| Token | Value | Role |
|---|---:|---|
| `--color-canvas` | `#FFF8F5` | Main application canvas and pattern base. |
| `--color-pattern-square` | `#F5D3BD` | Checkerboard square before the global 10% opacity treatment. |
| `--color-surface` | `#FFFFFF` | Primary cards, dialogs, tables, and forms. |
| `--color-surface-subtle` | `#FFF1E9` | Inputs, selected rows, quiet grouped areas. |
| `--color-surface-warm` | `#FFEADD` | Sidebar and stronger grouped regions. |
| `--color-text` | `#29180A` | Primary text and high-contrast icons. |
| `--color-text-muted` | `#54433A` | Secondary copy and metadata. |
| `--color-primary` | `#6C2F00` | Primary actions, active navigation, and focus emphasis. |
| `--color-primary-hover` | `#8B4513` | Primary hover/pressed treatment. |
| `--color-border` | `#DAC2B6` | Default borders and dividers. |
| `--color-border-strong` | `#877369` | Strong input or structural border. |
| `--color-success` | `#3E4C31` | Verified success only. |
| `--color-warning` | `#765B18` | Warnings and inaccuracies. |
| `--color-error` | `#BA1A1A` | Errors, blunders, and destructive actions. |

Measured WCAG contrast ratios for core pairings:

- Primary text on canvas: `16.27:1`.
- Muted text on canvas: `8.92:1`.
- White on primary: `10.27:1`.
- White on primary-hover: `7.10:1`.
- White on error: `6.46:1`.
- White on success: `9.20:1`.
- Warning on canvas: `6.10:1`.

Do not use the pale pattern, border, or container colors as text colors.

Chessboard and evaluation tokens (added 2026-11-14 for game review):

| Token | Value | Role |
|---|---:|---|
| `--color-board-light` | `#F3DCC6` | Light board squares (maple). |
| `--color-board-dark` | `#B98563` | Dark board squares (mahogany). |
| `--color-board-last-move-from` | `rgb(108 47 0 / 18%)` | Quiet overlay for the square a shown move left. |
| `--color-board-last-move-to` | `rgb(108 47 0 / 32%)` | Stronger overlay for the destination of the shown move. |
| `--color-board-arrow` | `rgb(62 76 49 / 85%)` | Engine best-move and user-annotation arrows, plus the engine-line banner rule. |
| `--color-eval-white` | `#F3DCC6` | White's share of the evaluation bar. |
| `--color-eval-black` | `#54433A` | Black's share of the evaluation bar. |

Pieces use filled chess glyphs from the system symbol font: white pieces are surface-white with a primary-text outline, black pieces are primary text. Square coordinates use the technical typeface on every board edge square.

## Shared chessboard background

The checkerboard background is mandatory on every page workspace, including first run, Dashboard, Training, Games, Reports, Puzzles, and Admin. It is decorative and must be hidden from assistive technology.

Canonical construction:

```css
background-color: #fff8f5;
background-image:
  linear-gradient(45deg, #f5d3bd 25%, transparent 25%),
  linear-gradient(-45deg, #f5d3bd 25%, transparent 25%),
  linear-gradient(45deg, transparent 75%, #f5d3bd 75%),
  linear-gradient(-45deg, transparent 75%, #f5d3bd 75%);
background-position: 0 0, 0 32px, 32px -32px, -32px 0;
background-size: 64px 64px;
opacity: 0.1;
```

Implement the pattern on a dedicated pseudo-element below page content. Never apply `opacity` to a container that owns readable content. Do not combine it with photographs, animated patterns, or heavy wood grain. Solid content regions must cover the pattern completely.

## Typography

| Role | Family | Size / line height | Weight | Usage |
|---|---|---:|---:|---|
| Display | Playfair Display | `48 / 56px` | 700 | Rare Dashboard or first-run hero only. |
| Page title | Playfair Display | `32 / 40px` | 600 | One page title per view. |
| Section title | Playfair Display | `24 / 32px` | 600 | Major content sections and panel titles. |
| Body large | Manrope | `18 / 28px` | 400 | Introductory copy. |
| Body | Manrope | `16 / 24px` | 400 | Default readable content. |
| UI small | Manrope | `14 / 20px` | 400–600 | Controls, metadata, and compact tables. |
| Technical | JetBrains Mono | `13 / 20px` | 500 | Moves, evaluations, FEN fragments, clocks, and numeric engine data. |
| Label | JetBrains Mono | `12 / 16px` | 500 | Short labels with `0.05em` letter spacing. |

Rules:

- Use Playfair Display sparingly; never use it for form labels, buttons, tables, or move notation.
- Avoid all caps except very short technical labels.
- Use tabular numbers for clocks, ratings, counts, and aligned evaluations.
- Design for realistic long opponent names, opening names, error explanations, and translated text.
- Font delivery must work without runtime CDN requests. Stage 1 must choose and verify a licensed build-time or self-hosted mechanism before implementation.

## Spacing and layout

- Base unit: `8px`; `4px` is allowed only for fine internal alignment.
- Standard scale: `4, 8, 16, 24, 32, 48, 64px`.
- Desktop sidebar: `208px` wide.
- Desktop top bar: approximately `72–80px` high.
- Main workspace starts close to the top bar; horizontal padding stays fluid and reduces before content becomes cramped.
- Standard grid gutter: `24px`.
- Main content uses a fluid grid and should not exceed the readable width of its task.
- When a board is displayed in a lesson, puzzle, or game review, hide the sidebar and use the available width for a square board with adjacent content; retain a clear return link.
- Dense tables use alignment and whitespace before adding more borders.

Responsive behavior:

- At `1280px` and wider, use the full sidebar and multi-column workspace, except when a board is displayed.
- Between `768px` and `1279px`, reduce outer padding and collapse secondary panels before shrinking primary content excessively.
- Below `768px`, use a navigation drawer and stack content in task order.
- At `320px`, controls remain usable without page-level horizontal scrolling; chessboards scale to the viewport.

The supplied design review is desktop-first. Responsive layouts must preserve hierarchy rather than invent a separate mobile design language.

## Shape and elevation

- Controls and small containers: `4px` radius.
- Cards and large panels: `8px` radius.
- Chessboards: square, with no decorative rounding that changes board geometry.
- Default surfaces use a `1px` warm border and no shadow.
- Dialogs and popovers may use `0 8px 32px rgb(44 30 26 / 8%)`.
- Primary buttons may use one restrained bevel; do not repeat bevels on ordinary cards.
- Inputs can use a subtle inset treatment, but their boundary and focus state must remain obvious.

## Application shell

The persistent desktop shell contains:

1. Chess Tweek wordmark and “Analysis Studio” subtitle.
2. Navigation in this order: Dashboard, Training, Games, Reports, Puzzles, Admin.
3. A compact connected-Lichess account region at the bottom.
4. A top bar used only for page context and relevant actions.

Do not add global search, Go Pro, notifications without a real feature, chat bubbles, Play Bot, Coach, Opening Lab, Endgames, Settings, or Help merely because they appear in generated references.

The selected navigation item uses a primary-brown left rule, solid or subtle warm background, icon, and bold text. Selection must not rely on color alone.

## Guided-course view

- The Training destination opens a calm course dashboard, not a competitive puzzle lobby. Lead with the course title, one-sentence purpose, progress, and a clear recommended next lesson.
- Show the five-question move routine as a stable recall aid and the course roadmap as solid surface cards. Available lessons are direct links; future modules use the plain text label **Planned**, not locks, countdowns, rewards, streaks, or invented scores.
- A lesson places the chessboard on the left and its title, explanation, recall checklist, prompt, status, hints, feedback, reveal, and completion controls to its right on wide screens; stack them on narrow screens.
- Correctness, progress, and revealed-answer states use text as well as color. A returned solved exercise can request **Review answer**, which replays the full line and states that it does not change the Training Lists, and **Solve again**.
- Each exercise is its Lichess puzzle's full line (owner decision, 2026-11-22). The board stays read-only until **Start solving**, because Solve Time starts then, and the card says beforehand that solving also counts as an attempt on the puzzle in the Training Lists. While solving, the card shows the moves to find, the fast-solve limit, and Wrong Moves. It also states next to the two written hints and Show solution that using any of them places the puzzle in Hard to Solve. Completion shows the course explanation and the puzzle result in words, for example "Puzzle training: moved from Retiring to Hard to Solve."
- Disconnected mode keeps all authored explanation and positions readable, while a calm inline notice explains that submitting moves and saving progress require Lichess connection.
- The source-and-independence note remains visible on the course dashboard without dominating the instructional hierarchy.

## Puzzle training view

- The Puzzles destination opens a calm training dashboard. It is not a competitive lobby: no invented ratings, scores, streaks, leaderboards, or daily-challenge styling.
- When any Puzzles screen is open, the Puzzles navigation item expands into a sub-menu: Dashboard, Themes, Openings, Own Puzzles, then "Repetition: normal puzzles" and "Repetition: own puzzles". An entry that is not built yet is plain text with the label **Planned**, not a link. The parent item stays marked as the current section, and the open screen is marked as the current page.
- The dashboard has only module cards, each a solid card with a thin top rule: Random, Themes, Openings, Repetition, and Own Puzzles. A card that is not built yet shows **Planned** instead of an action.
- The Themes page mirrors the Lichess Puzzle Themes page: the same sections in the same order (Phases, By openings, Motifs, Advanced, Mates, Mate themes, Special moves, Goals, Lengths, Origin), without "Healthy mix". Every theme is listed, not collapsed, in a two-column grid. Each theme shows a muted line icon (or chess glyph), its name, its puzzle count in the technical typeface, and a one-line description in Chess Tweek's own words. The whole entry is one link that opens a puzzle. "By openings" lists the most popular opening families and a "More »" link to the Openings page.
- The Openings page lists every opening family with its puzzle count, and all of its variations below it in a two-column grid with counts, named without the repeated family prefix. A labeled Order control offers Most popular (the default) and Alphabetical, and a labeled filter narrows families and variations.
- The player puts the project chessboard first, with the facts beside or below it: side to move, Lichess rating, owner moves to find, current Training List, fast-solve limit, and Wrong Moves. The setup move is highlighted as the last move.
- Opening a theme, an opening, or Random goes straight to a puzzle; there is no intermediate start screen. After a puzzle is complete, the board stays open for local exploration and says that those moves are not saved.
- A Wrong Move leaves the position unchanged and is reported in text with a warning rule. A correct move shows the opponent's reply on the board. A hint marks one square with a green inset ring and says so in that square's accessible name.
- The normal-Puzzle Repetition dashboard uses the same module cards: one per active Training List (Hard to Solve, Wrong Moves, Worth Repeating, Retiring) in progression order, then Mastered. Each card shows the list's puzzle count in the technical typeface and, in words, what it takes to leave the list. Its action starts a Practice Session; an empty list states that it has no puzzles instead of offering the action.
- A Practice Session opens straight to its first puzzle in the same player, with a "Session" fact such as "3 of 12" and a back link to Repetition. When every puzzle has been presented, the next round starts at once in a new order, without leaving the board: the fact reads "Round 2 · 1 of 12" and one muted line says that a new round has started (owner request, 2026-11-21). Only an empty list, or a session replaced by another one, stops the player, with one action to start again.
- The Mastered view groups Mastered puzzles under "By theme" (most puzzles first, without "Healthy mix") and "By opening" (families with their variations, laid out like the Openings page), each with its Mastered count. Each entry opens practice for that category. The view and the practice player both state that practice never changes Mastered status, and Hint and Show solution say so in place of the Hard to Solve warning.
- Hint and Show solution state next to the controls that either one places the puzzle in Hard to Solve and restarts its progress there. A used Hint stays disabled with a label that says it is shown on the board. Completion states the list result in words (for example, "Moved from Retiring to Hard to Solve."), Solve Time against the limit, Wrong Moves, hint use, the source game link, and one next action.

### Own Puzzles view

- Own Puzzles use the same cards, player, list modules, and wording rules as normal Puzzles, and never mix with them. Each screen names its kind in the eyebrow ("Own Puzzles", "Repetition · Own puzzles").
- The Puzzle dashboard's Own Puzzles card offers Open Own Puzzles and a New Own Puzzles shortcut.
- The Own Puzzles dashboard leads with three module cards: New Own Puzzles (its count, split into Lichess and chess.com in words), Own Repetition, and Discarded (its count). A card with nothing to open says so instead of offering the action. Below them, the Training List counts in progression order, each non-empty list with a View link to its puzzles. Then one summary panel of every kept Own Puzzle by opening family, game phase (in game order), and tactic, with counts in the technical typeface. These summaries are counts only; practice by group exists only for Mastered. With no Own Puzzles at all, one empty state explains that My Moves analysis creates them and links to Games.
- New Own Puzzles mixes both sites by default (owner decision, 2026-11-23). A labeled "Games from" control (All sites, Lichess, chess.com, each with its count) filters the list, and a labeled "Solve in" control (Newest first, Random order) sets the order for solving. Both are kept in the route. The primary action "Solve New puzzles" opens the player with those choices. The list itself is always newest game first, paged 20 at a time with Previous and Next and a "21–25 of 25" range.
- Each Own Puzzle row shows the severity (Inaccuracy, Mistake, or Blunder) as text with its semantic color, and the game phase, the full opening name (wrapping, never truncated), the proven tactic labels in Game Review wording, and the site, date, and move ("Move 12 (Black)"). It also offers an "Open source game" link. A New row also offers Solve and Discard.
- Discard always confirms inline in the same row or player panel. The confirmation names the puzzle, says that it leaves New and never enters a Training List and can be restored, and offers "Keep it" (or "Keep solving") with initial focus and "Discard puzzle" as the destructive action. After a discard from a list, a status line names the puzzle and focus moves to the page heading. After a discard in the player, the next New puzzle opens with the same status line and focus on its heading. A refused discard says that nothing has changed.
- Discarded lists discarded puzzles with Restore, and states that Restore returns a puzzle to New with no progress. Restore needs no confirmation.
- The Own Puzzle player is the normal player with these differences. The eyebrow above the board shows severity, phase, and opening family instead of a rating. While a New puzzle is open, a text-style Discard action follows Hint and Show solution. After completion, the source link opens the Game Review at the puzzle's move. No source link appears before completion, because the review shows the best move.
- "Open source game" opens the Game Review with My Moves selected and the finding at that move in view, exactly as if it had been chosen from the findings list.
- Own Repetition and Mastered Own Puzzles mirror the normal-Puzzle Repetition dashboard and Mastered view. Mastered Own Puzzles are grouped by opening, game phase, and tactic instead of by theme and Lichess opening.

## Components

### Buttons

- Minimum interactive height: `44px`.
- Primary: primary-brown background with white text.
- Secondary: solid surface with primary border and primary text.
- Tertiary: text action with a visible hover/focus surface.
- Destructive: error color, used only after the exact destructive scope is known.
- Disabled controls remain legible and state why the action is unavailable near the control.
- Loading actions retain their width, disable repeat submission, and show text plus progress indication.

### Forms

- Every control has a persistent visible label; placeholders are examples, not labels.
- Helper, validation, and error text appear in a consistent location.
- Focus uses a `2px` primary outline with a `2px` canvas/surface offset.
- Required, optional, and valid ranges are stated in text.
- Side panels are preferred for multi-field task setup; confirmation dialogs are reserved for short decisions.

### Cards and panels

- Use solid surfaces over the patterned canvas.
- Prefer one border and spacing hierarchy over shadow stacks.
- A thin mahogany header rule may identify analysis or operational panels.
- Entire cards are clickable only when there is one obvious action; otherwise use labeled actions.

### Tables and lists

- Keep headers visible for long lists where practical.
- Use subtle alternating warm rows only when it improves scanability.
- Selected, hover, focus, disabled, and error states must be distinct without relying on color alone.
- Long names truncate visually only when the full value is available by focus or an accessible disclosure.

### Status and severity

- Every status uses text and, when helpful, an icon in addition to color.
- Success, warning, and error colors carry semantic meaning only.
- Chess severities use the product terms Inaccuracy, Mistake, and Blunder consistently.
- Avoid gamified rewards, streak celebrations, and competitive leaderboards.

### Chessboards and notation

- The board is the primary visual element in review and puzzle views.
- Board orientation always has a visible indicator and a labeled Flip Board control.
- Highlight both squares of the selected or last move, with the destination stronger than the origin. Show at most one primary engine line by default. User-created annotation arrows may stack until the next normal left interaction or move clears them.
- Move notation, clocks, and evaluations use the technical typeface and tabular numbers.
- Board controls are keyboard operable and do not rely on drag-only interaction.

### Dialogs and destructive actions

- Name every target and cascade before confirmation.
- Use explicit Cancel and action-specific confirmation labels.
- The initial focus goes to the safest useful control.
- Transaction failures state whether no changes were made.

## Interaction and motion

- Use motion only to explain state change.
- Standard transitions are `120–180ms`; avoid large spring or parallax effects.
- Honor `prefers-reduced-motion` by removing nonessential transitions and animation.
- Preserve focus after async updates and return focus to the trigger after closing overlays.
- Do not use color-only, hover-only, or pointer-only interactions.

## Required states

Every implemented view must deliberately cover the states relevant to it:

- Initial loading and subsequent refresh.
- Empty first use.
- No filter results.
- Disabled actions with reasons.
- Selected and multi-selected rows/cards.
- Recoverable upstream or engine failure.
- Offline or revoked Lichess connection.
- Long-content overflow.
- Destructive confirmation, progress, success, and rollback-safe failure.
- Keyboard focus and reduced-motion behavior.

Skeletons must match the final layout. Errors must give a useful next action without exposing secrets, tokens, stack traces, or raw engine output.

## Accessibility requirements

- WCAG AA contrast: `4.5:1` for body text and `3:1` for large text and meaningful graphical controls.
- Minimum target size: `44 × 44px`.
- Visible focus for every interactive element.
- Semantic headings in order, landmarks, form labels, table headers, and accessible names for icon controls.
- Text or icons accompany semantic color.
- Images require useful alt text when informative and empty alt text when decorative.
- The background pattern is decorative, noninteractive, and absent from the accessibility tree.

## Reference-screen interpretation

| Supplied material | Keep | Reject or replace |
|---|---|---|
| Hero dashboard | Palette, checkerboard canvas, typography, shell proportions, card rhythm. | New Game, Play Bot, Go Pro, generic Coach, invented mastery scores, global search, chat bubble. |
| Centered login | Warm surfaces, focused setup composition, wordmark treatment. | Email/password, forgot password, request access. Replace with Lichess connection and local readiness. |
| Puzzle studio | Board/panel hierarchy, warm surface layering, section typography. | Invented global puzzle ratings, competitive scores, daily challenge, image-card category tiles. By Category follows the Puzzle training view rules above. |
| Bot selection and match | Board material, notation hierarchy, control sizing. | All bot play, live analysis, clocks, resign, hints during a game, and live insights. |
| Physical chess-set photos | Palette and material inspiration only. | Do not ship the photographs as runtime assets. |

Generated HTML, Tailwind classes, Material Symbols, external image URLs, and remote font links are not implementation dependencies. Stage 1 uses CSS Modules and design tokens and proposes any additional dependency before adding it.

## Content rules

- Use “Chess Tweek,” “Analysis Studio,” “Get Lichess Games,” and “Overall Games Analysis” consistently.
- Describe the product as a local-first macOS application.
- All platform-specific copy and installation guidance targets macOS.
- Do not invent a Chess Tweek rating or claim model-generated coaching.
- Never imply live-game assistance, in-app play, cloud synchronization, subscription tiers, or puzzle-result submission to Lichess.
- Use realistic chess data and include at least one intentionally long value in visual tests.

## Stage 1 implementation contract

Stage 1 is complete only when:

- CSS tokens encode this document as the only design-token source.
- The original empty Dashboard, Games, Reports, Puzzles, and Admin routes and the later first-class Training route use the checkerboard workspace consistently.
- First-run and application-shell behavior remain visually related.
- No generated out-of-scope feature appears in navigation or routes.
- Core color pairings meet the recorded contrast values.
- Keyboard focus, target sizes, long content, empty/loading/error states, and reduced motion are exercised.
- Desktop screenshots at `1280px` and `1440px` are reviewed against the canonical references.
- Responsive smoke checks at `768px` and `320px` show no uncontrolled page-level horizontal scrolling.

When a future view needs a visual rule not covered here, extend this document rather than creating a second design system.
