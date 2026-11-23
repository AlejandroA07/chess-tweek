import { useCallback, useEffect, useMemo, useState } from 'react'
import { OwnPuzzles, ownPuzzleRoute, type OwnRoute } from './OwnPuzzles'
import {
  PuzzlePlayer,
  reportSessionFailure,
  type PlayerAttempt,
  type PlayerSource,
  type SessionFailure,
} from './PuzzlePlayer'
import { PuzzleThemeIcon } from './PuzzleThemeIcon'
import { formatCount, LIST_RULES, LIST_SLUGS, ListCard, useLoaded, type LoadState } from './puzzleViews'
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
  | OwnRoute

type PlayerRoute = Extract<PuzzleRoute, { kind: 'random' | 'category' | 'session' | 'mastered-practice' }>

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
  { label: 'Own Puzzles', href: '#/puzzles/own' },
  { label: 'Repetition: normal puzzles', href: '#/puzzles/repetition' },
  { label: 'Repetition: own puzzles', href: '#/puzzles/own/repetition' },
]

/** The Puzzles route for a hash, or null when the hash is not a Puzzles route. */
export function puzzleRoute(hash: string): PuzzleRoute | null {
  if (hash === '#/puzzles/own' || hash.startsWith('#/puzzles/own/')) return ownPuzzleRoute(hash)
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

  if (route.kind.startsWith('own-')) {
    return <OwnPuzzles route={route as OwnRoute} onSessionFailure={onSessionFailure} />
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
    <NormalPuzzlePlayer
      key={playerKey(route as PlayerRoute)}
      route={route as PlayerRoute}
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
          text="Your own inaccuracies, mistakes, and blunders from My Moves analysis, kept apart from the Lichess library."
          href="#/puzzles/own"
          action="Open Own Puzzles"
          secondary={{ href: '#/puzzles/own/new', label: 'New Own Puzzles' }}
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
  secondary,
}: {
  id: string
  eyebrow: string
  title: string
  text: string
  href?: string
  action?: string
  /** A shortcut beside the main action, such as New Own Puzzles. */
  secondary?: { href: string; label: string }
}) {
  const planned = href === undefined
  return (
    <section className={styles.module} aria-labelledby={`${id}-title`} data-status={planned ? 'planned' : 'available'}>
      <p className={styles.eyebrow}>{eyebrow}</p>
      <h3 id={`${id}-title`}>{title}</h3>
      <p>{text}</p>
      {planned ? <strong>Planned</strong> : (
        <div className={styles.actions}>
          <a className={styles.primaryLink} href={href}>{action}</a>
          {secondary && <a className={styles.secondaryButton} href={secondary.href}>{secondary.label}</a>}
        </div>
      )}
    </section>
  )
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

/** The normal-Puzzle player for one route: where its puzzles come from and how it words them. */
function NormalPuzzlePlayer({
  route,
  categories,
  onSessionFailure,
}: {
  route: PlayerRoute
  categories: PuzzleCategory[] | null
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const categoryName = route.kind === 'category' || route.kind === 'mastered-practice'
    ? categories?.find((category) => category.kind === route.category && category.key === route.key)?.name
      ?? route.key.replaceAll('_', ' ')
    : null
  const title = route.kind === 'random'
    ? 'Random puzzle'
    : route.kind === 'session' ? LIST_LABELS[route.list] : categoryName ?? ''
  const themeNames = (completion: PuzzleCompletion) => completion.themes.map((key) =>
    categories?.find((category) => category.kind === 'theme' && category.key === key)?.name ?? key)

  const source: PlayerSource<PuzzleCompletion> = {
    key: playerKey(route),
    back: route.kind === 'session'
      ? { href: '#/puzzles/repetition', label: 'Repetition' }
      : route.kind === 'mastered-practice'
        ? { href: '#/puzzles/repetition/mastered', label: 'Mastered puzzles' }
        : { href: '#/puzzles', label: 'Puzzles' },
    eyebrow: route.kind === 'random'
      ? 'Random'
      : route.kind === 'category'
        ? `By Category · ${KIND_LABELS[route.category]}`
        : route.kind === 'session'
          ? 'Repetition · Practice Session'
          : `Mastered practice · ${KIND_LABELS[route.category]}`,
    title,
    description: route.kind === 'random'
      ? 'Puzzles you have not completed before, one at a time.'
      : route.kind === 'category'
        ? 'Any puzzle in this category except Mastered ones.'
        : route.kind === 'session'
          ? 'Every puzzle in this list once, in a fresh random order.'
          : 'Your Mastered puzzles in this category, in a fresh random order. Practice only: attempts never change Mastered status.',
    practice: route.kind === 'mastered-practice',
    nextLabel: route.kind === 'category' ? `Next in ${title}` : 'Next puzzle',
    start: route.kind === 'random'
      ? { kind: 'single', next: async () => normalAttempt(await startRandomPuzzle()) }
      : route.kind === 'category'
        ? { kind: 'single', next: async () => normalAttempt(await startCategoryPuzzle(route.category, route.key)) }
        : {
          kind: 'session',
          create: async () => (route.kind === 'session'
            ? await startListSession(route.list)
            : await startMasteredSession(route.category, route.key)).sessionId,
          next: async (sessionId) => {
            const next = await nextSessionPuzzle(sessionId)
            return { ...next, attempt: normalAttempt(next.attempt) }
          },
        },
    emptyText: route.kind === 'random'
      ? 'No new puzzle is available: every stored puzzle is already in one of your Training Lists, or the library is empty.'
      : route.kind === 'category'
        ? 'No puzzle is available: every puzzle in this category is Mastered.'
        : route.kind === 'session'
          ? `${LIST_LABELS[route.list]} has no puzzles right now. Puzzles you practice move to other lists as your results change.`
          : 'You have no Mastered puzzles in this category.',
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

function normalAttempt(attempt: PuzzleAttempt): PlayerAttempt {
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
  }
}

function playerKey(route: PlayerRoute) {
  if (route.kind === 'random') return 'random'
  if (route.kind === 'session') return `session/${route.list}`
  return `${route.kind}/${route.category}/${route.key}`
}

function categoryHref(category: PuzzleCategory) {
  return `#/puzzles/category/${category.kind}/${encodeURIComponent(category.key)}`
}
