import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { gameReviewHref } from './gameRef'
import styles from './OwnPuzzles.module.css'
import {
  discardOwnPuzzle,
  groupText,
  isGroupKey,
  isNewOrder,
  isSiteFilter,
  LABEL_TEXT,
  loadDiscarded,
  loadInList,
  loadNew,
  loadOverview,
  loadOwnListCounts,
  loadOwnMastered,
  nextOwnSessionPuzzle,
  ownTacticText,
  PAGE_SIZE,
  PHASE_TEXT,
  requestOwnHint,
  restoreOwnPuzzle,
  revealOwnPuzzle,
  SITE_TEXT,
  startNextNew,
  startOwnListSession,
  startOwnMasteredSession,
  startOwnPuzzle,
  submitOwnMove,
  type GroupCount,
  type GroupKind,
  type NewOrder,
  type OwnAttempt,
  type OwnCompletion,
  type OwnGroups,
  type OwnOverview,
  type OwnPage,
  type OwnPuzzle,
  type SiteFilter,
  type SourceGame,
} from './ownPuzzleApi'
import {
  PuzzlePlayer,
  reportSessionFailure,
  type PlayerAttempt,
  type PlayerSource,
  type SessionFailure,
} from './PuzzlePlayer'
import shared from './PuzzleTraining.module.css'
import { ACTIVE_LISTS, LIST_LABELS, PuzzleRequestError, type ActiveList, type ListCount, type TrainingList } from './puzzles'
import { formatCount, LIST_RULES, LIST_SLUGS, ListCard, useLoaded } from './puzzleViews'

export type OwnRoute =
  | { kind: 'own-dashboard' }
  | { kind: 'own-new'; site: SiteFilter; order: NewOrder }
  | { kind: 'own-discarded' }
  | { kind: 'own-list'; list: TrainingList }
  | { kind: 'own-solve'; site: SiteFilter; order: NewOrder; first: number | null }
  | { kind: 'own-repetition' }
  | { kind: 'own-session'; list: ActiveList }
  | { kind: 'own-mastered' }
  | { kind: 'own-mastered-practice'; group: GroupKind; key: string }

const LIST_SLUG_OR_MASTERED: Record<TrainingList, string> = { ...LIST_SLUGS, mastered: 'mastered' }
const ORDER_TEXT: Record<NewOrder, string> = { newest: 'Newest first', random: 'Random order' }
const GROUP_KIND_TEXT: Record<GroupKind, string> = { opening: 'Opening', phase: 'Game phase', tactic: 'Tactic' }
const MAX_PUZZLE_ID = Number.MAX_SAFE_INTEGER

/** The Own Puzzles route for a `#/puzzles/own…` hash, or null when it is not one. */
export function ownPuzzleRoute(hash: string): OwnRoute | null {
  if (hash === '#/puzzles/own') return { kind: 'own-dashboard' }
  if (hash === '#/puzzles/own/new') return { kind: 'own-new', site: 'all', order: 'newest' }
  if (hash === '#/puzzles/own/discarded') return { kind: 'own-discarded' }
  if (hash === '#/puzzles/own/repetition') return { kind: 'own-repetition' }
  if (hash === '#/puzzles/own/repetition/mastered') return { kind: 'own-mastered' }
  const newList = /^#\/puzzles\/own\/new\/([a-z]+)\/([a-z]+)$/.exec(hash)
  if (newList !== null) {
    return isSiteFilter(newList[1]) && isNewOrder(newList[2])
      ? { kind: 'own-new', site: newList[1], order: newList[2] }
      : null
  }
  const solve = /^#\/puzzles\/own\/solve\/([a-z]+)\/([a-z]+)(?:\/([1-9][0-9]{0,15}))?$/.exec(hash)
  if (solve !== null) {
    const first = solve[3] === undefined ? null : Number(solve[3])
    if (!isSiteFilter(solve[1]) || !isNewOrder(solve[2]) || (first !== null && first > MAX_PUZZLE_ID)) return null
    return { kind: 'own-solve', site: solve[1], order: solve[2], first }
  }
  const list = (Object.keys(LIST_SLUG_OR_MASTERED) as TrainingList[])
    .find((candidate) => hash === `#/puzzles/own/lists/${LIST_SLUG_OR_MASTERED[candidate]}`)
  if (list !== undefined) return { kind: 'own-list', list }
  const session = ACTIVE_LISTS.find((candidate) => hash === `#/puzzles/own/repetition/${LIST_SLUGS[candidate]}`)
  if (session !== undefined) return { kind: 'own-session', list: session }
  const mastered = /^#\/puzzles\/own\/repetition\/mastered\/(opening|phase|tactic)\/([^/]{1,600})$/.exec(hash)
  if (mastered !== null) {
    const group = mastered[1] as GroupKind
    let key: string
    try {
      key = decodeURIComponent(mastered[2])
    } catch {
      return null
    }
    return isGroupKey(group, key) ? { kind: 'own-mastered-practice', group, key } : null
  }
  return null
}

export function OwnPuzzles({
  route,
  onSessionFailure,
}: {
  route: OwnRoute
  onSessionFailure: (failure: SessionFailure) => void
}) {
  switch (route.kind) {
    case 'own-dashboard':
      return <OwnDashboard onSessionFailure={onSessionFailure} />
    case 'own-new':
      return <NewOwnPuzzles key={`new/${route.site}`} site={route.site} order={route.order} onSessionFailure={onSessionFailure} />
    case 'own-discarded':
      return <DiscardedOwnPuzzles onSessionFailure={onSessionFailure} />
    case 'own-list':
      return <OwnListPuzzles key={route.list} list={route.list} onSessionFailure={onSessionFailure} />
    case 'own-repetition':
      return <OwnRepetition onSessionFailure={onSessionFailure} />
    case 'own-mastered':
      return <OwnMastered onSessionFailure={onSessionFailure} />
    default:
      return <OwnPlayer key={ownPlayerKey(route)} route={route} onSessionFailure={onSessionFailure} />
  }
}

function OwnDashboard({ onSessionFailure }: { onSessionFailure: (failure: SessionFailure) => void }) {
  const { value: overview, state, reload } = useLoaded<OwnOverview>(loadOverview, onSessionFailure)
  const empty = overview !== null && overview.newPuzzles === 0 && overview.discarded === 0 && overview.groups.total === 0
  return (
    <div className={shared.page}>
      <a className={shared.backLink} href="#/puzzles">← Puzzles</a>
      <section className={shared.intro} aria-labelledby="own-title">
        <p className={shared.eyebrow}>Own Puzzles</p>
        <h2 id="own-title">Puzzles from your games</h2>
        <p>
          Every inaccuracy, mistake, and blunder in a My Moves analysis becomes an Own Puzzle: the
          position before your move, solved with the engine's best line. They follow the same
          Training List rules as Lichess puzzles but keep their own lists, sessions, and history.
        </p>
      </section>
      <LoadNotice state={state} what="Your Own Puzzles" onRetry={reload} />
      {state === 'ready' && overview !== null && (empty ? (
        <section className={shared.statePanel} aria-labelledby="own-empty-title">
          <h2 id="own-empty-title">No Own Puzzles yet</h2>
          <p>
            Open Games and choose Analyze My Moves on a game. When the analysis completes, each of
            your inaccuracies, mistakes, and blunders appears here as a New Own Puzzle.
          </p>
          <a className={shared.primaryLink} href="#/games">Open Games</a>
        </section>
      ) : (
        <>
          <div className={shared.modules}>
            <section className={shared.module} aria-labelledby="own-new-title">
              <p className={shared.eyebrow}>New</p>
              <h3 id="own-new-title">
                New Own Puzzles <span className={shared.count}>{formatCount(overview.newPuzzles)}</span>
              </h3>
              <p>
                {formatCount(overview.newBySource.lichess)} from Lichess and{' '}
                {formatCount(overview.newBySource.chesscom)} from chess.com. Solve or discard each one;
                your first completed attempt places it in a Training List.
              </p>
              {overview.newPuzzles === 0
                ? <p className={shared.muted}>No New Own Puzzles right now.</p>
                : <a className={shared.primaryLink} href="#/puzzles/own/new">Browse New puzzles</a>}
            </section>
            <section className={shared.module} aria-labelledby="own-repetition-title">
              <p className={shared.eyebrow}>Repetition</p>
              <h3 id="own-repetition-title">Practice your Own Puzzles</h3>
              <p>Practice Sessions over each Training List, kept apart from normal puzzles.</p>
              {overview.lists.every((entry) => entry.puzzles === 0)
                ? <p className={shared.muted}>No Own Puzzles in a Training List yet. Solve a New one to place it.</p>
                : <a className={shared.primaryLink} href="#/puzzles/own/repetition">Open Own Repetition</a>}
            </section>
            <section className={shared.module} aria-labelledby="own-discarded-title">
              <p className={shared.eyebrow}>Discarded</p>
              <h3 id="own-discarded-title">
                Discarded <span className={shared.count}>{formatCount(overview.discarded)}</span>
              </h3>
              <p>Discarded puzzles never enter a Training List. Restore returns one to New.</p>
              {overview.discarded === 0
                ? <p className={shared.muted}>Nothing discarded.</p>
                : <a className={shared.primaryLink} href="#/puzzles/own/discarded">View Discarded</a>}
            </section>
          </div>
          <section className={styles.panel} aria-labelledby="own-lists-title">
            <h3 id="own-lists-title">Training Lists</h3>
            <ul className={styles.listCounts}>
              {overview.lists.map((entry) => (
                <li key={entry.list}>
                  <span>{LIST_LABELS[entry.list]}</span>{' '}
                  <span className={shared.count}>{formatCount(entry.puzzles)}</span>
                  {entry.puzzles > 0 && (
                    <a
                      href={`#/puzzles/own/lists/${LIST_SLUG_OR_MASTERED[entry.list]}`}
                      aria-label={`View ${LIST_LABELS[entry.list]}`}
                    >
                      View
                    </a>
                  )}
                </li>
              ))}
            </ul>
          </section>
          <GroupSummary
            id="own-groups"
            title="Your Own Puzzles by category"
            text="Every Own Puzzle you have not discarded, including New ones. A puzzle with several tactics counts under each."
            groups={overview.groups}
          />
        </>
      ))}
    </div>
  )
}

/** Counts by opening family, game phase, and tactic; {@code href} makes each entry a link. */
function GroupSummary({
  id,
  title,
  text,
  groups,
  href,
}: {
  id: string
  title: string
  text: string
  groups: OwnGroups
  href?: (kind: GroupKind, key: string) => string
}) {
  const sections: { kind: GroupKind; title: string; counts: GroupCount[] }[] = [
    { kind: 'opening', title: 'By opening', counts: groups.openings },
    { kind: 'phase', title: 'By game phase', counts: groups.phases },
    { kind: 'tactic', title: 'By tactic', counts: groups.tactics },
  ]
  return (
    <section className={styles.panel} aria-labelledby={`${id}-title`}>
      <h3 id={`${id}-title`}>{title}</h3>
      <p className={shared.muted}>{text}</p>
      <div className={styles.groups}>
        {sections.map((section) => (
          <section key={section.kind} aria-labelledby={`${id}-${section.kind}`}>
            <h4 id={`${id}-${section.kind}`}>{section.title}</h4>
            {section.counts.length === 0 ? (
              <p className={shared.muted}>
                {section.kind === 'tactic' ? 'No proven tactic labels yet.' : 'None yet.'}
              </p>
            ) : (
              <ul className={styles.groupList}>
                {section.counts.map((count) => {
                  const name = groupText(section.kind, count.key)
                  return (
                    <li key={count.key}>
                      {href === undefined
                        ? <span className={styles.groupName}>{name}</span>
                        : <a className={styles.groupName} href={href(section.kind, count.key)}>{name}</a>}
                      <span className={shared.count}>{formatCount(count.puzzles)}</span>
                    </li>
                  )
                })}
              </ul>
            )}
          </section>
        ))}
      </div>
    </section>
  )
}

function LoadNotice({ state, what, onRetry }: { state: string; what: string; onRetry: () => void }) {
  if (state === 'loading') return <p className={shared.muted} aria-live="polite">Loading…</p>
  if (state !== 'failed') return null
  return (
    <div className={shared.inlineError} role="alert">
      <p>{what} could not be loaded. Nothing has changed.</p>
      <button className={shared.secondaryButton} type="button" onClick={onRetry}>Retry</button>
    </div>
  )
}

/** One page of Own Puzzles, with previous and next paging and a status line for changes. */
function usePaged(
  load: (offset: number, signal: AbortSignal) => Promise<OwnPage>,
  onSessionFailure: (failure: SessionFailure) => void,
) {
  const [offset, setOffset] = useState(0)
  const [page, setPage] = useState<OwnPage | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [tick, setTick] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setState('loading')
    load(offset, controller.signal).then((loaded) => {
      // A change can empty the last page; step back to the page that now holds the end.
      if (loaded.puzzles.length === 0 && offset > 0 && loaded.total > 0) {
        setOffset(Math.max(0, Math.floor((loaded.total - 1) / PAGE_SIZE) * PAGE_SIZE))
        return
      }
      setPage(loaded)
      setState('ready')
    }, (error: unknown) => {
      if (error instanceof DOMException && error.name === 'AbortError') return
      reportSessionFailure(error, onSessionFailure)
      setState('failed')
    })
    return () => controller.abort()
  }, [load, offset, tick, onSessionFailure])
  return {
    page,
    state,
    offset,
    setOffset,
    reload: useCallback(() => setTick((current) => current + 1), []),
  }
}

function Pager({
  offset,
  total,
  onChange,
}: {
  offset: number
  total: number
  onChange: (offset: number) => void
}) {
  if (total <= PAGE_SIZE) return null
  const last = Math.min(offset + PAGE_SIZE, total)
  return (
    <nav className={styles.pager} aria-label="Pages">
      <button
        className={shared.secondaryButton}
        type="button"
        disabled={offset === 0}
        onClick={() => onChange(Math.max(0, offset - PAGE_SIZE))}
      >
        Previous
      </button>
      <span className={styles.pageText}>
        {formatCount(offset + 1)}–{formatCount(last)} of {formatCount(total)}
      </span>
      <button
        className={shared.secondaryButton}
        type="button"
        disabled={last >= total}
        onClick={() => onChange(offset + PAGE_SIZE)}
      >
        Next
      </button>
    </nav>
  )
}

function NewOwnPuzzles({
  site,
  order,
  onSessionFailure,
}: {
  site: SiteFilter
  order: NewOrder
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const { value: overview, reload: reloadOverview } = useLoaded<OwnOverview>(loadOverview, onSessionFailure)
  const load = useCallback((offset: number, signal: AbortSignal) => loadNew(site, offset, signal), [site])
  const { page, state, offset, setOffset, reload } = usePaged(load, onSessionFailure)
  const [status, setStatus] = useState<string | null>(null)
  // A new site filter remounts this view (see its key), so it starts at the first page.
  const headingRef = useRef<HTMLHeadingElement>(null)

  const choose = (nextSite: SiteFilter, nextOrder: NewOrder) => {
    setStatus(null)
    window.location.replace(`#/puzzles/own/new/${nextSite}/${nextOrder}`)
  }
  const changed = (text: string) => {
    setStatus(text)
    reload()
    reloadOverview()
    headingRef.current?.focus()
  }
  const count = (value: SiteFilter) => overview === null
    ? null
    : value === 'all' ? overview.newPuzzles : overview.newBySource[value]

  return (
    <div className={shared.page}>
      <a className={shared.backLink} href="#/puzzles/own">← Own Puzzles</a>
      <section className={shared.intro} aria-labelledby="own-new-page-title">
        <p className={shared.eyebrow}>Own Puzzles · New</p>
        <h2 id="own-new-page-title" ref={headingRef} tabIndex={-1}>New Own Puzzles</h2>
        <p>
          Puzzles from your games that you have not solved yet, newest game first. Solve them in
          order or at random, or discard the ones you do not want to train.
        </p>
      </section>
      <section className={styles.panel} aria-label="Choose New puzzles">
        <div className={styles.controls}>
          <div className={shared.field}>
            <label htmlFor="own-site">Games from</label>
            <select
              id="own-site"
              value={site}
              onChange={(event) => choose(event.target.value as SiteFilter, order)}
            >
              {(['all', 'lichess', 'chesscom'] as SiteFilter[]).map((value) => {
                const total = count(value)
                return (
                  <option key={value} value={value}>
                    {value === 'all' ? 'All sites' : SITE_TEXT[value]}
                    {total === null ? '' : ` (${formatCount(total)})`}
                  </option>
                )
              })}
            </select>
          </div>
          <div className={shared.field}>
            <label htmlFor="own-order">Solve in</label>
            <select
              id="own-order"
              value={order}
              onChange={(event) => choose(site, event.target.value as NewOrder)}
            >
              <option value="newest">{ORDER_TEXT.newest}</option>
              <option value="random">{ORDER_TEXT.random}</option>
            </select>
          </div>
          {page !== null && page.total > 0 && (
            <a className={shared.primaryLink} href={`#/puzzles/own/solve/${site}/${order}`}>
              Solve New puzzles
            </a>
          )}
        </div>
      </section>
      {status && <p className={styles.status} role="status">{status}</p>}
      <LoadNotice state={state} what="New Own Puzzles" onRetry={reload} />
      {state === 'ready' && page !== null && (page.total === 0 ? (
        <p className={shared.muted}>
          {site === 'all'
            ? 'No New Own Puzzles. Analyze My Moves of more games to create them, or restore one from Discarded.'
            : `No New Own Puzzles from ${SITE_TEXT[site]} games. Choose All sites to see the others.`}
        </p>
      ) : (
        <>
          <p className={shared.muted} aria-live="polite">
            {formatCount(page.total)} New {page.total === 1 ? 'puzzle' : 'puzzles'}
            {site === 'all' ? '' : ` from ${SITE_TEXT[site]}`}.
          </p>
          <ul className={styles.rows}>
            {page.puzzles.map((puzzle) => (
              <OwnPuzzleRow
                key={puzzle.id}
                puzzle={puzzle}
                actions={(
                  <NewRowActions
                    puzzle={puzzle}
                    solveHref={`#/puzzles/own/solve/${site}/${order}/${puzzle.id}`}
                    onDiscarded={() => changed(`${describe(puzzle)} discarded. Restore it from Discarded at any time.`)}
                    onSessionFailure={onSessionFailure}
                  />
                )}
              />
            ))}
          </ul>
          <Pager offset={offset} total={page.total} onChange={setOffset} />
        </>
      ))}
    </div>
  )
}

function NewRowActions({
  puzzle,
  solveHref,
  onDiscarded,
  onSessionFailure,
}: {
  puzzle: OwnPuzzle
  solveHref: string
  onDiscarded: () => void
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const keepRef = useRef<HTMLButtonElement>(null)
  const discardRef = useRef<HTMLButtonElement>(null)
  const wasConfirming = useRef(false)

  useEffect(() => {
    if (confirming) keepRef.current?.focus()
    else if (wasConfirming.current) discardRef.current?.focus()
    wasConfirming.current = confirming
  }, [confirming])

  const discard = async () => {
    setBusy(true)
    setError(null)
    try {
      await discardOwnPuzzle(puzzle.id)
      setConfirming(false)
      onDiscarded()
    } catch (requestError) {
      reportSessionFailure(requestError, onSessionFailure)
      setConfirming(false)
      setError(requestError instanceof PuzzleRequestError && requestError.code === 'own_puzzle_classified'
        ? 'This puzzle is already in a Training List, so it can no longer be discarded.'
        : 'The puzzle could not be discarded. Nothing has changed; try again.')
    } finally {
      setBusy(false)
    }
  }

  if (confirming) {
    return (
      <div className={shared.confirm} role="group" aria-labelledby={`discard-${puzzle.id}`}>
        <p id={`discard-${puzzle.id}`}>
          <strong>Discard {describe(puzzle)}?</strong> It leaves New Own Puzzles and never enters a
          Training List. You can restore it from Discarded.
        </p>
        <div className={shared.actions}>
          <button
            ref={keepRef}
            className={shared.secondaryButton}
            type="button"
            disabled={busy}
            onClick={() => setConfirming(false)}
          >
            Keep it
          </button>
          <button className={shared.dangerButton} type="button" disabled={busy} onClick={() => void discard()}>
            Discard puzzle
          </button>
        </div>
      </div>
    )
  }
  return (
    <>
      <div className={shared.actions}>
        <a className={shared.primaryLink} href={solveHref} aria-label={`Solve ${describe(puzzle)}`}>Solve</a>
        <button
          ref={discardRef}
          className={shared.textButton}
          type="button"
          aria-label={`Discard ${describe(puzzle)}`}
          onClick={() => setConfirming(true)}
        >
          Discard
        </button>
      </div>
      {error && <p className={shared.error} role="alert">{error}</p>}
    </>
  )
}

function DiscardedOwnPuzzles({ onSessionFailure }: { onSessionFailure: (failure: SessionFailure) => void }) {
  const { page, state, offset, setOffset, reload } = usePaged(loadDiscarded, onSessionFailure)
  const [status, setStatus] = useState<string | null>(null)
  const [failure, setFailure] = useState<{ id: number; text: string } | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)

  const restore = async (puzzle: OwnPuzzle) => {
    setBusy(puzzle.id)
    setFailure(null)
    try {
      await restoreOwnPuzzle(puzzle.id)
      setStatus(`${describe(puzzle)} restored to New Own Puzzles.`)
      reload()
      headingRef.current?.focus()
    } catch (requestError) {
      reportSessionFailure(requestError, onSessionFailure)
      setFailure({ id: puzzle.id, text: 'The puzzle could not be restored. Nothing has changed; try again.' })
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className={shared.page}>
      <a className={shared.backLink} href="#/puzzles/own">← Own Puzzles</a>
      <section className={shared.intro} aria-labelledby="own-discarded-page-title">
        <p className={shared.eyebrow}>Own Puzzles · Discarded</p>
        <h2 id="own-discarded-page-title" ref={headingRef} tabIndex={-1}>Discarded Own Puzzles</h2>
        <p>
          These puzzles are kept but never offered or placed in a Training List. Restore returns a
          puzzle to New Own Puzzles with no progress.
        </p>
      </section>
      {status && <p className={styles.status} role="status">{status}</p>}
      <LoadNotice state={state} what="Discarded Own Puzzles" onRetry={reload} />
      {state === 'ready' && page !== null && (page.total === 0 ? (
        <p className={shared.muted}>Nothing discarded. Discard a New Own Puzzle to keep it out of training.</p>
      ) : (
        <>
          <ul className={styles.rows}>
            {page.puzzles.map((puzzle) => (
              <OwnPuzzleRow
                key={puzzle.id}
                puzzle={puzzle}
                actions={(
                  <>
                    <div className={shared.actions}>
                      <button
                        className={shared.secondaryButton}
                        type="button"
                        disabled={busy !== null}
                        aria-label={`Restore ${describe(puzzle)}`}
                        onClick={() => void restore(puzzle)}
                      >
                        Restore
                      </button>
                    </div>
                    {failure?.id === puzzle.id && <p className={shared.error} role="alert">{failure.text}</p>}
                  </>
                )}
              />
            ))}
          </ul>
          <Pager offset={offset} total={page.total} onChange={setOffset} />
        </>
      ))}
    </div>
  )
}

function OwnListPuzzles({
  list,
  onSessionFailure,
}: {
  list: TrainingList
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const load = useCallback((offset: number, signal: AbortSignal) => loadInList(list, offset, signal), [list])
  const { page, state, offset, setOffset, reload } = usePaged(load, onSessionFailure)
  return (
    <div className={shared.page}>
      <a className={shared.backLink} href="#/puzzles/own">← Own Puzzles</a>
      <section className={shared.intro} aria-labelledby="own-list-title">
        <p className={shared.eyebrow}>Own Puzzles · Training List</p>
        <h2 id="own-list-title">{LIST_LABELS[list]}</h2>
        <p>
          {list === 'mastered'
            ? 'Own Puzzles that no longer need repetition. Practice them by group from Own Repetition.'
            : LIST_RULES[list as ActiveList]}
        </p>
        <a
          className={shared.primaryLink}
          href={list === 'mastered'
            ? '#/puzzles/own/repetition/mastered'
            : `#/puzzles/own/repetition/${LIST_SLUGS[list as ActiveList]}`}
        >
          {list === 'mastered' ? 'Browse Mastered groups' : `Practice ${LIST_LABELS[list]}`}
        </a>
      </section>
      <LoadNotice state={state} what="This Training List" onRetry={reload} />
      {state === 'ready' && page !== null && (page.total === 0 ? (
        <p className={shared.muted}>No Own Puzzles in this list.</p>
      ) : (
        <>
          <ul className={styles.rows}>
            {page.puzzles.map((puzzle) => <OwnPuzzleRow key={puzzle.id} puzzle={puzzle} actions={null} />)}
          </ul>
          <Pager offset={offset} total={page.total} onChange={setOffset} />
        </>
      ))}
    </div>
  )
}

/** One Own Puzzle: its severity and phase, opening, tactics, source game, and actions. */
function OwnPuzzleRow({ puzzle, actions }: { puzzle: OwnPuzzle; actions: ReactNode }) {
  const titleId = `own-puzzle-${puzzle.id}`
  return (
    <li className={styles.row} aria-labelledby={titleId}>
      <div className={styles.rowText}>
        <h3 id={titleId} className={styles.rowTitle}>
          <span className={styles.label} data-label={puzzle.label}>{LABEL_TEXT[puzzle.label]}</span>
          {' · '}{PHASE_TEXT[puzzle.phase]}
        </h3>
        <p className={styles.opening}>{puzzle.openingName ?? 'Unknown opening'}</p>
        {puzzle.tactics.length > 0 && (
          <p className={shared.muted}>{puzzle.tactics.map(ownTacticText).join(', ')}</p>
        )}
        <p className={shared.muted}>
          {SITE_TEXT[puzzle.sourceGame.source]} game · {formatDate(puzzle.gamePlayedAt)} · {moveText(puzzle.sourceGame.ply)}
        </p>
        <a
          className={styles.sourceLink}
          href={gameReviewHref(puzzle.sourceGame, puzzle.sourceGame.ply)}
          aria-label={`Open source game for ${describe(puzzle)}`}
        >
          Open source game
        </a>
      </div>
      {actions !== null && <div className={styles.rowActions}>{actions}</div>}
    </li>
  )
}

function OwnRepetition({ onSessionFailure }: { onSessionFailure: (failure: SessionFailure) => void }) {
  const { value: counts, state, reload } = useLoaded<ListCount[]>(loadOwnListCounts, onSessionFailure)
  const count = (list: TrainingList) => counts?.find((entry) => entry.list === list)?.puzzles ?? 0
  return (
    <div className={shared.page}>
      <a className={shared.backLink} href="#/puzzles/own">← Own Puzzles</a>
      <section className={shared.intro} aria-labelledby="own-repetition-page-title">
        <p className={shared.eyebrow}>Repetition · Own puzzles</p>
        <h2 id="own-repetition-page-title">Practice your Own Puzzles</h2>
        <p>
          Choose a list to start a Practice Session. Each session shows every Own Puzzle in the list
          once, in a fresh random order, before any puzzle repeats. These lists are separate from
          your normal-puzzle lists.
        </p>
      </section>
      <LoadNotice state={state} what="Your Own Puzzle Training Lists" onRetry={reload} />
      {state === 'ready' && counts !== null && (
        <div className={shared.modules}>
          {ACTIVE_LISTS.map((list) => (
            <ListCard
              key={list}
              id={`own-list-${LIST_SLUGS[list]}`}
              title={LIST_LABELS[list]}
              puzzles={count(list)}
              text={LIST_RULES[list]}
              href={`#/puzzles/own/repetition/${LIST_SLUGS[list]}`}
              action={`Practice ${LIST_LABELS[list]}`}
              empty="No Own Puzzles in this list yet."
            />
          ))}
          <ListCard
            id="own-list-mastered"
            title="Mastered"
            puzzles={count('mastered')}
            text="Own Puzzles that no longer need repetition, grouped by opening, game phase, and tactic. Practicing them never changes their status."
            href="#/puzzles/own/repetition/mastered"
            action="Browse Mastered"
            empty="No Mastered Own Puzzles yet."
          />
        </div>
      )}
    </div>
  )
}

function OwnMastered({ onSessionFailure }: { onSessionFailure: (failure: SessionFailure) => void }) {
  const { value: groups, state, reload } = useLoaded<OwnGroups>(loadOwnMastered, onSessionFailure)
  return (
    <div className={shared.page}>
      <a className={shared.backLink} href="#/puzzles/own/repetition">← Own Repetition</a>
      <section className={shared.intro} aria-labelledby="own-mastered-title">
        <p className={shared.eyebrow}>Repetition · Own puzzles</p>
        <h2 id="own-mastered-title">Mastered Own Puzzles</h2>
        <p>
          Choose an opening, game phase, or tactic to practice its Mastered Own Puzzles. Practice is
          optional: it never changes Mastered status or starts repetition progress.
        </p>
      </section>
      <LoadNotice state={state} what="Mastered Own Puzzles" onRetry={reload} />
      {state === 'ready' && groups !== null && (groups.total === 0 ? (
        <p className={shared.muted}>
          No Mastered Own Puzzles yet. A puzzle is Mastered after five fast, clean solutions in Retiring.
        </p>
      ) : (
        <GroupSummary
          id="own-mastered-groups"
          title={`${formatCount(groups.total)} Mastered ${groups.total === 1 ? 'puzzle' : 'puzzles'}`}
          text="A puzzle with several tactics appears under each."
          groups={groups}
          href={(kind, key) => `#/puzzles/own/repetition/mastered/${kind}/${encodeURIComponent(key)}`}
        />
      ))}
    </div>
  )
}

type PlayerRoute = Extract<OwnRoute, { kind: 'own-solve' | 'own-session' | 'own-mastered-practice' }>

function OwnPlayer({
  route,
  onSessionFailure,
}: {
  route: PlayerRoute
  onSessionFailure: (failure: SessionFailure) => void
}) {
  // A chosen puzzle opens first; Next puzzle then continues with New in the chosen order.
  const first = useRef(route.kind === 'own-solve' ? route.first : null)
  const backToNew = route.kind === 'own-solve' ? `#/puzzles/own/new/${route.site}/${route.order}` : ''
  const siteText = route.kind === 'own-solve' && route.site !== 'all' ? ` from ${SITE_TEXT[route.site]}` : ''

  const source: PlayerSource<OwnCompletion> = {
    key: ownPlayerKey(route),
    back: route.kind === 'own-solve'
      ? { href: backToNew, label: 'New Own Puzzles' }
      : route.kind === 'own-session'
        ? { href: '#/puzzles/own/repetition', label: 'Own Repetition' }
        : { href: '#/puzzles/own/repetition/mastered', label: 'Mastered Own Puzzles' },
    eyebrow: route.kind === 'own-solve'
      ? `Own Puzzles · New · ${ORDER_TEXT[route.order]}${route.site === 'all' ? '' : ` · ${SITE_TEXT[route.site]}`}`
      : route.kind === 'own-session'
        ? 'Own Repetition · Practice Session'
        : `Mastered practice · ${GROUP_KIND_TEXT[route.group]}`,
    title: route.kind === 'own-solve'
      ? 'New Own Puzzles'
      : route.kind === 'own-session' ? LIST_LABELS[route.list] : groupText(route.group, route.key),
    description: route.kind === 'own-solve'
      ? 'The position before your move in one of your games. Find the engine\'s best line; your first completed attempt places the puzzle in a Training List.'
      : route.kind === 'own-session'
        ? 'Every Own Puzzle in this list once, in a fresh random order.'
        : 'Your Mastered Own Puzzles in this group, in a fresh random order. Practice only: attempts never change Mastered status.',
    practice: route.kind === 'own-mastered-practice',
    nextLabel: 'Next puzzle',
    start: route.kind === 'own-solve'
      ? {
        kind: 'single',
        next: async () => {
          const chosen = first.current
          first.current = null
          return ownAttempt(chosen === null ? await startNextNew(route.site, route.order) : await startOwnPuzzle(chosen))
        },
      }
      : {
        kind: 'session',
        create: async () => (route.kind === 'own-session'
          ? await startOwnListSession(route.list)
          : await startOwnMasteredSession(route.group, route.key)).sessionId,
        next: async (sessionId) => {
          const next = await nextOwnSessionPuzzle(sessionId)
          return { ...next, attempt: ownAttempt(next.attempt) }
        },
      },
    emptyText: route.kind === 'own-solve'
      ? `No New Own Puzzles${siteText} right now. Analyze My Moves of more games to create them, or restore one from Discarded.`
      : route.kind === 'own-session'
        ? `${LIST_LABELS[route.list]} has no Own Puzzles right now. Puzzles you practice move to other lists as your results change.`
        : 'You have no Mastered Own Puzzles in this group.',
    unavailableText: 'That puzzle is no longer New: it was solved or discarded. Try another puzzle to continue with New Own Puzzles.',
    move: submitOwnMove,
    hint: requestOwnHint,
    reveal: revealOwnPuzzle,
    discard: route.kind === 'own-solve' ? (attempt) => discardOwnPuzzle(attempt.puzzleId) : undefined,
    discardText: 'It leaves New Own Puzzles and never enters a Training List, and the next New puzzle opens. You can restore it from Discarded.',
    completionExtra: (completion) => (
      <>
        <p className={shared.muted}>
          The board stays open: keep moving pieces to explore. These moves are not checked or saved.
        </p>
        <a className={shared.externalLink} href={gameReviewHref(completion.sourceGame, completion.sourceGame.ply)}>
          Open the source game at {lowerFirst(moveText(completion.sourceGame.ply))}
        </a>
      </>
    ),
  }
  return <PuzzlePlayer source={source} onSessionFailure={onSessionFailure} />
}

function ownAttempt(attempt: OwnAttempt): PlayerAttempt {
  const { puzzle } = attempt
  return {
    attemptId: attempt.attemptId,
    puzzleId: puzzle.id,
    positionFen: attempt.positionFen,
    lastMove: attempt.previousMove,
    ownerMoves: attempt.ownerMoves,
    fastSolveLimitMillis: attempt.fastSolveLimitMillis,
    currentList: attempt.currentList,
    name: `Own Puzzle, ${LABEL_TEXT[puzzle.label]} in the ${PHASE_TEXT[puzzle.phase].toLowerCase()}`,
    title: describe(puzzle),
    detail: `${LABEL_TEXT[puzzle.label]} · ${PHASE_TEXT[puzzle.phase]} · ${puzzle.openingFamily}`,
    discardable: attempt.currentList === null,
  }
}

function ownPlayerKey(route: PlayerRoute) {
  if (route.kind === 'own-solve') return `own-solve/${route.site}/${route.order}/${route.first ?? ''}`
  if (route.kind === 'own-session') return `own-session/${route.list}`
  return `own-mastered/${route.group}/${route.key}`
}

/** Names one puzzle in actions and messages, for example "Blunder at move 12 (Black), Nov 20, 2026". */
function describe(puzzle: OwnPuzzle) {
  return `${LABEL_TEXT[puzzle.label]} at ${lowerFirst(moveText(puzzle.sourceGame.ply))}, ${formatDate(puzzle.gamePlayedAt)}`
}

/** The move of a ply in the game, for example "Move 12 (Black)". */
export function moveText(ply: SourceGame['ply']) {
  return `Move ${Math.ceil(ply / 2)} (${ply % 2 === 1 ? 'White' : 'Black'})`
}

function lowerFirst(value: string) {
  return value.length === 0 ? value : value[0].toLowerCase() + value.slice(1)
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })
}
