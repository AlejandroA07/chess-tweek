import { useEffect, useState } from 'react'
import styles from './DashboardRatingContext.module.css'
import {
  HISTORY_SPEEDS,
  RatingContextRequestError,
  requestRatingContext,
  type HistorySpeed,
  type RatingContext,
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
}: {
  currentRatings: Record<HistorySpeed, number | null>
}) {
  return (
    <section className={styles.panel} aria-labelledby="rating-context-title">
      <div className={styles.heading}>
        <h2 id="rating-context-title">Lichess ratings</h2>
        <a className={styles.link} href="#/games">Open Games</a>
      </div>
      <div className={styles.grid}>
        {HISTORY_SPEEDS.map((speed) => (
          <RatingCard key={speed} speed={speed} current={currentRatings[speed]} />
        ))}
      </div>
    </section>
  )
}

/**
 * Chess.com ratings built only from stored chess.com games: the latest stored rating per speed and
 * at the time of the latest stored game.
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

  return (
    <section className={styles.panel} aria-labelledby="chesscom-ratings-title">
      <div className={styles.heading}>
        <h2 id="chesscom-ratings-title">Chess.com ratings</h2>
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

      {context !== null && (
        <div className={styles.grid}>
          {HISTORY_SPEEDS.map((speed) => {
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
  site = 'lichess',
}: {
  speed: HistorySpeed
  current: number | null
  site?: 'lichess' | 'chesscom'
}) {
  const titleId = site === 'lichess' ? `rating-${speed}` : `rating-${site}-${speed}`
  const value = current === null
    ? site === 'chesscom' && speed === 'classical' ? 'Not available' : 'Not rated'
    : current.toLocaleString('en-US')
  return (
    <article className={styles.card} aria-labelledby={titleId}>
      <h3 id={titleId} className={styles.cardTitle}>{speedLabels[speed]}</h3>
      <p className={styles.rating}>{value}</p>
    </article>
  )
}
