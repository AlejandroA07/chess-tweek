import { useEffect, useRef, useState, type FormEvent } from 'react'
import styles from './AdminChessCom.module.css'
import {
  CHESSCOM_USERNAME,
  ChessComPlayerError,
  removeChessComUsername,
  requestChessComUsername,
  saveChessComUsername,
} from './chessComPlayer'

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; username: string | null }
  | { kind: 'error' }

type Action = 'idle' | 'saving' | 'removing'

/** The chess.com username used to read the owner's public chess.com games. */
export function AdminChessCom({
  onSessionFailure,
}: {
  onSessionFailure: (failure: 'not_connected') => void
}) {
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [draft, setDraft] = useState('')
  const [action, setAction] = useState<Action>('idle')
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null)
  const input = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setState({ kind: 'loading' })
    requestChessComUsername(controller.signal)
      .then((username) => {
        setState({ kind: 'ready', username })
        setDraft(username ?? '')
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (error instanceof ChessComPlayerError && error.failure === 'not_connected') {
          onSessionFailure('not_connected')
          return
        }
        setState({ kind: 'error' })
      })
    return () => controller.abort()
  }, [attempt, onSessionFailure])

  const trimmed = draft.trim()
  const valid = CHESSCOM_USERNAME.test(trimmed)
  const saved = state.kind === 'ready' ? state.username : null

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!valid) {
      setMessage({ kind: 'error', text: 'Enter 3 to 25 letters, numbers, underscores, or hyphens.' })
      return
    }
    setAction('saving')
    setMessage(null)
    try {
      const username = await saveChessComUsername(trimmed)
      setState({ kind: 'ready', username })
      setDraft(username)
      setMessage({ kind: 'success', text: `Saved. Get Chess.com Games on Games now uses ${username}.` })
    } catch (error: unknown) {
      if (isSessionLoss(error)) {
        onSessionFailure('not_connected')
        return
      }
      setMessage({ kind: 'error', text: failureText(error, 'saved') })
    } finally {
      setAction('idle')
    }
  }

  const remove = async () => {
    setAction('removing')
    setMessage(null)
    try {
      await removeChessComUsername()
      setState({ kind: 'ready', username: null })
      setDraft('')
      setMessage({ kind: 'success', text: 'Username removed. Stored chess.com games were kept.' })
      // The Remove button disappears with the saved username; continue from the empty field.
      input.current?.focus()
    } catch (error: unknown) {
      if (isSessionLoss(error)) {
        onSessionFailure('not_connected')
        return
      }
      setMessage({ kind: 'error', text: failureText(error, 'removed') })
    } finally {
      setAction('idle')
    }
  }

  return (
    <section className={styles.panel} aria-labelledby="chesscom-title">
      <p className={styles.eyebrow}>Game sources</p>
      <h2 id="chesscom-title">Chess.com account</h2>
      <p className={styles.muted}>
        Chess Tweek reads your finished games from chess.com&apos;s public game archive. No password or
        sign-in is needed, and nothing is sent to chess.com except the username.
      </p>

      {state.kind === 'loading' && (
        <p className={styles.muted} aria-live="polite">Checking the saved chess.com username…</p>
      )}

      {state.kind === 'error' && (
        <div role="alert">
          <h3 className={styles.subheading}>The saved username could not be loaded</h3>
          <p className={styles.muted}>Your settings were not changed. Check the local backend and try again.</p>
          <button className={styles.secondaryButton} type="button" onClick={() => setAttempt((value) => value + 1)}>
            Retry
          </button>
        </div>
      )}

      {state.kind === 'ready' && (
        <form className={styles.form} onSubmit={(event) => void save(event)}>
          <label htmlFor="chesscom-username">Chess.com username</label>
          <input
            ref={input}
            id="chesscom-username"
            type="text"
            autoComplete="off"
            spellCheck={false}
            maxLength={25}
            aria-describedby="chesscom-username-help"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
          <small id="chesscom-username-help">
            {saved === null
              ? 'Required to get chess.com games. 3 to 25 letters, numbers, underscores, or hyphens.'
              : `Saved: ${saved}. 3 to 25 letters, numbers, underscores, or hyphens.`}
          </small>
          <div className={styles.actions}>
            <button
              className={styles.primaryButton}
              type="submit"
              disabled={action !== 'idle'}
            >
              {action === 'saving' ? 'Saving…' : 'Save Username'}
            </button>
            {saved !== null && (
              <button
                className={styles.secondaryButton}
                type="button"
                disabled={action !== 'idle'}
                onClick={() => void remove()}
              >
                {action === 'removing' ? 'Removing…' : 'Remove Username'}
              </button>
            )}
          </div>
        </form>
      )}

      <div aria-live="polite">
        {message?.kind === 'success' && (
          <p className={styles.message} data-kind="success">{message.text}</p>
        )}
      </div>
      {message?.kind === 'error' && (
        <p className={styles.message} data-kind="error" role="alert">{message.text}</p>
      )}
    </section>
  )
}

function isSessionLoss(error: unknown) {
  return error instanceof ChessComPlayerError && error.failure === 'not_connected'
}

function failureText(error: unknown, verb: 'saved' | 'removed') {
  if (error instanceof ChessComPlayerError && error.failure === 'invalid_username') {
    return 'Enter 3 to 25 letters, numbers, underscores, or hyphens.'
  }
  return `The username could not be ${verb}. Your settings were not changed. Try again.`
}
