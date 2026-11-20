import type { ThemeIcon } from './puzzleThemes'
import styles from './PuzzleTraining.module.css'

/** Simple line icons in the project style, drawn on a 24-unit grid with the current text color. */
const PATHS: Record<Exclude<ThemeIcon, `piece:${string}`>, string> = {
  book: 'M3 5c3-1 6-1 9 1 3-2 6-2 9-1v13c-3-1-6-1-9 1-3-2-6-2-9-1zM12 6v13',
  signpost: 'M12 3v18M5 6h11l3 2.5-3 2.5H5zM19 13H8l-3 2.5L8 18h11z',
  hourglass: 'M6 3h12M6 21h12M7 3c0 5 10 5 10 9s-10 4-10 9M17 3c0 5-10 5-10 9s10 4 10 9',
  pawnUp: 'M12 3l4 4h-3v4h-2V7H8zM9 13h6l1 4H8zM6 21h12',
  target: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 4a5 5 0 1 0 0 10 5 5 0 0 0 0-10zm0 4a1 1 0 1 0 0 2 1 1 0 0 0 0-2z',
  shieldBreak: 'M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6zM12 7l-2 5h4l-2 5',
  reveal: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zm10-3a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  doubleArrow: 'M4 20L14 10M14 10V15M14 10H9M10 20l10-10M20 10v5M20 10h-5',
  crownOpen: 'M4 18h16M5 16l-1-9 5 4 3-6 3 6 5-4-1 9M9 20h6',
  fork: 'M12 21v-8M12 13L6 4M12 13l6-9M6 4v4M6 4h4M18 4v4M18 4h-4',
  hanging: 'M12 3v6M8 9h8l-1 4H9zM10 13l-2 8h8l-2-8',
  kingside: 'M14 4h6v6M20 4l-9 9M4 20h8v-6',
  pin: 'M12 2l3 7h-6zM12 9v6M7 15h10M12 15v7',
  queenside: 'M10 4H4v6M4 4l9 9M20 20h-8v-6',
  sacrifice: 'M12 3c3 4 6 6 6 10a6 6 0 0 1-12 0c0-2 1-4 3-5 0 2 1 3 2 3 0-3 0-5 1-8z',
  skewer: 'M2 12h20M18 8l4 4-4 4M7 9v6M12 9v6',
  cage: 'M5 4h14v16H5zM9 4v16M12 4v16M15 4v16',
  magnet: 'M6 4v8a6 6 0 0 0 12 0V4h-4v8a2 2 0 0 1-4 0V4zM6 8h4M14 8h4',
  broom: 'M14 3l-4 9M7 12h7l3 9H4z',
  line: 'M3 21L21 3M6 18a2 2 0 1 0 0 .1M14 10a2 2 0 1 0 0 .1',
  shield: 'M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z',
  deflect: 'M3 12h8l6-7M17 5v4M17 5h-4M11 12l6 7',
  block: 'M3 12h6M15 12h6M9 6h6v12H9z',
  pause: 'M8 5v14M16 5v14',
  quiet: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM8 12h8',
  xray: 'M2 12h20M8 7v10M16 7v10M18 10l4 2-4 2',
  stopwatch: 'M12 8a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM12 11v4l3 2M10 2h4M12 2v4',
  crown: 'M3 18h18M4 16L3 7l5 4 4-7 4 7 5-4-1 9M8 21h8',
  castle: 'M5 21V8h3V5h3v3h2V5h3v3h3v13zM10 21v-5h4v5',
  enPassant: 'M5 19l7-7M12 12l7-7M5 5l14 14',
  promotion: 'M12 21V5M7 10l5-5 5 5M7 3h10',
  balance: 'M12 3v18M5 7h14M5 7l-3 7h6zM19 7l-3 7h6zM8 21h8',
  advantage: 'M3 20l6-6 4 4 8-9M15 9h6v6',
  crushing: 'M13 2L4 14h7l-1 8 9-12h-7z',
  length: 'M3 12h18M3 8v8M21 8v8M9 10v4M15 10v4',
  trophy: 'M7 4h10v4a5 5 0 0 1-10 0zM7 6H4a3 3 0 0 0 3 4M17 6h3a3 3 0 0 1-3 4M12 13v4M8 21h8M9 17h6',
}

export function PuzzleThemeIcon({ icon }: { icon: ThemeIcon }) {
  if (icon.startsWith('piece:')) {
    return (
      <span className={styles.themeIcon} data-kind="piece" aria-hidden="true">
        {icon.slice('piece:'.length)}{'︎'}
      </span>
    )
  }
  return (
    <span className={styles.themeIcon} aria-hidden="true">
      <svg viewBox="0 0 24 24" focusable="false">
        <path d={PATHS[icon as Exclude<ThemeIcon, `piece:${string}`>]} />
      </svg>
    </span>
  )
}
