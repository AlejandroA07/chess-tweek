import { useEffect, useState } from 'react'
import styles from './DashboardRatingContext.module.css'
import {
  HISTORY_SPEEDS,
  RatingContextRequestError,
  requestRatingContext,
  type BotGroup,
  type BotRecord,
  type HistorySpeed,
  type RatingContext,
  type SpeedRating,
} from './ratingContext'

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; context: RatingContext }
  | { kind: 'error' }

type OtherSpeed = 'bullet' | 'correspondence'

const otherSpeeds: OtherSpeed[] = ['bullet', 'correspondence']

const otherSpeedLabels: Record<OtherSpeed, string> = {
  bullet: 'Bullet',
  correspondence: 'Correspondence',
}

const speedLabels: Record<HistorySpeed, string> = {
  blitz: 'Blitz',
  rapid: 'Rapid',
  classical: 'Classical',
}

export function DashboardRatingContext({
  currentRatings,
  onSessionFailure,
}: {
  currentRatings: Record<HistorySpeed | OtherSpeed, number | null>
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
          <h2 id="rating-context-title">Ratings and bot record</h2>
          <p>
            Current ratings come from Lichess. History, recent change, and the bot record are
            built from games stored on this Mac.
          </p>
        </div>
        <a className={styles.link} href="#/games">Open Games</a>
      </div>

      <dl className={styles.otherRatings} aria-label="Other Lichess ratings">
        {otherSpeeds.map((speed) => (
          <div key={speed}>
            <dt>{otherSpeedLabels[speed]}</dt>
            <dd>{formatRating(currentRatings[speed])}</dd>
          </div>
        ))}
      </dl>

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
        <BotRecordCard record={context?.botRecord ?? null} loading={state.kind === 'loading'} />
      </div>
    </section>
  )
}

function RatingCard({
  speed,
  current,
  history,
  loading,
}: {
  speed: HistorySpeed
  current: number | null
  history: SpeedRating | null
  loading: boolean
}) {
  const label = speedLabels[speed]
  const titleId = `rating-${speed}`
  return (
    <article className={styles.card} aria-labelledby={titleId}>
      <h3 id={titleId} className={styles.cardTitle}>{label} rating</h3>
      <p className={styles.rating}>{formatRating(current)}</p>
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
        <Sparkline label={label} rating={rating} />
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

function Sparkline({ label, rating }: { label: string; rating: SpeedRating }) {
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
  const description = `${label} rating after the last ${values.length} stored rated games: ` +
    `from ${formatNumber(values[0])} to ${formatNumber(values[values.length - 1])}, ` +
    `lowest ${formatNumber(lowest)}, highest ${formatNumber(highest)}`
  return (
    <svg
      className={styles.sparkline}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={description}
    >
      <polyline points={coordinates.join(' ')} />
    </svg>
  )
}

function BotRecordCard({ record, loading }: { record: BotRecord | null; loading: boolean }) {
  return (
    <article className={`${styles.card} ${styles.botCard}`} aria-labelledby="bot-record-title">
      <h3 id="bot-record-title" className={styles.cardTitle}>Bot record</h3>
      {record === null ? (
        <p className={styles.note}>{loading ? 'Loading stored games…' : 'Bot record unavailable.'}</p>
      ) : record.games === 0 ? (
        <p className={styles.note}>No games against Lichess AI or BOT accounts are stored yet.</p>
      ) : (
        <>
          <dl className={styles.tally}>
            <div>
              <dt>Wins</dt>
              <dd>{formatNumber(record.wins)}</dd>
            </div>
            <div>
              <dt>Draws</dt>
              <dd>{formatNumber(record.draws)}</dd>
            </div>
            <div>
              <dt>Losses</dt>
              <dd>{formatNumber(record.losses)}</dd>
            </div>
          </dl>
          <p className={styles.note}>
            {formatNumber(record.games)} game{record.games === 1 ? '' : 's'} counted
          </p>
          <p className={styles.note}>
            {record.lowestRating === null || record.highestRating === null
              ? 'No rated BOT opponents'
              : `BOT ratings ${formatNumber(record.lowestRating)}–${formatNumber(record.highestRating)}`}
          </p>
          <table className={styles.groups} aria-label="Results by opponent group">
            <thead>
              <tr>
                <th scope="col">Opponent</th>
                <th scope="col">Wins</th>
                <th scope="col">Draws</th>
                <th scope="col">Losses</th>
              </tr>
            </thead>
            <tbody>
              {record.groups.map((group) => (
                <tr key={groupKey(group)}>
                  <th scope="row">{groupLabel(group)}</th>
                  <td>{formatNumber(group.wins)}</td>
                  <td>{formatNumber(group.draws)}</td>
                  <td>{formatNumber(group.losses)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </article>
  )
}

function groupKey(group: BotGroup) {
  if (group.type === 'lichess_ai') return `ai-${group.aiLevel}`
  if (group.type === 'bot_rating_band') return `bot-${group.ratingFrom}`
  return 'bot-unrated'
}

function groupLabel(group: BotGroup) {
  if (group.type === 'lichess_ai') return `Lichess AI level ${group.aiLevel}`
  if (group.type === 'bot_rating_band') {
    return `BOT ${formatNumber(group.ratingFrom)}–${formatNumber(group.ratingTo)}`
  }
  return 'BOT without rating'
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
