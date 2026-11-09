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
- Use the approved application navigation: Dashboard, Games, Reports, Puzzles, and Admin.
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

## Shared chessboard background

The checkerboard background is mandatory on every page workspace, including first run, Dashboard, Games, Reports, Puzzles, and Admin. It is decorative and must be hidden from assistive technology.

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
- Desktop sidebar: `256px` wide.
- Desktop top bar: approximately `72–80px` high.
- Main desktop padding: `40–64px`, reduced before content becomes cramped.
- Standard grid gutter: `24px`.
- Main content uses a fluid grid and should not exceed the readable width of its task.
- Analysis and puzzle views prioritize a square board with adjacent move and evidence panels.
- Dense tables use alignment and whitespace before adding more borders.

Responsive behavior:

- At `1280px` and wider, use the full sidebar and multi-column workspace.
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
2. Navigation in this order: Dashboard, Games, Reports, Puzzles, Admin.
3. A compact connected-Lichess account region at the bottom.
4. A top bar used only for page context and relevant actions.

Do not add global search, Go Pro, notifications without a real feature, chat bubbles, Play Bot, Coach, Opening Lab, Endgames, Settings, or Help merely because they appear in generated references.

The selected navigation item uses a primary-brown left rule, solid or subtle warm background, icon, and bold text. Selection must not rely on color alone.

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
- Highlight the selected or last move and at most one primary engine line by default.
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
| Puzzle studio | Board/panel hierarchy, warm surface layering, section typography. | Invented global puzzle ratings, competitive scores, generic category library not in scope. |
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
- The shared shell and empty Dashboard, Games, Reports, Puzzles, and Admin routes use the checkerboard workspace consistently.
- First-run and application-shell behavior remain visually related.
- No generated out-of-scope feature appears in navigation or routes.
- Core color pairings meet the recorded contrast values.
- Keyboard focus, target sizes, long content, empty/loading/error states, and reduced motion are exercised.
- Desktop screenshots at `1280px` and `1440px` are reviewed against the canonical references.
- Responsive smoke checks at `768px` and `320px` show no uncontrolled page-level horizontal scrolling.

When a future view needs a visual rule not covered here, extend this document rather than creating a second design system.
