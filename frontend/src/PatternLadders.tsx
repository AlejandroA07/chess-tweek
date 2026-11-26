import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { PuzzlePlayer, reportSessionFailure, type PlayerAttempt, type PlayerSource, type SessionFailure } from './PuzzlePlayer'
import { PuzzleThemeIcon } from './PuzzleThemeIcon'
import { formatCount, useLoaded } from './puzzleViews'
import styles from './PuzzleTraining.module.css'
import { THEME_SECTIONS, type ThemeEntry } from './puzzleThemes'
import {
  isPackageKey,
  loadLadder,
  loadLadderPatterns,
  nextLadderPuzzle,
  RATING_PACKAGES,
  requestPuzzleHint,
  restartLadder,
  revealPuzzle,
  submitPuzzleMove,
  type Ladder,
  type LadderPackage,
  type LadderPattern,
  type PuzzleCategory,
  type PuzzleCompletion,
  type RatingPackageKey,
} from './puzzles'

/** Pattern ladders (Stage 9): one pattern Theme climbed by fixed rating packages. */

export type LadderRoute =
  | { kind: 'ladders' }
  | { kind: 'ladder'; pattern: string }
  | { kind: 'ladder-play'; pattern: string; ratingPackage: RatingPackageKey }

/** The Themes page sections whose themes are pattern ladders (owner decision, 2026-11-26). */
const LADDER_SECTIONS = ['Motifs', 'Advanced', 'Mate themes']

const PATTERN = '([A-Za-z0-9_-]{1,120})'

/** The pattern-ladder route for a hash, or null when the hash is not one. */
export function ladderRoute(hash: string): LadderRoute | null {
  if (hash === '#/puzzles/ladders') return { kind: 'ladders' }
  const ladder = new RegExp(`^#/puzzles/ladders/${PATTERN}$`).exec(hash)
  if (ladder !== null) return { kind: 'ladder', pattern: ladder[1] }
  const play = new RegExp(`^#/puzzles/ladders/${PATTERN}/([a-z0-9-]{1,20})$`).exec(hash)
  if (play !== null && isPackageKey(play[2])) {
    return { kind: 'ladder-play', pattern: play[1], ratingPackage: play[2] }
  }
  return null
}

export function PatternLadders({
  route,
  categories,
  onSessionFailure,
}: {
  route: LadderRoute
  categories: PuzzleCategory[] | null
  onSessionFailure: (failure: SessionFailure) => void
}) {
  if (route.kind === 'ladders') return <LadderList onSessionFailure={onSessionFailure} />
  if (route.kind === 'ladder') {
    return <LadderView key={route.pattern} pattern={route.pattern} onSessionFailure={onSessionFailure} />
  }
  return (
    <LadderPlayer
      key={`${route.pattern}/${route.ratingPackage}`}
      pattern={route.pattern}
      ratingPackage={route.ratingPackage}
      categories={categories}
      onSessionFailure={onSessionFailure}
    />
  )
}

type PatternRow = ThemeEntry & { name: string; puzzles: number }

function LadderList({ onSessionFailure }: { onSessionFailure: (failure: SessionFailure) => void }) {
  const { value: patterns, state, reload } = useLoaded<LadderPattern[]>(loadLadderPatterns, onSessionFailure)
  const sections = useMemo(() => patternSections(patterns ?? []), [patterns])
  return (
    <div className={styles.page}>
      <section className={styles.categoryPage} aria-labelledby="ladders-title">
        <header className={styles.categoryHeader}>
          <div>
            <p className={styles.eyebrow}>Pattern ladders</p>
            <h2 id="ladders-title">Pattern ladders</h2>
            <p className={styles.muted}>
              Choose a mate or tactic, then a rating package. Its puzzles run from the lowest rating to the
              highest, and your place is saved, so you can stop and move to another package whenever you
              want. Counts include only well-tested puzzles; Mastered puzzles are skipped.
            </p>
          </div>
        </header>
        {state === 'loading' && <p className={styles.muted} aria-live="polite">Loading pattern ladders…</p>}
        {state === 'failed' && (
          <div className={styles.inlineError} role="alert">
            <p>The pattern ladders could not be loaded. Your Training Lists are unchanged.</p>
            <button className={styles.secondaryButton} type="button" onClick={reload}>Retry</button>
          </div>
        )}
        {state === 'ready' && sections.length === 0 && (
          <p className={styles.muted}>
            No pattern ladders yet. Import the Lichess Puzzle database from Admin to fill them.
          </p>
        )}
        {state === 'ready' && sections.length > 0 && (
          <div className={styles.themeSections}>
            {sections.map((section) => (
              <section key={section.title} className={styles.themeSection} aria-labelledby={sectionId(section.title)}>
                <h3 id={sectionId(section.title)}>{section.title}</h3>
                <ul className={styles.themeGrid}>
                  {section.rows.map((pattern) => (
                    <li key={pattern.key}>
                      <a className={styles.themeLink} href={`#/puzzles/ladders/${encodeURIComponent(pattern.key)}`}>
                        <PuzzleThemeIcon icon={pattern.icon} />
                        <span className={styles.themeText}>
                          <span className={styles.themeName}>
                            {pattern.name} <span className={styles.count}>{formatCount(pattern.puzzles)}</span>
                          </span>
                          {pattern.description !== '' && (
                            <span className={styles.themeDescription}>{pattern.description}</span>
                          )}
                        </span>
                      </a>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

/** Themes page order and wording; a pattern the catalog does not list follows in its group. */
function patternSections(patterns: LadderPattern[]) {
  const byKey = new Map(patterns.map((pattern) => [pattern.key, pattern]))
  const listed = new Set<string>()
  const sections = THEME_SECTIONS.filter((section) => LADDER_SECTIONS.includes(section.title)).map((section) => ({
    title: section.title,
    rows: section.themes.flatMap((entry): PatternRow[] => {
      const pattern = byKey.get(entry.key)
      if (pattern === undefined) return []
      listed.add(entry.key)
      return [{ ...entry, name: pattern.name, puzzles: pattern.puzzles }]
    }),
  }))
  for (const pattern of patterns) {
    if (listed.has(pattern.key)) continue
    const row: PatternRow = { key: pattern.key, icon: 'target', description: '', name: pattern.name, puzzles: pattern.puzzles }
    const section = sections.find((candidate) => candidate.title === pattern.group)
    if (section === undefined) sections.push({ title: pattern.group, rows: [row] })
    else section.rows.push(row)
  }
  return sections.filter((section) => section.rows.length > 0)
}

function LadderView({
  pattern,
  onSessionFailure,
}: {
  pattern: string
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const load = useCallback((signal: AbortSignal) => loadLadder(pattern, signal), [pattern])
  const { value: ladder, state, reload } = useLoaded<Ladder>(load, onSessionFailure)
  const description = THEME_SECTIONS.flatMap((section) => section.themes)
    .find((entry) => entry.key === pattern)?.description
  return (
    <div className={styles.page}>
      <section className={styles.categoryPage} aria-labelledby="ladder-title">
        <a className={styles.backLink} href="#/puzzles/ladders">← Pattern ladders</a>
        <header className={styles.categoryHeader}>
          <div>
            <p className={styles.eyebrow}>Pattern ladder</p>
            <h2 id="ladder-title">{ladder?.pattern.name ?? 'Pattern ladder'}</h2>
            {description !== undefined && <p>{description}</p>}
            <p className={styles.muted}>
              Choose a rating package. Puzzles run from its lowest rating to its highest and skip Mastered
              puzzles. Every attempt updates your Training Lists as usual.
            </p>
          </div>
        </header>
        {state === 'loading' && <p className={styles.muted} aria-live="polite">Loading the ladder…</p>}
        {state === 'failed' && (
          <div className={styles.inlineError} role="alert">
            <p>This ladder could not be loaded. Your place in it is unchanged.</p>
            <a className={styles.secondaryButton} href="#/puzzles/ladders">Back to Pattern ladders</a>
            <button className={styles.secondaryButton} type="button" onClick={reload}>Retry</button>
          </div>
        )}
        {state === 'ready' && ladder !== null && (
          <div className={styles.modules}>
            {ladder.packages.map((entry) => (
              <PackageCard
                key={entry.key}
                pattern={ladder.pattern}
                entry={entry}
                onRestarted={reload}
                onSessionFailure={onSessionFailure}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

function PackageCard({
  pattern,
  entry,
  onRestarted,
  onSessionFailure,
}: {
  pattern: LadderPattern
  entry: LadderPackage
  onRestarted: () => void
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const label = packageLabel(entry.key)
  const id = `package-${entry.key}`
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const keepRef = useRef<HTMLButtonElement>(null)
  const restartRef = useRef<HTMLButtonElement>(null)
  const wasConfirming = useRef(false)

  useEffect(() => {
    if (confirming) keepRef.current?.focus()
    else if (wasConfirming.current) restartRef.current?.focus()
    wasConfirming.current = confirming
  }, [confirming])

  const restart = async () => {
    setBusy(true)
    setError(null)
    try {
      await restartLadder(pattern.key, entry.key)
      setConfirming(false)
      onRestarted()
    } catch (requestError) {
      reportSessionFailure(requestError, onSessionFailure)
      setConfirming(false)
      setError('The package could not be restarted. Your place is unchanged; try again.')
    } finally {
      setBusy(false)
    }
  }

  const play = `#/puzzles/ladders/${encodeURIComponent(pattern.key)}/${entry.key}`
  return (
    <section className={styles.module} aria-labelledby={`${id}-title`}>
      <p className={styles.eyebrow}>Rating package</p>
      <h3 id={`${id}-title`}>
        {label} <span className={styles.count}>{formatCount(entry.puzzles)}</span>
      </h3>
      <p>
        {entry.progress === null
          ? 'Not started.'
          : `${formatCount(entry.progress.presented)} ${entry.progress.presented === 1 ? 'puzzle' : 'puzzles'} done. Last rating ${entry.progress.lastRating}.`}
      </p>
      {entry.puzzles === 0 ? (
        <p className={styles.muted}>No puzzles of this pattern in this rating range.</p>
      ) : confirming ? (
        <div className={styles.confirm} role="group" aria-labelledby={`${id}-confirm`}>
          <p id={`${id}-confirm`}>
            <strong>Restart {pattern.name}, {label}?</strong> Your place is forgotten and the package starts
            again from its lowest rating. Your Training Lists are not changed.
          </p>
          <div className={styles.actions}>
            <button
              ref={keepRef}
              className={styles.secondaryButton}
              type="button"
              disabled={busy}
              onClick={() => setConfirming(false)}
            >
              Keep my place
            </button>
            <button className={styles.dangerButton} type="button" disabled={busy} onClick={() => void restart()}>
              Restart package
            </button>
          </div>
        </div>
      ) : (
        <div className={styles.actions}>
          <a className={styles.primaryLink} href={play} aria-label={`${entry.progress === null ? 'Start' : 'Continue'} ${label}`}>
            {entry.progress === null ? 'Start' : 'Continue'}
          </a>
          {entry.progress !== null && (
            <button
              ref={restartRef}
              className={styles.textButton}
              type="button"
              aria-label={`Restart ${label}`}
              onClick={() => setConfirming(true)}
            >
              Restart
            </button>
          )}
        </div>
      )}
      {error && <p className={styles.error} role="alert">{error}</p>}
    </section>
  )
}

function LadderPlayer({
  pattern,
  ratingPackage,
  categories,
  onSessionFailure,
}: {
  pattern: string
  ratingPackage: RatingPackageKey
  categories: PuzzleCategory[] | null
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const name = categories?.find((category) => category.kind === 'theme' && category.key === pattern)?.name
    ?? pattern.replaceAll('_', ' ')
  const label = packageLabel(ratingPackage)
  const ladderHref = `#/puzzles/ladders/${encodeURIComponent(pattern)}`
  const themeNames = (completion: PuzzleCompletion) => completion.themes.map((key) =>
    categories?.find((category) => category.kind === 'theme' && category.key === key)?.name ?? key)

  const source: PlayerSource<PuzzleCompletion> = {
    key: `ladder/${pattern}/${ratingPackage}`,
    back: { href: ladderHref, label: name },
    eyebrow: `Pattern ladder · ${label}`,
    title: name,
    description: 'From the lowest rating to the highest, skipping Mastered puzzles. Your place is saved after each puzzle.',
    practice: false,
    nextLabel: 'Next in ladder',
    start: {
      kind: 'single',
      next: async (): Promise<PlayerAttempt> => {
        const step = await nextLadderPuzzle(pattern, ratingPackage)
        const attempt = step.attempt
        return {
          attemptId: attempt.attemptId,
          puzzleId: attempt.puzzleId,
          positionFen: attempt.positionFen,
          lastMove: attempt.setupMove,
          ownerMoves: attempt.ownerMoves,
          fastSolveLimitMillis: attempt.fastSolveLimitMillis,
          currentList: attempt.currentList,
          name: `Puzzle ${attempt.externalId}`,
          detail: `Lichess ${attempt.rating}`,
          discardable: false,
          fact: { label: 'Ladder', value: `Puzzle ${formatCount(step.progress.presented)} in ${label}` },
        }
      },
    },
    emptyText: `You have reached the end of ${label}: no puzzle is left above your place, apart from Mastered ones. Restart the package or choose another one.`,
    endAction: { href: ladderHref, label: 'Choose a package' },
    move: submitPuzzleMove,
    hint: requestPuzzleHint,
    reveal: revealPuzzle,
    completionExtra: (completion) => {
      const themes = themeNames(completion)
      return (
        <>
          {themes.length > 0 && <p className={styles.muted}>Themes: {themes.join(', ')}</p>}
          <p className={styles.muted}>
            The board stays open: keep moving pieces to explore. These moves are not checked or saved.
          </p>
          <a className={styles.externalLink} href={completion.gameUrl} target="_blank" rel="noreferrer">
            Open the source game on Lichess
          </a>
        </>
      )
    },
  }
  return <PuzzlePlayer source={source} onSessionFailure={onSessionFailure} />
}

function packageLabel(key: RatingPackageKey) {
  return RATING_PACKAGES.find((entry) => entry.key === key)?.label ?? key
}

function sectionId(title: string) {
  return `ladder-section-${title.toLowerCase().replaceAll(' ', '-')}`
}
