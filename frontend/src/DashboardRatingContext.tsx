import { useEffect, useState } from 'react'
import styles from './DashboardRatingContext.module.css'
import {
  HISTORY_SPEEDS,
  RatingContextRequestError,
  requestRatingContext,
  type HistorySpeed,
  type RatingContext,
  type SpeedRating,
} from './ratingContext'

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; context: RatingContext }
  | { kind: 'error' }

const speedLabels: Record<HistorySpeed, string> = {
  rapid: 'Rapid',
  blitz: 'Blitz',
  bullet: 'Bullet',
  classical: 'Classical',
}

export function DashboardRatingContext({
  currentRatings,
  onSessionFailure,
}: {
  currentRatings: Record<HistorySpeed, number | null>
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setState({ kind: 'loading' })
    requestRatingContext(controller.signal)
      .then((context) => setState({ kind: 'ready', context }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (
          error instanceof RatingContextRequestError &&
          (error.failure === 'not_connected' || error.failure === 'session_unavailable')
        ) {
          onSessionFailure(error.failure)
          return
        }
        setState({ kind: 'error' })
      })
    return () => controller.abort()
  }, [attempt, onSessionFailure])

  const context = state.kind === 'ready' ? state.context : null

  return (
    <section className={styles.panel} aria-labelledby="rating-context-title">
      <div className={styles.heading}>
        <div>
          <p className={styles.eyebrow}>Lichess ratings</p>
          <h2 id="rating-context-title">Lichess ratings</h2>
          <p>
            Current ratings come from Lichess. History and recent changes come from games stored on this Mac.
          </p>
        </div>
        <a className={styles.link} href="#/games">Open Games</a>
      </div>

      {state.kind === 'error' && (
        <div className={styles.failure} role="alert">
          <h3>Rating context could not be loaded</h3>
          <p>Your stored games are safe. Check the local backend and try again.</p>
          <button
            className={styles.secondaryButton}
            type="button"
            onClick={() => setAttempt((value) => value + 1)}
          >
            Retry
          </button>
        </div>
      )}

      <div className={styles.grid} aria-busy={state.kind === 'loading'}>
        {HISTORY_SPEEDS.map((speed, index) => (
          <RatingCard
            key={speed}
            speed={speed}
            current={currentRatings[speed]}
            history={context?.ratings[index] ?? null}
            loading={state.kind === 'loading'}
          />
        ))}
      </div>
    </section>
  )
}

const CHESSCOM_SPEEDS: HistorySpeed[] = ['rapid', 'blitz', 'bullet']

/**
 * Chess.com ratings built only from stored chess.com games: the latest stored rating per speed and
 * its history. Chess.com games carry no per-game rating change and no bot record.
 */
export function DashboardChessComRatings({
  onSessionFailure,
}: {
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setState({ kind: 'loading' })
    requestRatingContext(controller.signal, 'chesscom')
      .then((context) => setState({ kind: 'ready', context }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (
          error instanceof RatingContextRequestError &&
          (error.failure === 'not_connected' || error.failure === 'session_unavailable')
        ) {
          onSessionFailure(error.failure)
          return
        }
        setState({ kind: 'error' })
      })
    return () => controller.abort()
  }, [attempt, onSessionFailure])

  const context = state.kind === 'ready' ? state.context : null
  const historyFor = (speed: HistorySpeed) =>
    context?.ratings.find((rating) => rating.speed === speed) ?? null
  const empty = context !== null && CHESSCOM_SPEEDS.every((speed) => (historyFor(speed)?.gamesCounted ?? 0) === 0)

  return (
    <section className={styles.panel} aria-labelledby="chesscom-ratings-title">
      <div className={styles.heading}>
        <div>
          <p className={styles.eyebrow}>From stored games</p>
          <h2 id="chesscom-ratings-title">Chess.com ratings</h2>
          <p>
            Built only from chess.com games stored on this Mac: each rating is the latest one in your
            stored games, so it can be older than your rating on chess.com. Daily games are stored
            and can be analyzed, but their rating is not shown here.
          </p>
        </div>
        <a className={styles.link} href="#/games">Open Games</a>
      </div>

      {state.kind === 'error' && (
        <div className={styles.failure} role="alert">
          <h3>Chess.com ratings could not be loaded</h3>
          <p>Your stored games are safe. Check the local backend and try again.</p>
          <button
            className={styles.secondaryButton}
            type="button"
            onClick={() => setAttempt((value) => value + 1)}
          >
            Retry
          </button>
        </div>
      )}

      {state.kind === 'loading' && (
        <p className={styles.note} aria-live="polite">Loading stored chess.com ratings…</p>
      )}

      {context === null ? null : empty ? (
        <p className={styles.note}>
          No rated chess.com rapid, blitz, or bullet games are stored yet. Save your chess.com
          username on Admin, then use Get Chess.com Games on Games.
        </p>
      ) : (
        <div className={styles.grid}>
          {CHESSCOM_SPEEDS.map((speed) => {
            const history = historyFor(speed)
            const latest = history !== null && history.points.length > 0
              ? history.points[history.points.length - 1].rating
              : null
            return (
              <RatingCard
                key={speed}
                site="chesscom"
                speed={speed}
                current={latest}
                history={history}
                loading={false}
              />
            )
          })}
        </div>
      )}
    </section>
  )
}

function RatingCard({
  speed,
  current,
  history,
  loading,
  site = 'lichess',
}: {
  speed: HistorySpeed
  current: number | null
  history: SpeedRating | null
  loading: boolean
  site?: 'lichess' | 'chesscom'
}) {
  const label = speedLabels[speed]
  const titleId = site === 'lichess' ? `rating-${speed}` : `rating-${site}-${speed}`
  return (
    <article className={styles.card} aria-labelledby={titleId}>
      <h3 id={titleId} className={styles.cardTitle}>{label} rating</h3>
      <p className={styles.rating}>{formatRating(current)}</p>
      {site === 'chesscom' && current !== null && (
        <p className={styles.note}>Latest rating in your stored games</p>
      )}
      {history === null ? (
        <p className={styles.note}>{loading ? 'Loading stored history…' : 'Stored history unavailable.'}</p>
      ) : (
        <StoredHistory label={label} rating={history} />
      )}
    </article>
  )
}

function StoredHistory({ label, rating }: { label: string; rating: SpeedRating }) {
  return (
    <>
      {rating.gamesCounted > 0 && (
        <p className={styles.change}>{formatLatestChange(rating.latestChange)}</p>
      )}
      {rating.points.length >= 2 ? (
        <Sparkline rating={rating} />
      ) : rating.gamesCounted > 0 ? (
        <p className={styles.note}>A history line needs at least two stored rated games.</p>
      ) : null}
      <p className={styles.note}>
        {rating.gamesCounted === 0
          ? `No rated ${label} games stored yet.`
          : `${formatNumber(rating.gamesCounted)} rated game${rating.gamesCounted === 1 ? '' : 's'} stored`}
      </p>
    </>
  )
}

function Sparkline({ rating }: { rating: SpeedRating }) {
  const values = rating.points.map((point) => point.rating)
  const lowest = Math.min(...values)
  const highest = Math.max(...values)
  const width = 120
  const height = 36
  const padding = 3
  const span = highest - lowest
  const coordinates = values.map((value, index) => {
    const x = (index / (values.length - 1)) * width
    const y = span === 0
      ? height / 2
      : padding + (1 - (value - lowest) / span) * (height - 2 * padding)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  return (
    <figure className={styles.history}>
      <svg
        className={styles.sparkline}
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        <polyline points={coordinates.join(' ')} />
      </svg>
      <figcaption>
        <span>
          {formatNumber(values[0])} to {formatNumber(values[values.length - 1])} over the last{' '}
          {values.length} stored rated games
        </span>
        <span>Low {formatNumber(lowest)} · High {formatNumber(highest)}</span>
      </figcaption>
    </figure>
  )
}

function formatLatestChange(value: number | null) {
  if (value === null) return 'Last game: no change reported'
  if (value === 0) return 'Last game ±0'
  return value > 0 ? `Last game +${value}` : `Last game −${Math.abs(value)}`
}

function formatRating(value: number | null) {
  return value === null ? 'Not rated' : formatNumber(value)
}

function formatNumber(value: number) {
  return value.toLocaleString('en-US')
}
