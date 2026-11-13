import { useEffect, useState } from 'react'
import styles from './AdminEngine.module.css'
import {
  EngineRequestError,
  requestEngineStatus,
  runEngineBenchmark,
  runEngineCheck,
  type EngineProfile,
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
  const [action, setAction] = useState<'idle' | 'checking' | 'benchmarking'>('idle')
  const [actionError, setActionError] = useState<string | null>(null)

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

  const runAction = async (kind: 'checking' | 'benchmarking') => {
    setAction(kind)
    setActionError(null)
    try {
      const status = kind === 'checking' ? await runEngineCheck() : await runEngineBenchmark()
      setState({ kind: 'ready', status })
    } catch (error: unknown) {
      if (sessionFailure(error, onSessionFailure)) return
      setActionError(actionFailure(error, kind))
    } finally {
      setAction('idle')
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
            <button
              className={styles.primaryButton}
              type="button"
              disabled={action !== 'idle'}
              onClick={() => void runAction('checking')}
            >
              {action === 'checking' ? 'Testing…' : 'Test Engine'}
            </button>
          </div>
          <ProfileSection
            profile={state.status.profile}
            currentEngine={state.status.engineName}
            running={action === 'benchmarking'}
            disabled={action !== 'idle'}
            onRun={() => void runAction('benchmarking')}
          />
          {actionError && <p className={styles.error} role="alert">{actionError}</p>}
        </>
      )}
    </section>
  )
}

function ProfileSection({
  profile,
  currentEngine,
  running,
  disabled,
  onRun,
}: {
  profile: EngineProfile | null
  currentEngine: string | null
  running: boolean
  disabled: boolean
  onRun: () => void
}) {
  const outdated = profile !== null && currentEngine !== null && currentEngine !== profile.engineName
  return (
    <section className={styles.profile} aria-labelledby="profile-title">
      <h3 id="profile-title" className={styles.subheading}>Analysis profile</h3>
      {profile === null ? (
        <p className={styles.muted}>
          No benchmark yet. Run Benchmark measures this Mac with the analysis settings and fixes how
          much each move is searched. It takes about 10 seconds.
        </p>
      ) : (
        <>
          <dl className={styles.facts}>
            <div>
              <dt>Measured speed</dt>
              <dd>{formatSpeed(profile.nodesPerSecond)}</dd>
            </div>
            <div>
              <dt>Quick search</dt>
              <dd>{formatNumber(profile.quickNodes)} nodes · about {formatSeconds(profile.quickTargetMs)}</dd>
            </div>
            <div>
              <dt>Deep search</dt>
              <dd>{formatNumber(profile.deepNodes)} nodes · about {formatSeconds(profile.deepTargetMs)}</dd>
            </div>
          </dl>
          <p className={styles.muted}>
            {profile.threads} threads · {profile.hashMb} MB hash · {profile.multiPv} best lines ·
            measured {formatDate(profile.createdAt)} with {profile.engineName}
          </p>
          {outdated && (
            <p className={styles.warning} role="status">
              Stockfish changed to {currentEngine} after this benchmark. Run it again so new
              analyses use a profile measured with this version.
            </p>
          )}
        </>
      )}
      <div className={styles.actions}>
        <button className={styles.secondaryButton} type="button" disabled={disabled} onClick={onRun}>
          {running ? 'Running benchmark…' : profile === null ? 'Run Benchmark' : 'Run Benchmark Again'}
        </button>
      </div>
      {running && (
        <p className={styles.muted} aria-live="polite">
          Measuring 6 positions with Stockfish. This takes about 10 seconds.
        </p>
      )}
    </section>
  )
}

function actionFailure(error: unknown, kind: 'checking' | 'benchmarking') {
  const failure = error instanceof EngineRequestError ? error.failure : null
  if (failure === 'engine_busy') return 'Stockfish is busy with another task. Try again when it finishes.'
  if (failure === 'engine_not_ready') {
    return 'Stockfish is not ready. Run Test Engine and resolve its status before benchmarking.'
  }
  if (failure === 'benchmark_failed') {
    return 'The benchmark did not complete, so no profile was saved. Try again.'
  }
  return kind === 'checking'
    ? 'The engine test could not run. Check the local backend and try again.'
    : 'The benchmark could not run. Check the local backend and try again.'
}

function formatSpeed(nodesPerSecond: number) {
  return `${(nodesPerSecond / 1_000_000).toFixed(2)} million nodes/s`
}

function formatSeconds(milliseconds: number) {
  return `${(milliseconds / 1_000).toLocaleString('en-US', { maximumFractionDigits: 2 })} s`
}

function formatNumber(value: number) {
  return value.toLocaleString('en-US')
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
    .format(new Date(value))
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
