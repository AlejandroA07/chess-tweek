import { useEffect, useRef, useState, type FormEvent } from 'react'
import styles from './GameRetrievalPanel.module.css'
import {
  GameRetrievalError,
  retrieveGames,
  type GameRetrievalFailure,
  type GameRetrievalResult,
} from './gameRetrieval'

type SubmissionState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'success'; result: GameRetrievalResult }
  | { kind: 'error'; failure: GameRetrievalFailure; retryAfterSeconds: number | null }

export function GameRetrievalPanel({
  username,
  onClose,
  onComplete,
  onSessionFailure,
}: {
  username: string
  onClose: () => void
  onComplete: () => void
  onSessionFailure: (failure: 'not_connected') => void
}) {
  const [count, setCount] = useState('20')
  const [before, setBefore] = useState('')
  const [submission, setSubmission] = useState<SubmissionState>({ kind: 'idle' })
  const request = useRef<AbortController | null>(null)

  useEffect(() => () => request.current?.abort(), [])

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const parsedCount = Number(count)
    if (!Number.isSafeInteger(parsedCount) || parsedCount < 1 || parsedCount > 50) return

    const controller = new AbortController()
    request.current?.abort()
    request.current = controller
    setSubmission({ kind: 'submitting' })

    try {
      const result = await retrieveGames(parsedCount, before || null, controller.signal)
      setSubmission({ kind: 'success', result })
      onComplete()
    } catch (error: unknown) {
      if (error instanceof GameRetrievalError) {
        if (error.failure === 'not_connected') onSessionFailure(error.failure)
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
          <h2 id="retrieval-panel-title">Get Lichess Games</h2>
        </div>
        <button type="button" aria-label="Close Get Lichess Games" onClick={onClose}>Close</button>
      </header>

      <p className={styles.account}>Connected as <strong>{username}</strong></p>
      <p className={styles.explanation}>Choose a finished-game batch. Retrieval never starts analysis.</p>

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

      <div className={styles.outcome} aria-live="polite">
        {submission.kind === 'success' && (
          <p>
            Received {submission.result.received} {submission.result.received === 1 ? 'game' : 'games'} from Lichess.
            {' '}{submission.result.created} new, {submission.result.updated} updated,{' '}
            {submission.result.unchanged} unchanged.
            {submission.result.rejected > 0 && ` ${submission.result.rejected} invalid games were skipped.`}
          </p>
        )}
        {submission.kind === 'error' && (
          <p role="alert">{retrievalErrorMessage(submission)}</p>
        )}
      </div>
    </aside>
  )
}

function retrievalErrorMessage(
  submission: Extract<SubmissionState, { kind: 'error' }>,
) {
  if (submission.failure === 'rate_limited') {
    return `Lichess asked us to wait ${submission.retryAfterSeconds ?? 60} seconds before trying again.`
  }
  if (submission.failure === 'busy') {
    return 'Another retrieval is already running. Wait for it to finish, then try again.'
  }
  if (submission.failure === 'not_connected') {
    return 'Your Lichess connection ended. Reconnect from Dashboard before trying again.'
  }
  return 'Games could not be retrieved. Your stored library was not changed. Try again.'
}
