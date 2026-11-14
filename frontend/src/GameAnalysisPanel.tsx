import styles from './GameAnalysisPanel.module.css'
import type {
  AnalysisJob,
  AnalysisJobFailure,
  AnalysisPerspective,
  AnalysisRequestFailure,
} from './analysis'

const perspectiveLabels: Record<AnalysisPerspective, string> = {
  my_moves: 'your moves',
  opponent_moves: 'your opponent’s moves',
  both: 'both sides',
}

type Explanation = { text: string; admin: boolean }

const jobFailures: Record<AnalysisJobFailure, Explanation> = {
  engine_busy: {
    text: 'Stockfish was busy with a health check or benchmark. Nothing was saved. Try again in a moment.',
    admin: false,
  },
  engine_unavailable: {
    text: 'Stockfish could not be started. Nothing was saved. Open Admin and run Test Engine.',
    admin: true,
  },
  engine_failed: {
    text: 'Stockfish stopped or gave unusable output. Nothing was saved. Try again; if it repeats, test the engine on Admin.',
    admin: true,
  },
  profile_outdated: {
    text: 'Stockfish has changed since the last benchmark. Nothing was saved. Open Admin and run the benchmark again.',
    admin: true,
  },
  storage_failed: {
    text: 'The results could not be saved, so nothing was stored. Try again.',
    admin: false,
  },
}

const startFailures: Record<AnalysisRequestFailure, Explanation> = {
  analysis_busy: { text: 'Another analysis is already running. Wait for it to finish or cancel it.', admin: false },
  engine_profile_missing: { text: 'Analysis needs an engine benchmark first. Open Admin and run the benchmark.', admin: true },
  analysis_not_supported: { text: 'This game cannot be analyzed. Only standard chess games are supported.', admin: false },
  game_not_found: { text: 'This game is no longer stored. Refresh the library and try again.', admin: false },
  no_running_analysis: { text: 'No analysis is running.', admin: false },
  analysis_unavailable: { text: 'Analysis could not be started. Check the local app and try again.', admin: false },
  not_connected: { text: 'Connect Lichess to analyze games.', admin: false },
  session_unavailable: { text: 'The Lichess session could not be checked. Try again.', admin: false },
}

/**
 * The single analysis, as one region. A visually hidden live region stays mounted and announces
 * only pass changes and outcomes, so screen readers are not interrupted by every progress poll.
 */
export function GameAnalysisPanel({
  job,
  opponentName,
  starting,
  canceling,
  startFailure,
  now,
  onCancel,
  onRetry,
  onDismiss,
}: {
  job: AnalysisJob | null
  opponentName: string | null
  starting: boolean
  canceling: boolean
  startFailure: AnalysisRequestFailure | null
  now: number
  onCancel: () => void
  onRetry: () => void
  onDismiss: () => void
}) {
  return (
    <>
      <p className={styles.announcement} aria-live="polite">{announcement(job, starting)}</p>
      {visiblePanel()}
    </>
  )

  function visiblePanel() {
    const refused = startFailure === null ? null : (
      <div role="alert" className={styles.refusal}>
        <h3>Analysis did not start</h3>
        <p>{startFailures[startFailure].text}</p>
      </div>
    )

    if (job === null || (startFailure !== null && job.state !== 'running')) {
      if (starting) {
        return (
          <section className={styles.panel} aria-label="Game analysis">
            <h3>Starting analysis…</h3>
          </section>
        )
      }
      if (startFailure === null) return null
      return (
        <section className={styles.panel} aria-label="Game analysis" data-tone="error">
          {refused}
          <div className={styles.actions}>
            {startFailures[startFailure].admin && (
              <a className={styles.secondary} href="#/admin">Open Admin</a>
            )}
            <button className={styles.tertiary} type="button" onClick={onDismiss}>Dismiss</button>
          </div>
        </section>
      )
    }

    const subject = `${perspectiveLabels[job.perspective]} against ${opponentName ?? 'this opponent'}`

    if (job.state === 'running') {
      const scope = job.perspective === 'both' ? ' (both sides)' : ''
      const firstDone = job.pass === 'deep' || job.pass === 'saving'
      return (
        <section className={styles.panel} aria-label="Game analysis">
          {refused}
          <h3>Analyzing {subject}</h3>
          <p className={styles.step}>{runningStep(job, scope)}</p>
          <div className={styles.passes}>
            <label className={styles.pass}>
              <span>First pass</span>
              <progress
                max={1}
                value={firstDone ? 1 : job.pass === 'quick' && job.total > 0 ? job.judged / job.total : 0}
              />
            </label>
            <label className={styles.pass}>
              <span>Second pass</span>
              <progress
                max={1}
                value={job.pass === 'saving'
                  ? 1
                  : job.pass === 'deep' && job.total > 0 ? job.judged / job.total : 0}
              />
            </label>
          </div>
          <p className={styles.meta}>
            <span>Elapsed {formatElapsed(now - Date.parse(job.startedAt))}</span>
            <span>Stockfish runs locally and can warm your Mac.</span>
          </p>
          <div className={styles.actions}>
            <button
              className={styles.secondary}
              type="button"
              disabled={canceling}
              onClick={onCancel}
            >
              {canceling ? 'Stopping…' : 'Cancel Analysis'}
            </button>
            {canceling && <p className={styles.note}>Stopping after the current search. Nothing will be saved.</p>}
          </div>
        </section>
      )
    }

    if (job.state === 'completed') {
      return (
        <section className={styles.panel} aria-label="Game analysis" data-tone="success">
          <h3>Analysis saved</h3>
          <p>
            Stockfish finished {subject}
            {job.finishedAt ? ` in ${formatElapsed(Date.parse(job.finishedAt) - Date.parse(job.startedAt))}` : ''}.
            The results are shown on the game card.
          </p>
          <div className={styles.actions}>
            <button className={styles.tertiary} type="button" onClick={onDismiss}>Dismiss</button>
          </div>
        </section>
      )
    }

    if (job.state === 'canceled') {
      return (
        <section className={styles.panel} aria-label="Game analysis">
          <h3>Analysis canceled</h3>
          <p>The analysis of {subject} was stopped. Nothing was saved.</p>
          <div className={styles.actions}>
            <button className={styles.secondary} type="button" onClick={onRetry}>Analyze Again</button>
            <button className={styles.tertiary} type="button" onClick={onDismiss}>Dismiss</button>
          </div>
        </section>
      )
    }

    const failure = jobFailures[job.failure ?? 'engine_failed']
    return (
      <section className={styles.panel} aria-label="Game analysis" data-tone="error">
        <div role="alert">
          <h3>Analysis failed</h3>
          <p>{failure.text}</p>
        </div>
        <div className={styles.actions}>
          <button className={styles.secondary} type="button" onClick={onRetry}>Try Again</button>
          {failure.admin && <a className={styles.secondary} href="#/admin">Open Admin</a>}
          <button className={styles.tertiary} type="button" onClick={onDismiss}>Dismiss</button>
        </div>
      </section>
    )
  }
}

function announcement(job: AnalysisJob | null, starting: boolean) {
  if (job === null) return starting ? 'Starting analysis.' : ''
  switch (job.state) {
    case 'running':
      switch (job.pass) {
        case 'starting':
          return 'Analysis started.'
        case 'quick':
          return 'First pass running.'
        case 'deep':
          return 'Second pass running.'
        case 'saving':
          return 'Saving results.'
      }
      break
    case 'completed':
      return 'Analysis saved.'
    case 'canceled':
      return 'Analysis canceled. Nothing was saved.'
    case 'failed':
      return ''
  }
}

function runningStep(job: AnalysisJob, scope: string) {
  switch (job.pass) {
    case 'starting':
      return 'Starting Stockfish…'
    case 'quick':
      return `First pass · ${job.judged} of ${job.total} moves${scope}`
    case 'deep':
      return job.total === 0
        ? 'Second pass · no moves need a deeper check'
        : `Second pass · checking ${job.judged} of ${job.total} moves more deeply`
    case 'saving':
      return 'Saving results…'
  }
}

export function formatElapsed(milliseconds: number) {
  const seconds = Math.max(0, Math.floor(milliseconds / 1_000))
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}
