# App material

This folder holds the approved visual source material for Chess Tweek.

[`DESIGN.md`](DESIGN.md) is the canonical implementation source. Screenshots, prompts, and exports are supporting references only.

## Workflow

1. Copy one standalone prompt from [`view-prompts.md`](view-prompts.md) into Google Stitch.
2. Iterate in Stitch until the screen expresses the intended hierarchy, controls, and states.
3. Save the selected output in this folder with a clear screen name, for example `games-library.png`.
4. Add notes beside an image when a behavior or visual choice is not obvious from the screenshot.
5. Return the selected designs for consistency review before Stage 1 begins.

After the designs are reviewed, the accepted shared rules will be recorded in `DESIGN.md`. Application styling must follow that file and the selected screen references; it must not invent a parallel design system.

Only desktop designs are required initially. Mobile-specific designs are deferred.

## Approved direction

On 2026-07-22, the owner selected the hero/Grandmaster Studio visual language as canonical. Its low-contrast light chessboard pattern applies behind the main workspace on every view. Solid content surfaces preserve readability over the pattern. Generated Play Bot, live assistance, subscription, email/password, and free-text coach concepts remain out of scope and are visual references only.

## Versioned references

| File | Role |
|---|---|
| [`reference-dashboard-shell.png`](reference-dashboard-shell.png) | Primary shell, hierarchy, palette, typography, and checkerboard-workspace reference. |
| [`reference-puzzles-shell.png`](reference-puzzles-shell.png) | Supporting content-density, card, and navigation reference. |
| [`reference-sign-in-visual-only.png`](reference-sign-in-visual-only.png) | Visual reference only; email/password authentication and private-access concepts are rejected. |
| [`reference-play-bot-rejected.png`](reference-play-bot-rejected.png) | Visual reference only; bot play is out of scope. |
| [`reference-live-assistance-rejected.png`](reference-live-assistance-rejected.png) | Visual reference only; live play and live assistance are out of scope. |

Raw phone photos, generated HTML/ZIP exports, and the full browser capture remain local and Git-excluded. `DESIGN.md` and the product specification always take precedence over these images.
