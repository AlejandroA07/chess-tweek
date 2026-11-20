import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { InteractiveChessBoard } from './ChessBoard'
import styles from './PuzzleTraining.module.css'
import {
  LIST_LABELS,
  loadCategories,
  PuzzleRequestError,
  requestPuzzleHint,
  revealPuzzle,
  startCategoryPuzzle,
  startRandomPuzzle,
  submitPuzzleMove,
  type CategoryKind,
  type PuzzleAttempt,
  type PuzzleCategory,
  type PuzzleCompletion,
} from './puzzles'

type ConnectionState = 'checking' | 'disconnected' | 'connected' | 'unavailable'
type SessionFailure = 'not_connected' | 'session_unavailable'

export type PuzzleRoute =
  | { kind: 'dashboard' }
  | { kind: 'random' }
  | { kind: 'category'; category: CategoryKind; key: string }

const KIND_LABELS: Record<CategoryKind, string> = {
  theme: 'Theme',
  'opening-family': 'Opening',
  'opening-variation': 'Opening variation',
}
const OPENING_RESULTS = 20

/** The Puzzles route for a hash, or null when the hash is not a Puzzles route. */
export function puzzleRoute(hash: string): PuzzleRoute | null {
  if (hash === '#/puzzles') return { kind: 'dashboard' }
  if (hash === '#/puzzles/random') return { kind: 'random' }
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
  const [categoriesState, setCategoriesState] = useState<'loading' | 'ready' | 'failed'>('loading')

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

  if (route.kind === 'dashboard') {
    return (
      <PuzzleDashboard
        categories={categories}
        state={categoriesState}
        onRetry={() => void load()}
      />
    )
  }
  return (
    <PuzzlePlayer
      key={route.kind === 'random' ? 'random' : `${route.category}/${route.key}`}
      route={route}
      categories={categories}
      onSessionFailure={onSessionFailure}
    />
  )
}

function PuzzleDashboard({
  categories,
  state,
  onRetry,
}: {
  categories: PuzzleCategory[] | null
  state: 'loading' | 'ready' | 'failed'
  onRetry: () => void
}) {
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
        <section className={styles.module} aria-labelledby="random-title">
          <p className={styles.eyebrow}>Random</p>
          <h3 id="random-title">One new puzzle at a time</h3>
          <p>
            Random serves only puzzles you have not completed before, one at a time.
          </p>
          <a className={styles.primaryLink} href="#/puzzles/random">Start Random</a>
        </section>

        <section className={styles.module} aria-labelledby="repetition-title" data-status="planned">
          <p className={styles.eyebrow}>Repetition</p>
          <h3 id="repetition-title">Practice your Training Lists</h3>
          <p>Repeat Hard to Solve, Wrong Moves, Worth Repeating, and Retiring puzzles until they are Mastered.</p>
          <strong>Planned</strong>
        </section>

        <section className={styles.module} aria-labelledby="own-title" data-status="planned">
          <p className={styles.eyebrow}>Own Puzzles</p>
          <h3 id="own-title">Puzzles from your games</h3>
          <p>Your own inaccuracies, mistakes, and blunders from My Moves analysis.</p>
          <strong>Planned</strong>
        </section>
      </div>

      <section className={`${styles.module} ${styles.categories}`} aria-labelledby="category-title">
        <div className={styles.sectionHeading}>
          <p className={styles.eyebrow}>By Category</p>
          <h3 id="category-title">Choose a theme or opening</h3>
          <p>Includes puzzles already in a Training List. Mastered puzzles are skipped.</p>
        </div>
        {state === 'loading' && <p className={styles.muted} aria-live="polite">Loading categories…</p>}
        {state === 'failed' && (
          <div className={styles.inlineError} role="alert">
            <p>The categories could not be loaded. Your Training Lists are unchanged.</p>
            <button className={styles.secondaryButton} type="button" onClick={onRetry}>Retry</button>
          </div>
        )}
        {state === 'ready' && categories !== null && <CategoryBrowser categories={categories} />}
      </section>
    </div>
  )
}

function CategoryBrowser({ categories }: { categories: PuzzleCategory[] }) {
  const [query, setQuery] = useState('')
  const themeGroups = useMemo(() => {
    const groups = new Map<string, PuzzleCategory[]>()
    for (const category of categories) {
      if (category.kind !== 'theme') continue
      groups.set(category.group, [...(groups.get(category.group) ?? []), category])
    }
    return [...groups.entries()]
  }, [categories])
  const openings = useMemo(
    () => categories.filter((category) => category.kind !== 'theme'),
    [categories],
  )
  const normalized = query.trim().toLowerCase()
  const matches = normalized.length < 2
    ? []
    : openings.filter((opening) => opening.name.toLowerCase().includes(normalized))

  if (categories.length === 0) {
    return (
      <p className={styles.muted}>
        No categories yet. Import the Lichess Puzzle database from Admin to train by theme or opening.
      </p>
    )
  }

  return (
    <div className={styles.browser}>
      {themeGroups.map(([group, themes]) => (
        <section key={group} className={styles.themeGroup} aria-label={`${group} themes`}>
          <h4>{group}</h4>
          <ul>
            {themes.map((theme) => (
              <li key={theme.key}>
                <a href={categoryHref(theme)}>{theme.name}</a>
              </li>
            ))}
          </ul>
        </section>
      ))}
      <section className={styles.openingSearch} aria-label="Openings">
        <h4>Openings</h4>
        <label htmlFor="opening-query">Find an opening</label>
        <input
          id="opening-query"
          type="search"
          value={query}
          maxLength={80}
          placeholder="For example, Caro-Kann"
          aria-describedby="opening-query-help"
          onChange={(event) => setQuery(event.target.value)}
        />
        <p id="opening-query-help" className={styles.muted}>
          {openings.length.toLocaleString()} openings and variations. Type at least two letters.
        </p>
        {normalized.length >= 2 && (
          <p className={styles.muted} aria-live="polite">
            {matches.length === 0
              ? 'No openings match.'
              : matches.length > OPENING_RESULTS
                ? `Showing ${OPENING_RESULTS} of ${matches.length.toLocaleString()} matches.`
                : `${matches.length} ${matches.length === 1 ? 'match' : 'matches'}.`}
          </p>
        )}
        {matches.length > 0 && (
          <ul className={styles.openingResults}>
            {matches.slice(0, OPENING_RESULTS).map((opening) => (
              <li key={`${opening.kind}/${opening.key}`}>
                <a href={categoryHref(opening)}>{opening.name}</a>
                <span>{KIND_LABELS[opening.kind]}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
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
  route: Exclude<PuzzleRoute, { kind: 'dashboard' }>
  categories: PuzzleCategory[] | null
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const [phase, setPhase] = useState<Phase>({ kind: 'ready' })
  const [line, setLine] = useState<string[]>([])
  const [wrongMoves, setWrongMoves] = useState(0)
  const [hintSquare, setHintSquare] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState<{ tone: 'correct' | 'incorrect'; text: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const statusRef = useRef<HTMLDivElement>(null)
  const puzzleTitleRef = useRef<HTMLHeadingElement>(null)
  const startRef = useRef<HTMLButtonElement>(null)

  const categoryName = route.kind === 'category'
    ? categories?.find((category) => category.kind === route.category && category.key === route.key)?.name
      ?? route.key.replaceAll('_', ' ')
    : null
  const title = route.kind === 'random' ? 'Random puzzle' : categoryName ?? ''

  const start = async () => {
    setPhase({ kind: 'starting' })
    setLine([])
    setWrongMoves(0)
    setHintSquare(null)
    setFeedback(null)
    setError(null)
    try {
      // The attempt (and its Solve Time) starts here; the board is interactive as soon as it returns.
      const attempt = route.kind === 'random'
        ? await startRandomPuzzle()
        : await startCategoryPuzzle(route.category, route.key)
      setPhase({ kind: 'playing', attempt })
    } catch (requestError) {
      reportSessionFailure(requestError, onSessionFailure)
      setPhase({ kind: 'ready' })
      setError(requestError instanceof PuzzleRequestError && requestError.code === 'no_puzzle_available'
        ? route.kind === 'random'
          ? 'No new puzzle is available: every stored puzzle is already in one of your Training Lists, or the library is empty.'
          : 'No puzzle is available: every puzzle in this category is Mastered.'
        : 'A puzzle could not be started. Try again.')
    }
  }

  const failed = (requestError: unknown) => {
    reportSessionFailure(requestError, onSessionFailure)
    if (requestError instanceof PuzzleRequestError && requestError.code === 'attempt_finished') {
      setPhase({ kind: 'ready' })
      setError('This attempt has ended, for example because another puzzle was started. Start a new puzzle.')
      return
    }
    setError('That action could not be completed. Try again.')
  }

  const move = async (uci: string) => {
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

  // Keep focus on the content that replaced the control the owner just used.
  const previousPhase = useRef<Phase['kind']>(phase.kind)
  useEffect(() => {
    const before = previousPhase.current
    previousPhase.current = phase.kind
    if (before === phase.kind) return
    if (phase.kind === 'complete') statusRef.current?.focus()
    else if (phase.kind === 'playing') puzzleTitleRef.current?.focus()
    else if (phase.kind === 'ready' && before !== 'starting') startRef.current?.focus()
  }, [phase.kind])

  const attempt = phase.kind === 'playing' || phase.kind === 'complete' ? phase.attempt : null
  const side = attempt === null ? null : sideToMove(attempt.positionFen)

  return (
    <article className={styles.playerPage}>
      <a className={styles.backLink} href="#/puzzles">← Puzzles</a>
      <header className={styles.playerHeader}>
        <div>
          <p className={styles.eyebrow}>
            {route.kind === 'random' ? 'Random' : `By Category · ${KIND_LABELS[route.category]}`}
          </p>
          <h2>{title}</h2>
          <p>
            {route.kind === 'random'
              ? 'Puzzles you have not completed before, one at a time.'
              : 'Any puzzle in this category except Mastered ones.'}
          </p>
        </div>
      </header>

      {attempt === null ? (
        <section className={styles.startPanel} aria-labelledby="start-title">
          <h3 id="start-title">
            {phase.kind === 'starting' ? 'Starting a puzzle…' : 'Ready when you are'}
          </h3>
          <p>
            Solve Time starts when you press Start puzzle and the board appears, and stops when the
            full solution is played.
          </p>
          {error && <p className={styles.error} role="alert">{error}</p>}
          <button
            ref={startRef}
            className={styles.primaryButton}
            type="button"
            disabled={phase.kind === 'starting'}
            onClick={() => void start()}
          >
            {phase.kind === 'starting' ? 'Starting…' : 'Start puzzle'}
          </button>
        </section>
      ) : (
        <section className={styles.puzzleCard} aria-labelledby="puzzle-title">
          <div className={styles.board}>
            <InteractiveChessBoard
              key={attempt.attemptId}
              fen={attempt.positionFen}
              orientation={side ?? 'white'}
              label={`Puzzle ${attempt.externalId}: ${side === 'black' ? 'Black' : 'White'} to move`}
              disabled={busy || phase.kind === 'complete'}
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
                  <strong>Hint:</strong> move the piece on {hintSquare}. This puzzle goes to Hard to Solve, with its progress there restarted.
                </p>
              )}
              {phase.kind === 'complete' && (
                <CompletionSummary
                  completion={phase.completion}
                  revealed={phase.revealed}
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
                  {route.kind === 'random' ? 'Next puzzle' : `Next in ${title}`}
                </button>
              )}
            </div>
            {phase.kind === 'playing' && (
              <p className={styles.muted}>
                A hint or Show solution places this puzzle in Hard to Solve and restarts its progress there.
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
  categories,
}: {
  completion: PuzzleCompletion
  revealed: boolean
  categories: PuzzleCategory[] | null
}) {
  const list = LIST_LABELS[completion.list]
  const themeNames = completion.themes.map((key) =>
    categories?.find((category) => category.kind === 'theme' && category.key === key)?.name ?? key)
  return (
    <div className={styles.summary}>
      <p>
        <strong>{revealed ? 'Solution shown.' : 'Puzzle complete.'}</strong>{' '}
        {completion.previousList === null
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
      <a className={styles.externalLink} href={completion.gameUrl} target="_blank" rel="noreferrer">
        Open the source game on Lichess
      </a>
    </div>
  )
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
