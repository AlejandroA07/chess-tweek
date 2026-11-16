import { useEffect, useRef, useState, type FormEvent } from 'react'
import styles from './GameRetrievalPanel.module.css'
import { requestChessComUsername } from './chessComPlayer'
import { sourceLabel } from './gameCatalog'
import {
  GameRetrievalError,
  retrieveGames,
  type GameRetrievalFailure,
  type GameRetrievalResult,
  type RetrievalSite,
} from './gameRetrieval'

type SubmissionState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'success'; result: GameRetrievalResult }
  | { kind: 'error'; failure: GameRetrievalFailure; retryAfterSeconds: number | null }

type PlayerState =
  | { kind: 'loading' }
  | { kind: 'ready'; username: string }
  | { kind: 'missing' }
  | { kind: 'unavailable' }

/**
 * Manual retrieval of finished games from one site. Lichess uses the connected account; chess.com
 * uses the username saved on Admin.
 */
export function GameRetrievalPanel({
  site,
  lichessUsername,
  onClose,
  onComplete,
  onSessionFailure,
}: {
  site: RetrievalSite
  lichessUsername: string
  onClose: () => void
  onComplete: () => void
  onSessionFailure: (failure: 'not_connected') => void
}) {
  const siteName = sourceLabel(site)
  const title = `Get ${siteName} Games`
  const [count, setCount] = useState('20')
  const [before, setBefore] = useState('')
  const [submission, setSubmission] = useState<SubmissionState>({ kind: 'idle' })
  const [player, setPlayer] = useState<PlayerState>(site === 'lichess'
    ? { kind: 'ready', username: lichessUsername }
    : { kind: 'loading' })
  const request = useRef<AbortController | null>(null)

  useEffect(() => () => request.current?.abort(), [])

  useEffect(() => {
    if (site !== 'chesscom') return
    const controller = new AbortController()
    requestChessComUsername(controller.signal)
      .then((username) => setPlayer(username === null ? { kind: 'missing' } : { kind: 'ready', username }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        setPlayer({ kind: 'unavailable' })
      })
    return () => controller.abort()
  }, [site])

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const parsedCount = Number(count)
    if (!Number.isSafeInteger(parsedCount) || parsedCount < 1 || parsedCount > 50) return

    const controller = new AbortController()
    request.current?.abort()
    request.current = controller
    setSubmission({ kind: 'submitting' })

    try {
      const result = await retrieveGames(site, parsedCount, before || null, controller.signal)
      setSubmission({ kind: 'success', result })
      onComplete()
    } catch (error: unknown) {
      if (error instanceof GameRetrievalError) {
        if (error.failure === 'not_connected') onSessionFailure(error.failure)
        if (error.failure === 'username_missing') setPlayer({ kind: 'missing' })
        setSubmission({
          kind: 'error',
          failure: error.failure,
          retryAfterSeconds: error.retryAfterSeconds,
        })
      } else if (!(error instanceof DOMException && error.name === 'AbortError')) {
        setSubmission({ kind: 'error', failure: 'unavailable', retryAfterSeconds: null })
      }
    } finally {
      if (request.current === controller) request.current = null
    }
  }

  return (
    <aside className={styles.panel} aria-labelledby="retrieval-panel-title">
      <header className={styles.header}>
        <div>
          <p>Manual retrieval</p>
          <h2 id="retrieval-panel-title">{title}</h2>
        </div>
        <button type="button" aria-label={`Close ${title}`} onClick={onClose}>Close</button>
      </header>

      {player.kind === 'loading' && <p className={styles.account}>Checking your chess.com username…</p>}
      {player.kind === 'missing' && (
        <div className={styles.account} role="status">
          <p>No chess.com username is saved for this account yet.</p>
          <p><a href="#/admin">Add your chess.com username on Admin</a>, then come back to get games.</p>
        </div>
      )}
      {player.kind === 'unavailable' && (
        <p className={styles.account} role="alert">
          Your chess.com username could not be read. Close this panel and try again.
        </p>
      )}
      {player.kind === 'ready' && (
        <>
          <p className={styles.account}>
            {site === 'lichess' ? 'Connected as' : 'Chess.com username'} <strong>{player.username}</strong>
          </p>
          <p className={styles.explanation}>
            Choose a finished-game batch. Retrieval never starts analysis.
            {site === 'chesscom' && ' Chess.com publishes games with a delay of up to a day, so a game you just finished may not appear yet. Only standard chess games are stored.'}
          </p>

          <form className={styles.form} onSubmit={(event) => void submit(event)}>
            <label htmlFor="retrieval-count">Number of games</label>
            <input
              id="retrieval-count"
              type="number"
              min="1"
              max="50"
              step="1"
              required
              autoFocus
              value={count}
              onChange={(event) => setCount(event.target.value)}
            />
            <small>1–50</small>

            <label htmlFor="retrieval-before">Games before</label>
            <input
              id="retrieval-before"
              type="date"
              value={before}
              onChange={(event) => setBefore(event.target.value)}
            />
            <small>Optional. Use this to retrieve an older batch.</small>

            <div className={styles.actions}>
              <button type="submit" disabled={submission.kind === 'submitting'}>
                {submission.kind === 'submitting' ? `Receiving up to ${count} games…` : 'Get Games'}
              </button>
              <button type="button" onClick={onClose}>Cancel</button>
            </div>
          </form>
        </>
      )}

      <div className={styles.outcome} aria-live="polite">
        {submission.kind === 'success' && (
          <p>
            Received {submission.result.received} {submission.result.received === 1 ? 'game' : 'games'} from {siteName}.
            {' '}{submission.result.created} new, {submission.result.updated} updated,{' '}
            {submission.result.unchanged} unchanged.
            {submission.result.rejected > 0 && ` ${submission.result.rejected} ${site === 'chesscom' ? 'games that are not standard chess or could not be read' : 'invalid games'} were skipped.`}
          </p>
        )}
        {submission.kind === 'error' && submission.failure !== 'username_missing' && (
          <p role="alert">{retrievalErrorMessage(siteName, submission)}</p>
        )}
      </div>
    </aside>
  )
}

function retrievalErrorMessage(
  siteName: string,
  submission: Extract<SubmissionState, { kind: 'error' }>,
) {
  if (submission.failure === 'rate_limited') {
    return `${siteName} asked us to wait ${submission.retryAfterSeconds ?? 60} seconds before trying again.`
  }
  if (submission.failure === 'busy') {
    return 'Another retrieval is already running. Wait for it to finish, then try again.'
  }
  if (submission.failure === 'not_connected') {
    return 'Your Lichess connection ended. Reconnect from Dashboard before trying again.'
  }
  if (submission.failure === 'player_not_found') {
    return 'Chess.com has no player with this username. Check the username on Admin, then try again.'
  }
  return 'Games could not be retrieved. Your stored library was not changed. Try again.'
}
