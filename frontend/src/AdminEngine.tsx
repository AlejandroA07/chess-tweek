import { useEffect, useState } from 'react'
import styles from './AdminEngine.module.css'
import {
  EngineRequestError,
  requestEngineStatus,
  runEngineCheck,
  type EngineState,
  type EngineStatus,
} from './engine'

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; status: EngineStatus }
  | { kind: 'error' }

const descriptions: Record<EngineState, { label: string; detail: string }> = {
  not_checked: {
    label: 'Found, not tested',
    detail: 'Stockfish was found. Run Test Engine to confirm it answers before analysis.',
  },
  ready: {
    label: 'Ready',
    detail: 'Stockfish answered the engine handshake and is ready for analysis.',
  },
  not_installed: {
    label: 'Not installed',
    detail: 'Install Stockfish with Homebrew, then run Test Engine.',
  },
  not_executable: {
    label: 'Cannot be used',
    detail: 'A file was found, but it is not an executable Homebrew Stockfish binary. Reinstall Stockfish with Homebrew.',
  },
  unsupported_engine: {
    label: 'Not Stockfish',
    detail: 'The binary answered as a different engine. Chess Tweek analyzes with Stockfish only.',
  },
  not_responding: {
    label: 'Not responding',
    detail: 'Stockfish started but did not complete the handshake in time. Try again, or reinstall it with Homebrew.',
  },
}

export function AdminEngine({
  onSessionFailure,
}: {
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [checking, setChecking] = useState(false)
  const [checkError, setCheckError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setState({ kind: 'loading' })
    requestEngineStatus(controller.signal)
      .then((status) => setState({ kind: 'ready', status }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (sessionFailure(error, onSessionFailure)) return
        setState({ kind: 'error' })
      })
    return () => controller.abort()
  }, [attempt, onSessionFailure])

  const testEngine = async () => {
    setChecking(true)
    setCheckError(null)
    try {
      setState({ kind: 'ready', status: await runEngineCheck() })
    } catch (error: unknown) {
      if (sessionFailure(error, onSessionFailure)) return
      setCheckError(
        error instanceof EngineRequestError && error.failure === 'engine_busy'
          ? 'Stockfish is busy with another task. Try again when it finishes.'
          : 'The engine test could not run. Check the local backend and try again.',
      )
    } finally {
      setChecking(false)
    }
  }

  return (
    <section className={styles.panel} aria-labelledby="engine-title">
      <p className={styles.eyebrow}>Local analysis engine</p>
      <h2 id="engine-title">Stockfish</h2>

      {state.kind === 'loading' && <p className={styles.muted} aria-live="polite">Checking for Stockfish…</p>}

      {state.kind === 'error' && (
        <div role="alert">
          <h3 className={styles.subheading}>Engine status could not be loaded</h3>
          <p className={styles.muted}>Check the local backend and try again.</p>
          <button className={styles.secondaryButton} type="button" onClick={() => setAttempt((value) => value + 1)}>
            Retry
          </button>
        </div>
      )}

      {state.kind === 'ready' && (
        <>
          <dl className={styles.facts}>
            <div>
              <dt>Status</dt>
              <dd data-state={state.status.state}>
                <span className={styles.indicator} aria-hidden="true" />
                {descriptions[state.status.state].label}
              </dd>
            </div>
            <div>
              <dt>Version</dt>
              <dd>{state.status.engineName ?? 'Unknown until tested'}</dd>
            </div>
            <div>
              <dt>Location</dt>
              <dd className={styles.path}>{state.status.path ?? 'Not found'}</dd>
            </div>
          </dl>
          <p className={styles.muted}>{descriptions[state.status.state].detail}</p>
          {state.status.state === 'not_installed' && (
            <p className={styles.command}>
              <span>Terminal</span>
              <code>brew install stockfish</code>
            </p>
          )}
          <div className={styles.actions}>
            <button className={styles.primaryButton} type="button" disabled={checking} onClick={() => void testEngine()}>
              {checking ? 'Testing…' : 'Test Engine'}
            </button>
          </div>
          {checkError && <p className={styles.error} role="alert">{checkError}</p>}
        </>
      )}
    </section>
  )
}

function sessionFailure(
  error: unknown,
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void,
) {
  if (
    error instanceof EngineRequestError &&
    (error.failure === 'not_connected' || error.failure === 'session_unavailable')
  ) {
    onSessionFailure(error.failure)
    return true
  }
  return false
}
