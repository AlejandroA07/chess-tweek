import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { InteractiveChessBoard } from './ChessBoard'
import { PuzzleThemeIcon } from './PuzzleThemeIcon'
import styles from './PuzzleTraining.module.css'
import {
  HIDDEN_THEMES,
  OPENINGS_SECTION_AFTER,
  OPENINGS_SECTION_SIZE,
  THEME_SECTIONS,
  type ThemeEntry,
} from './puzzleThemes'
import {
  ACTIVE_LISTS,
  LIST_LABELS,
  loadCategories,
  loadListCounts,
  loadMastered,
  nextSessionPuzzle,
  PuzzleRequestError,
  requestPuzzleHint,
  revealPuzzle,
  startCategoryPuzzle,
  startListSession,
  startMasteredSession,
  startRandomPuzzle,
  submitPuzzleMove,
  type ActiveList,
  type CategoryKind,
  type ListCount,
  type MasteredSummary,
  type PuzzleAttempt,
  type PuzzleCategory,
  type PuzzleCompletion,
} from './puzzles'

type ConnectionState = 'checking' | 'disconnected' | 'connected' | 'unavailable'
type SessionFailure = 'not_connected' | 'session_unavailable'
type LoadState = 'loading' | 'ready' | 'failed'

export type PuzzleRoute =
  | { kind: 'dashboard' }
  | { kind: 'themes' }
  | { kind: 'openings' }
  | { kind: 'random' }
  | { kind: 'category'; category: CategoryKind; key: string }
  | { kind: 'repetition' }
  | { kind: 'mastered' }
  | { kind: 'session'; list: ActiveList }
  | { kind: 'mastered-practice'; category: CategoryKind; key: string }

type PlayerRoute = Extract<PuzzleRoute, { kind: 'random' | 'category' | 'session' | 'mastered-practice' }>

/** The hash segment of each list a Practice Session covers. */
const LIST_SLUGS: Record<ActiveList, string> = {
  hard_to_solve: 'hard-to-solve',
  wrong_moves: 'wrong-moves',
  worth_repeating: 'worth-repeating',
  retiring: 'retiring',
}

/** What it takes to leave each active list, in the owner-approved rules' terms. */
const LIST_RULES: Record<ActiveList, string> = {
  hard_to_solve:
    'Ten Successful Repetitions in a row move a puzzle to Wrong Moves. A solution with at most one Wrong Move counts; two Wrong Moves or a hint restart the count.',
  wrong_moves:
    'Ten clean solutions in a row move a puzzle to Worth Repeating. One Wrong Move restarts the count; two Wrong Moves or a hint move it to Hard to Solve.',
  worth_repeating:
    'Ten clean solutions in a row within the fast-solve limit move a puzzle to Retiring. A slow solve restarts the count.',
  retiring:
    'Five clean solutions within the fast-solve limit move a puzzle to Mastered. The second slow solve moves it back to Worth Repeating.',
}

const KIND_LABELS: Record<CategoryKind, string> = {
  theme: 'Theme',
  'opening-family': 'Opening',
  'opening-variation': 'Opening variation',
}

/** The Puzzles sub-navigation; Planned entries are listed but not linked until they exist. */
export const PUZZLE_SECTIONS: { label: string; href: string | null }[] = [
  { label: 'Dashboard', href: '#/puzzles' },
  { label: 'Themes', href: '#/puzzles/themes' },
  { label: 'Openings', href: '#/puzzles/openings' },
  { label: 'Repetition: normal puzzles', href: '#/puzzles/repetition' },
  { label: 'Repetition: own puzzles', href: null },
]

/** The Puzzles route for a hash, or null when the hash is not a Puzzles route. */
export function puzzleRoute(hash: string): PuzzleRoute | null {
  if (hash === '#/puzzles') return { kind: 'dashboard' }
  if (hash === '#/puzzles/themes') return { kind: 'themes' }
  if (hash === '#/puzzles/openings') return { kind: 'openings' }
  if (hash === '#/puzzles/random') return { kind: 'random' }
  if (hash === '#/puzzles/repetition') return { kind: 'repetition' }
  if (hash === '#/puzzles/repetition/mastered') return { kind: 'mastered' }
  const list = ACTIVE_LISTS.find((candidate) => hash === `#/puzzles/repetition/${LIST_SLUGS[candidate]}`)
  if (list !== undefined) return { kind: 'session', list }
  const mastered = /^#\/puzzles\/repetition\/mastered\/(theme|opening-family|opening-variation)\/([A-Za-z0-9_-]{1,120})$/
    .exec(hash)
  if (mastered !== null) {
    return { kind: 'mastered-practice', category: mastered[1] as CategoryKind, key: mastered[2] }
  }
  const match = /^#\/puzzles\/category\/(theme|opening-family|opening-variation)\/([A-Za-z0-9_-]{1,120})$/
    .exec(hash)
  return match === null
    ? null
    : { kind: 'category', category: match[1] as CategoryKind, key: match[2] }
}

export function PuzzleTraining({
  connectionState,
  route,
  onSessionFailure,
}: {
  connectionState: ConnectionState
  route: PuzzleRoute
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const [categories, setCategories] = useState<PuzzleCategory[] | null>(null)
  const [categoriesState, setCategoriesState] = useState<LoadState>('loading')

  const load = useCallback(async (signal?: AbortSignal) => {
    if (connectionState !== 'connected') return
    setCategoriesState('loading')
    try {
      setCategories(await loadCategories(signal))
      setCategoriesState('ready')
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      reportSessionFailure(error, onSessionFailure)
      setCategoriesState('failed')
    }
  }, [connectionState, onSessionFailure])

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  if (connectionState !== 'connected') {
    return (
      <section className={styles.statePanel} aria-labelledby="puzzles-connect-title">
        <p className={styles.eyebrow}>Puzzle training</p>
        <h2 id="puzzles-connect-title">
          {connectionState === 'checking' ? 'Checking your Lichess connection…' : 'Connect Lichess to train puzzles'}
        </h2>
        {connectionState !== 'checking' && (
          <p>
            Puzzle attempts and Training Lists are saved for your connected account on this Mac.
            Connect Lichess from Dashboard to start.
          </p>
        )}
      </section>
    )
  }

  if (route.kind === 'dashboard') return <PuzzleDashboard />
  if (route.kind === 'repetition') return <RepetitionDashboard onSessionFailure={onSessionFailure} />
  if (route.kind === 'mastered') return <MasteredView onSessionFailure={onSessionFailure} />
  if (route.kind === 'themes' || route.kind === 'openings') {
    return (
      <CategoryPage
        kind={route.kind}
        categories={categories}
        state={categoriesState}
        onRetry={() => void load()}
      />
    )
  }
  return (
    <PuzzlePlayer
      key={playerKey(route)}
      route={route}
      categories={categories}
      onSessionFailure={onSessionFailure}
    />
  )
}

function PuzzleDashboard() {
  return (
    <div className={styles.page}>
      <section className={styles.intro} aria-labelledby="puzzles-title">
        <p className={styles.eyebrow}>Lichess Puzzle database</p>
        <h2 id="puzzles-title">Puzzle training</h2>
        <p>
          Your first completed attempt places a puzzle in a Training List by how you solved it:
          Wrong Moves, hints, and Solve Time. Lists are saved on this Mac and nothing is sent to Lichess.
        </p>
      </section>

      <div className={styles.modules}>
        <DashboardCard
          id="random"
          eyebrow="Random"
          title="One new puzzle at a time"
          text="Only puzzles you have not completed before. Your first completed attempt places each one in a Training List."
          href="#/puzzles/random"
          action="Start Random"
        />
        <DashboardCard
          id="themes"
          eyebrow="By Category"
          title="Themes"
          text="Forks, pins, mating patterns, endgames, and more, ordered as on Lichess."
          href="#/puzzles/themes"
          action="Browse themes"
        />
        <DashboardCard
          id="openings"
          eyebrow="By Category"
          title="Openings"
          text="Every opening family with all of its variations, by popularity or alphabetically."
          href="#/puzzles/openings"
          action="Browse openings"
        />
        <DashboardCard
          id="repetition"
          eyebrow="Repetition"
          title="Practice your Training Lists"
          text="Repeat Hard to Solve, Wrong Moves, Worth Repeating, and Retiring puzzles until they are Mastered."
          href="#/puzzles/repetition"
          action="Open Repetition"
        />
        <DashboardCard
          id="own"
          eyebrow="Own Puzzles"
          title="Puzzles from your games"
          text="Your own inaccuracies, mistakes, and blunders from My Moves analysis."
        />
      </div>
    </div>
  )
}

function DashboardCard({
  id,
  eyebrow,
  title,
  text,
  href,
  action,
}: {
  id: string
  eyebrow: string
  title: string
  text: string
  href?: string
  action?: string
}) {
  const planned = href === undefined
  return (
    <section className={styles.module} aria-labelledby={`${id}-title`} data-status={planned ? 'planned' : 'available'}>
      <p className={styles.eyebrow}>{eyebrow}</p>
      <h3 id={`${id}-title`}>{title}</h3>
      <p>{text}</p>
      {planned ? <strong>Planned</strong> : <a className={styles.primaryLink} href={href}>{action}</a>}
    </section>
  )
}

/** Loads one read-only view, reporting a revoked session; {@code reload} retries it. */
function useLoaded<T>(
  load: (signal: AbortSignal) => Promise<T>,
  onSessionFailure: (failure: SessionFailure) => void,
) {
  const [value, setValue] = useState<T | null>(null)
  const [state, setState] = useState<LoadState>('loading')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setState('loading')
    load(controller.signal).then((loaded) => {
      setValue(loaded)
      setState('ready')
    }, (error: unknown) => {
      if (error instanceof DOMException && error.name === 'AbortError') return
      reportSessionFailure(error, onSessionFailure)
      setState('failed')
    })
    return () => controller.abort()
  }, [load, onSessionFailure, attempt])
  return { value, state, reload: () => setAttempt((current) => current + 1) }
}

function RepetitionDashboard({ onSessionFailure }: { onSessionFailure: (failure: SessionFailure) => void }) {
  const { value: counts, state, reload } = useLoaded<ListCount[]>(loadListCounts, onSessionFailure)
  const count = (list: ListCount['list']) => counts?.find((entry) => entry.list === list)?.puzzles ?? 0
  return (
    <div className={styles.page}>
      <section className={styles.intro} aria-labelledby="repetition-title">
        <p className={styles.eyebrow}>Repetition · Normal puzzles</p>
        <h2 id="repetition-title">Practice your Training Lists</h2>
        <p>
          Choose a list to start a Practice Session. Each session shows every puzzle in the list once,
          in a fresh random order, before any puzzle repeats. Your results move puzzles between lists
          until they are Mastered.
        </p>
      </section>
      {state === 'loading' && <p className={styles.muted} aria-live="polite">Loading your Training Lists…</p>}
      {state === 'failed' && (
        <div className={styles.inlineError} role="alert">
          <p>Your Training Lists could not be loaded. Nothing has changed.</p>
          <button className={styles.secondaryButton} type="button" onClick={reload}>Retry</button>
        </div>
      )}
      {state === 'ready' && counts !== null && (
        <div className={styles.modules}>
          {ACTIVE_LISTS.map((list) => (
            <ListCard
              key={list}
              id={`list-${LIST_SLUGS[list]}`}
              title={LIST_LABELS[list]}
              puzzles={count(list)}
              text={LIST_RULES[list]}
              href={`#/puzzles/repetition/${LIST_SLUGS[list]}`}
              action={`Practice ${LIST_LABELS[list]}`}
              empty="No puzzles in this list yet."
            />
          ))}
          <ListCard
            id="list-mastered"
            title="Mastered"
            puzzles={count('mastered')}
            text="Puzzles that no longer need repetition, grouped by theme and opening. Practicing them never changes their status."
            href="#/puzzles/repetition/mastered"
            action="Browse Mastered"
            empty="No Mastered puzzles yet."
          />
        </div>
      )}
    </div>
  )
}

function ListCard({
  id,
  title,
  puzzles,
  text,
  href,
  action,
  empty,
}: {
  id: string
  title: string
  puzzles: number
  text: string
  href: string
  action: string
  empty: string
}) {
  return (
    <section className={styles.module} aria-labelledby={`${id}-title`}>
      <p className={styles.eyebrow}>Training List</p>
      <h3 id={`${id}-title`}>
        {title} <span className={styles.count}>{formatCount(puzzles)}</span>
      </h3>
      <p>{text}</p>
      {puzzles === 0 ? <p className={styles.muted}>{empty}</p> : <a className={styles.primaryLink} href={href}>{action}</a>}
    </section>
  )
}

function MasteredView({ onSessionFailure }: { onSessionFailure: (failure: SessionFailure) => void }) {
  const { value: summary, state, reload } = useLoaded<MasteredSummary>(loadMastered, onSessionFailure)
  const themes = useMemo(
    () => (summary?.categories ?? [])
      .filter((category) => category.kind === 'theme' && !HIDDEN_THEMES.has(category.key))
      .sort(byCount),
    [summary],
  )
  const families = useMemo(
    () => summary === null ? [] : openingFamilies(summary.categories, 'popular'),
    [summary],
  )
  return (
    <div className={styles.page}>
      <section className={styles.categoryPage} aria-labelledby="mastered-title">
        <a className={styles.backLink} href="#/puzzles/repetition">← Repetition</a>
        <header className={styles.categoryHeader}>
          <div>
            <p className={styles.eyebrow}>Repetition · Normal puzzles</p>
            <h2 id="mastered-title">Mastered puzzles</h2>
            <p className={styles.muted}>
              Choose a theme or opening to practice its Mastered puzzles. Practice is optional: it never
              changes Mastered status or starts repetition progress. A puzzle appears under every theme
              and opening it belongs to.
            </p>
          </div>
        </header>
        {state === 'loading' && <p className={styles.muted} aria-live="polite">Loading Mastered puzzles…</p>}
        {state === 'failed' && (
          <div className={styles.inlineError} role="alert">
            <p>Mastered puzzles could not be loaded. Nothing has changed.</p>
            <button className={styles.secondaryButton} type="button" onClick={reload}>Retry</button>
          </div>
        )}
        {state === 'ready' && summary !== null && (
          summary.total === 0 ? (
            <p className={styles.muted}>
              No Mastered puzzles yet. A puzzle is Mastered after five fast, clean solutions in Retiring.
            </p>
          ) : (
            <div className={styles.themeSections}>
              <p className={styles.muted}>
                {formatCount(summary.total)} Mastered {summary.total === 1 ? 'puzzle' : 'puzzles'}.
              </p>
              {themes.length > 0 && (
                <section className={styles.themeSection} aria-labelledby="mastered-themes">
                  <h3 id="mastered-themes">By theme</h3>
                  <ul className={styles.themeGrid}>
                    {themes.map((theme) => (
                      <li key={theme.key}>
                        <a className={styles.themeLink} href={masteredHref(theme)}>
                          <span className={styles.themeText}>
                            <span className={styles.themeName}>
                              {theme.name} <span className={styles.count}>{formatCount(theme.puzzles)}</span>
                            </span>
                          </span>
                        </a>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
              {families.length > 0 && (
                <section className={styles.themeSection} aria-labelledby="mastered-openings">
                  <h3 id="mastered-openings">By opening</h3>
                  {families.map((family) => (
                    <div key={family.key} className={styles.openingFamily}>
                      <h4>
                        <a href={masteredHref(family)}>{family.name}</a>{' '}
                        <span className={styles.count}>{formatCount(family.puzzles)}</span>
                      </h4>
                      {family.variations.length > 0 && (
                        <ul className={styles.variationGrid}>
                          {family.variations.map((variation) => (
                            <li key={variation.key}>
                              <a href={masteredHref(variation)}>{variation.name}</a>{' '}
                              <span className={styles.count}>{formatCount(variation.puzzles)}</span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  ))}
                </section>
              )}
            </div>
          )
        )}
      </section>
    </div>
  )
}

function byCount(left: PuzzleCategory, right: PuzzleCategory) {
  return right.puzzles - left.puzzles || left.name.localeCompare(right.name)
}

function masteredHref(category: PuzzleCategory) {
  return `#/puzzles/repetition/mastered/${category.kind}/${encodeURIComponent(category.key)}`
}

function CategoryPage({
  kind,
  categories,
  state,
  onRetry,
}: {
  kind: 'themes' | 'openings'
  categories: PuzzleCategory[] | null
  state: LoadState
  onRetry: () => void
}) {
  const title = kind === 'themes' ? 'Puzzle themes' : 'Puzzles by opening'
  return (
    <div className={styles.page}>
      <section className={styles.categoryPage} aria-labelledby="category-page-title">
        <header className={styles.categoryHeader}>
          <div>
            <p className={styles.eyebrow}>By Category</p>
            <h2 id="category-page-title">{title}</h2>
            <p className={styles.muted}>
              Choose one to open a puzzle right away. Puzzles already in a Training List are
              included; Mastered puzzles are skipped.
            </p>
          </div>
        </header>
        {state === 'loading' && <p className={styles.muted} aria-live="polite">Loading categories…</p>}
        {state === 'failed' && (
          <div className={styles.inlineError} role="alert">
            <p>The categories could not be loaded. Your Training Lists are unchanged.</p>
            <button className={styles.secondaryButton} type="button" onClick={onRetry}>Retry</button>
          </div>
        )}
        {state === 'ready' && categories !== null && categories.length === 0 && (
          <p className={styles.muted}>
            No categories yet. Import the Lichess Puzzle database from Admin to train by theme or opening.
          </p>
        )}
        {state === 'ready' && categories !== null && categories.length > 0 && (
          kind === 'themes' ? <ThemeList categories={categories} /> : <OpeningList categories={categories} />
        )}
      </section>
    </div>
  )
}

type ThemeRow = ThemeEntry & { name: string; puzzles: number }

function ThemeList({ categories }: { categories: PuzzleCategory[] }) {
  const sections = useMemo(() => themeSections(categories), [categories])
  const families = useMemo(
    () => categories
      .filter((category) => category.kind === 'opening-family')
      .sort((left, right) => right.puzzles - left.puzzles || left.name.localeCompare(right.name))
      .slice(0, OPENINGS_SECTION_SIZE),
    [categories],
  )
  return (
    <div className={styles.themeSections}>
      {sections.map((section) => (
        <div key={section.title} className={styles.themeSectionGroup}>
          <section className={styles.themeSection} aria-labelledby={sectionId(section.title)}>
            <h3 id={sectionId(section.title)}>{section.title}</h3>
            <ul className={styles.themeGrid}>
              {section.rows.map((theme) => (
                <li key={`${section.title}/${theme.key}`}>
                  <a className={styles.themeLink} href={`#/puzzles/category/theme/${encodeURIComponent(theme.key)}`}>
                    <PuzzleThemeIcon icon={theme.icon} />
                    <span className={styles.themeText}>
                      <span className={styles.themeName}>
                        {theme.name} <span className={styles.count}>{formatCount(theme.puzzles)}</span>
                      </span>
                      <span className={styles.themeDescription}>{theme.description}</span>
                    </span>
                  </a>
                </li>
              ))}
            </ul>
          </section>
          {section.title === OPENINGS_SECTION_AFTER && families.length > 0 && (
            <section className={styles.themeSection} aria-labelledby="themes-by-openings">
              <h3 id="themes-by-openings">
                By openings <a className={styles.moreLink} href="#/puzzles/openings">More »</a>
              </h3>
              <ul className={styles.themeGrid}>
                {families.map((family) => (
                  <li key={family.key}>
                    <a className={styles.themeLink} href={categoryHref(family)}>
                      <PuzzleThemeIcon icon="book" />
                      <span className={styles.themeText}>
                        <span className={styles.themeName}>
                          {family.name} <span className={styles.count}>{formatCount(family.puzzles)}</span>
                        </span>
                      </span>
                    </a>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      ))}
    </div>
  )
}

/** Catalog order first; a backend Theme the catalog does not list follows in its own group. */
function themeSections(categories: PuzzleCategory[]) {
  const themes = new Map(
    categories.filter((category) => category.kind === 'theme').map((theme) => [theme.key, theme]),
  )
  const listed = new Set<string>()
  const sections = THEME_SECTIONS.map((section) => ({
    title: section.title,
    rows: section.themes.flatMap((entry): ThemeRow[] => {
      const theme = themes.get(entry.key)
      if (theme === undefined) return []
      listed.add(entry.key)
      return [{ ...entry, name: theme.name, puzzles: theme.puzzles }]
    }),
  }))
  for (const theme of themes.values()) {
    if (listed.has(theme.key) || HIDDEN_THEMES.has(theme.key)) continue
    const row: ThemeRow = { key: theme.key, icon: 'target', description: '', name: theme.name, puzzles: theme.puzzles }
    const section = sections.find((candidate) => candidate.title === theme.group)
    if (section === undefined) sections.push({ title: theme.group, rows: [row] })
    else section.rows.push(row)
  }
  return sections.filter((section) => section.rows.length > 0)
}

type OpeningOrder = 'popular' | 'alphabetical'

function OpeningList({ categories }: { categories: PuzzleCategory[] }) {
  const [order, setOrder] = useState<OpeningOrder>('popular')
  const [query, setQuery] = useState('')
  const families = useMemo(() => openingFamilies(categories, order), [categories, order])
  const normalized = query.trim().toLowerCase()
  const visible = normalized === ''
    ? families
    : families.flatMap((family) => {
      if (family.name.toLowerCase().includes(normalized)) return [family]
      const variations = family.variations.filter((variation) =>
        variation.name.toLowerCase().includes(normalized))
      return variations.length === 0 ? [] : [{ ...family, variations }]
    })

  return (
    <div className={styles.openings}>
      <div className={styles.openingControls}>
        <div className={styles.field}>
          <label htmlFor="opening-filter">Filter openings</label>
          <input
            id="opening-filter"
            type="search"
            value={query}
            maxLength={80}
            placeholder="For example, Najdorf"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className={styles.field}>
          <label htmlFor="opening-order">Order</label>
          <select
            id="opening-order"
            value={order}
            onChange={(event) => setOrder(event.target.value as OpeningOrder)}
          >
            <option value="popular">Most popular</option>
            <option value="alphabetical">Alphabetical</option>
          </select>
        </div>
      </div>
      <p className={styles.muted} aria-live="polite">
        {normalized === ''
          ? `${families.length.toLocaleString()} opening families.`
          : visible.length === 0
            ? 'No openings match.'
            : `${visible.length.toLocaleString()} ${visible.length === 1 ? 'family matches' : 'families match'}.`}
      </p>
      {visible.map((family) => (
        <section key={family.key} className={styles.openingFamily} aria-labelledby={`family-${family.key}`}>
          <h3 id={`family-${family.key}`}>
            <a href={categoryHref(family)}>{family.name}</a>{' '}
            <span className={styles.count}>{formatCount(family.puzzles)}</span>
          </h3>
          {family.variations.length > 0 && (
            <ul className={styles.variationGrid}>
              {family.variations.map((variation) => (
                <li key={variation.key}>
                  <a href={categoryHref(variation)}>{variation.name}</a>{' '}
                  <span className={styles.count}>{formatCount(variation.puzzles)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </div>
  )
}

type OpeningFamily = PuzzleCategory & { variations: PuzzleCategory[] }

/** Families with their variations, named without the repeated family prefix, as on Lichess. */
function openingFamilies(categories: PuzzleCategory[], order: OpeningOrder): OpeningFamily[] {
  const compare = order === 'popular'
    ? (left: PuzzleCategory, right: PuzzleCategory) =>
      right.puzzles - left.puzzles || left.name.localeCompare(right.name)
    : (left: PuzzleCategory, right: PuzzleCategory) => left.name.localeCompare(right.name)
  const families = new Map<string, OpeningFamily>()
  for (const category of categories) {
    if (category.kind === 'opening-family') families.set(category.key, { ...category, variations: [] })
  }
  for (const category of categories) {
    if (category.kind !== 'opening-variation' || category.parentKey === null) continue
    const family = families.get(category.parentKey)
    if (family === undefined) continue
    const prefix = `${family.name} `
    family.variations.push({
      ...category,
      name: category.name.startsWith(prefix) ? category.name.slice(prefix.length) : category.name,
    })
  }
  const sorted = [...families.values()].sort(compare)
  for (const family of sorted) family.variations.sort(compare)
  return sorted
}

function sectionId(title: string) {
  return `theme-section-${title.toLowerCase().replaceAll(' ', '-')}`
}

function formatCount(value: number) {
  return value.toLocaleString('en-US')
}

type Phase =
  | { kind: 'ready' }
  | { kind: 'starting' }
  | { kind: 'playing'; attempt: PuzzleAttempt }
  | { kind: 'complete'; attempt: PuzzleAttempt; completion: PuzzleCompletion; revealed: boolean }

function PuzzlePlayer({
  route,
  categories,
  onSessionFailure,
}: {
  route: PlayerRoute
  categories: PuzzleCategory[] | null
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const [phase, setPhase] = useState<Phase>({ kind: 'starting' })
  // A Practice Session is created when the route opens and again only after it ends.
  const [sessionId, setSessionId] = useState<number | null>(null)
  const [progress, setProgress] = useState<{ position: number; total: number } | null>(null)
  const practice = route.kind === 'mastered-practice'
  const inSession = route.kind === 'session' || route.kind === 'mastered-practice'
  const [line, setLine] = useState<string[]>([])
  const [wrongMoves, setWrongMoves] = useState(0)
  const [hintSquare, setHintSquare] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState<{ tone: 'correct' | 'incorrect'; text: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const statusRef = useRef<HTMLDivElement>(null)
  const puzzleTitleRef = useRef<HTMLHeadingElement>(null)
  const startRef = useRef<HTMLButtonElement>(null)

  const categoryName = route.kind === 'category' || route.kind === 'mastered-practice'
    ? categories?.find((category) => category.kind === route.category && category.key === route.key)?.name
      ?? route.key.replaceAll('_', ' ')
    : null
  const title = route.kind === 'random'
    ? 'Random puzzle'
    : route.kind === 'session' ? LIST_LABELS[route.list] : categoryName ?? ''

  const nextAttempt = async (): Promise<PuzzleAttempt> => {
    if (route.kind === 'random') return startRandomPuzzle()
    if (route.kind === 'category') return startCategoryPuzzle(route.category, route.key)
    let current = sessionId
    if (current === null) {
      const session = route.kind === 'session'
        ? await startListSession(route.list)
        : await startMasteredSession(route.category, route.key)
      current = session.sessionId
      setSessionId(current)
    }
    const next = await nextSessionPuzzle(current)
    setProgress({ position: next.position, total: next.total })
    return next.attempt
  }

  const startFailure = (requestError: unknown) => {
    const code = requestError instanceof PuzzleRequestError ? requestError.code : null
    if (code === 'session_complete' || code === 'session_not_found') setSessionId(null)
    if (code === 'session_complete') {
      const practiced = progress === null || progress.total === 1 ? 'every puzzle' : `all ${progress.total} puzzles`
      return `Session complete: you practiced ${practiced} once. A new session shuffles them again.`
    }
    if (code === 'session_not_found') {
      return 'This session ended because another Practice Session was started. Start a new session to continue.'
    }
    if (code !== 'no_puzzle_available') return 'A puzzle could not be started. Try again.'
    if (route.kind === 'random') {
      return 'No new puzzle is available: every stored puzzle is already in one of your Training Lists, or the library is empty.'
    }
    if (route.kind === 'category') return 'No puzzle is available: every puzzle in this category is Mastered.'
    if (route.kind === 'session') return `${LIST_LABELS[route.list]} has no puzzles right now.`
    return 'You have no Mastered puzzles in this category.'
  }

  const start = async () => {
    setPhase({ kind: 'starting' })
    setLine([])
    setWrongMoves(0)
    setHintSquare(null)
    setFeedback(null)
    setError(null)
    try {
      // The attempt (and its Solve Time) starts here; the board is interactive as soon as it returns.
      const attempt = await nextAttempt()
      setPhase({ kind: 'playing', attempt })
    } catch (requestError) {
      reportSessionFailure(requestError, onSessionFailure)
      setPhase({ kind: 'ready' })
      setError(startFailure(requestError))
    }
  }

  const failed = (requestError: unknown) => {
    reportSessionFailure(requestError, onSessionFailure)
    if (requestError instanceof PuzzleRequestError && requestError.code === 'attempt_finished') {
      setPhase({ kind: 'ready' })
      setError('This attempt has ended, for example because another puzzle was started.')
      return
    }
    setError('That action could not be completed. Try again.')
  }

  const move = async (uci: string) => {
    // After completion the board stays open for local exploration; nothing is sent or saved.
    if (phase.kind === 'complete') {
      setFeedback(null)
      return true
    }
    if (phase.kind !== 'playing') return false
    setBusy(true)
    setError(null)
    try {
      const result = await submitPuzzleMove(phase.attempt.attemptId, uci)
      setWrongMoves(result.wrongMoves)
      if (result.outcome === 'wrong') {
        setFeedback({ tone: 'incorrect', text: 'Not the solution. The position is unchanged; try another move.' })
        return false
      }
      setHintSquare(null)
      if (result.outcome === 'solved') {
        setLine((current) => [...current, uci])
        setFeedback({ tone: 'correct', text: 'Solved.' })
        setPhase({ kind: 'complete', attempt: phase.attempt, completion: result.completion, revealed: false })
        return true
      }
      setLine((current) => [...current, uci, result.opponentReply])
      setFeedback({ tone: 'correct', text: 'Correct. Your opponent replied; find the next move.' })
      return true
    } catch (requestError) {
      if (requestError instanceof PuzzleRequestError && requestError.code === 'illegal_move') {
        setError('That move is not legal in this position.')
        return false
      }
      failed(requestError)
      return false
    } finally {
      setBusy(false)
    }
  }

  const hint = async () => {
    if (phase.kind !== 'playing') return
    setBusy(true)
    setError(null)
    try {
      setHintSquare(await requestPuzzleHint(phase.attempt.attemptId))
      setFeedback(null)
    } catch (requestError) {
      failed(requestError)
    } finally {
      setBusy(false)
    }
  }

  const reveal = async () => {
    if (phase.kind !== 'playing') return
    setBusy(true)
    setError(null)
    try {
      const result = await revealPuzzle(phase.attempt.attemptId)
      setHintSquare(null)
      setLine((current) => [...current, ...result.remainingMoves])
      setFeedback(null)
      setPhase({ kind: 'complete', attempt: phase.attempt, completion: result.completion, revealed: true })
    } catch (requestError) {
      failed(requestError)
    } finally {
      setBusy(false)
    }
  }

  // Opening a Random or By Category route starts its first puzzle directly. The ref keeps
  // React's development double-mount from starting (and abandoning) a second attempt.
  const autoStarted = useRef(false)
  useEffect(() => {
    if (autoStarted.current) return
    autoStarted.current = true
    void start()
  }, [])

  // Keep focus on the content that replaced the control the owner just used.
  const previousPhase = useRef<Phase['kind']>(phase.kind)
  useEffect(() => {
    const before = previousPhase.current
    previousPhase.current = phase.kind
    if (before === phase.kind) return
    if (phase.kind === 'complete') statusRef.current?.focus()
    else if (phase.kind === 'playing') puzzleTitleRef.current?.focus()
    else if (phase.kind === 'ready') startRef.current?.focus()
  }, [phase.kind])

  const attempt = phase.kind === 'playing' || phase.kind === 'complete' ? phase.attempt : null
  const side = attempt === null ? null : sideToMove(attempt.positionFen)

  return (
    <article className={styles.playerPage}>
      {route.kind === 'session' ? (
        <a className={styles.backLink} href="#/puzzles/repetition">← Repetition</a>
      ) : route.kind === 'mastered-practice' ? (
        <a className={styles.backLink} href="#/puzzles/repetition/mastered">← Mastered puzzles</a>
      ) : (
        <a className={styles.backLink} href="#/puzzles">← Puzzles</a>
      )}
      <header className={styles.playerHeader}>
        <div>
          <p className={styles.eyebrow}>
            {route.kind === 'random'
              ? 'Random'
              : route.kind === 'category'
                ? `By Category · ${KIND_LABELS[route.category]}`
                : route.kind === 'session'
                  ? 'Repetition · Practice Session'
                  : `Mastered practice · ${KIND_LABELS[route.category]}`}
          </p>
          <h2>{title}</h2>
          <p>
            {route.kind === 'random'
              ? 'Puzzles you have not completed before, one at a time.'
              : route.kind === 'category'
                ? 'Any puzzle in this category except Mastered ones.'
                : route.kind === 'session'
                  ? 'Every puzzle in this list once, in a fresh random order.'
                  : 'Your Mastered puzzles in this category, in a fresh random order. Practice only: attempts never change Mastered status.'}
          </p>
        </div>
      </header>

      {attempt === null ? (
        <section className={styles.startPanel} aria-labelledby="start-title">
          <h3 id="start-title" aria-live="polite">
            {phase.kind === 'starting' ? 'Loading a puzzle…' : 'No puzzle is open'}
          </h3>
          {error && <p className={styles.error} role="alert">{error}</p>}
          {phase.kind === 'ready' && (
            <button
              ref={startRef}
              className={styles.primaryButton}
              type="button"
              onClick={() => void start()}
            >
              {inSession && sessionId === null ? 'Start a new session' : 'Try another puzzle'}
            </button>
          )}
        </section>
      ) : (
        <section className={styles.puzzleCard} aria-labelledby="puzzle-title">
          <div className={styles.board}>
            <InteractiveChessBoard
              key={attempt.attemptId}
              fen={attempt.positionFen}
              orientation={side ?? 'white'}
              label={`Puzzle ${attempt.externalId}: ${side === 'black' ? 'Black' : 'White'} to move`}
              disabled={busy}
              solution={null}
              line={line}
              initialLastMove={attempt.setupMove}
              hintSquare={hintSquare}
              onMove={move}
            />
          </div>
          <div className={styles.panel}>
            <p className={styles.eyebrow}>
              Lichess {attempt.rating} · {attempt.ownerMoves} {attempt.ownerMoves === 1 ? 'move' : 'moves'} to find
            </p>
            <h3 id="puzzle-title" ref={puzzleTitleRef} tabIndex={-1}>
              {side === 'black' ? 'Black' : 'White'} to move
            </h3>
            <dl className={styles.facts}>
              <div>
                <dt>Training List</dt>
                <dd>{attempt.currentList === null ? 'New puzzle' : LIST_LABELS[attempt.currentList]}</dd>
              </div>
              <div>
                <dt>Fast-solve limit</dt>
                <dd>{seconds(attempt.fastSolveLimitMillis)}</dd>
              </div>
              <div>
                <dt>Wrong Moves</dt>
                <dd>{wrongMoves}</dd>
              </div>
              {inSession && progress !== null && (
                <div>
                  <dt>Session</dt>
                  <dd>{progress.position} of {progress.total}</dd>
                </div>
              )}
            </dl>
            <div ref={statusRef} tabIndex={-1} className={styles.status} aria-live="polite">
              {feedback && (
                <p className={styles.feedback} data-outcome={feedback.tone}>
                  <strong>{feedback.tone === 'correct' ? 'Correct' : 'Wrong Move'}</strong>
                  {' '}{feedback.text}
                </p>
              )}
              {hintSquare && phase.kind === 'playing' && (
                <p className={styles.hint}>
                  <strong>Hint:</strong> move the piece on {hintSquare}.{' '}
                  {practice
                    ? 'Practice only: the puzzle stays Mastered.'
                    : 'This puzzle goes to Hard to Solve, with its progress there restarted.'}
                </p>
              )}
              {phase.kind === 'complete' && (
                <CompletionSummary
                  completion={phase.completion}
                  revealed={phase.revealed}
                  practice={practice}
                  categories={categories}
                />
              )}
            </div>
            {error && <p className={styles.error} role="alert">{error}</p>}
            <div className={styles.actions}>
              {phase.kind === 'playing' ? (
                <>
                  <button
                    className={styles.secondaryButton}
                    type="button"
                    disabled={busy || hintSquare !== null}
                    onClick={() => void hint()}
                  >
                    {hintSquare === null ? 'Hint' : 'Hint shown on the board'}
                  </button>
                  <button
                    className={styles.textButton}
                    type="button"
                    disabled={busy}
                    onClick={() => void reveal()}
                  >
                    Show solution
                  </button>
                </>
              ) : (
                <button className={styles.primaryButton} type="button" onClick={() => void start()}>
                  {route.kind === 'category' ? `Next in ${title}` : 'Next puzzle'}
                </button>
              )}
            </div>
            {phase.kind === 'playing' && (
              <p className={styles.muted}>
                {practice
                  ? 'Practice only: a hint or Show solution does not change Mastered status.'
                  : 'A hint or Show solution places this puzzle in Hard to Solve and restarts its progress there.'}
              </p>
            )}
          </div>
        </section>
      )}
    </article>
  )
}

function CompletionSummary({
  completion,
  revealed,
  practice,
  categories,
}: {
  completion: PuzzleCompletion
  revealed: boolean
  practice: boolean
  categories: PuzzleCategory[] | null
}) {
  const list = LIST_LABELS[completion.list]
  const themeNames = completion.themes.map((key) =>
    categories?.find((category) => category.kind === 'theme' && category.key === key)?.name ?? key)
  return (
    <div className={styles.summary}>
      <p>
        <strong>{revealed ? 'Solution shown.' : 'Puzzle complete.'}</strong>{' '}
        {practice
          ? 'Practice only: stays Mastered.'
          : completion.previousList === null
          ? `Placed in ${list}.`
          : completion.previousList === completion.list
            ? `Stays in ${list}.`
            : `Moved from ${LIST_LABELS[completion.previousList]} to ${list}.`}
      </p>
      <dl className={styles.facts}>
        <div>
          <dt>Solve Time</dt>
          <dd>
            {revealed
              ? `${seconds(completion.elapsedMillis)}, solution shown`
              : `${seconds(completion.elapsedMillis)} of ${seconds(completion.fastSolveLimitMillis)}`}
          </dd>
        </div>
        <div>
          <dt>Wrong Moves</dt>
          <dd>{completion.wrongMoves}</dd>
        </div>
        <div>
          <dt>Hint</dt>
          <dd>{completion.hintUsed ? 'Used' : 'Not used'}</dd>
        </div>
        {completion.previousList !== null && completion.list !== 'mastered' && (
          <div>
            <dt>Progress in {list}</dt>
            <dd>
              {completion.successes} {completion.successes === 1 ? 'success' : 'successes'}
              {completion.list === 'retiring' && `, ${completion.strikes} time ${completion.strikes === 1 ? 'strike' : 'strikes'}`}
            </dd>
          </div>
        )}
      </dl>
      {themeNames.length > 0 && <p className={styles.muted}>Themes: {themeNames.join(', ')}</p>}
      <p className={styles.muted}>
        The board stays open: keep moving pieces to explore. These moves are not checked or saved.
      </p>
      <a className={styles.externalLink} href={completion.gameUrl} target="_blank" rel="noreferrer">
        Open the source game on Lichess
      </a>
    </div>
  )
}

function playerKey(route: PlayerRoute) {
  if (route.kind === 'random') return 'random'
  if (route.kind === 'session') return `session/${route.list}`
  return `${route.kind}/${route.category}/${route.key}`
}

function categoryHref(category: PuzzleCategory) {
  return `#/puzzles/category/${category.kind}/${encodeURIComponent(category.key)}`
}

function sideToMove(fen: string): 'white' | 'black' {
  return fen.split(' ')[1] === 'b' ? 'black' : 'white'
}

/** Rounds down, so a time under the limit never displays as the limit itself. */
function seconds(milliseconds: number) {
  const tenths = Math.floor(milliseconds / 100)
  return tenths % 10 === 0 ? `${tenths / 10} s` : `${(tenths / 10).toFixed(1)} s`
}

function reportSessionFailure(error: unknown, onSessionFailure: (failure: SessionFailure) => void) {
  if (error instanceof PuzzleRequestError &&
      (error.code === 'not_connected' || error.code === 'session_unavailable')) {
    onSessionFailure(error.code)
  }
}
