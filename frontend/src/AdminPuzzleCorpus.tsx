import { useCallback, useEffect, useState } from 'react'
import styles from './AdminPuzzleCorpus.module.css'
import {
  CorpusRequestError,
  cancelCorpusImport,
  checkCorpusUpdate,
  isActive,
  requestCorpusStatus,
  startCorpusImport,
  type CorpusImport,
  type CorpusIssue,
  type CorpusStatus,
} from './puzzleCorpus'

type LoadState = { kind: 'loading' } | { kind: 'ready'; status: CorpusStatus } | { kind: 'error' }
type Action = 'idle' | 'checking' | 'starting' | 'cancelling'

const POLL_MS = 1_000

const pausedReasons: Record<string, string> = {
  cancelled: 'You cancelled the import. Saved progress and the downloaded file are kept.',
  interrupted: 'Chess Tweek stopped while the import was running. Saved progress is kept.',
  download_failed: 'The download was interrupted. The bytes already saved are kept.',
  download_incomplete: 'The download ended early. The bytes already saved are kept.',
  insufficient_disk: 'Free disk space ran low. Free some space, then resume.',
  storage_unavailable: 'The local import folder could not be used. Check that var/puzzle-corpus is writable.',
  import_error: 'The import stopped unexpectedly. Saved progress is kept.',
}

const failedReasons: Record<string, string> = {
  source_changed: 'Lichess published a different file during the download, so the partial download was deleted.',
  source_corrupt: 'The downloaded file failed its integrity test and was deleted.',
  checksum_mismatch: 'The downloaded file changed after it was verified and was deleted.',
  source_missing: 'The downloaded file was missing when the import continued.',
  invalid_header: 'The downloaded file is not a Lichess Puzzle export and was deleted.',
  too_many_rejected: 'Too many records were invalid, so this snapshot was not accepted.',
  snapshot_too_large: 'The snapshot is larger than Chess Tweek accepts, so it was not imported.',
  verification_failed: 'Final verification did not match the imported records, so the snapshot was not accepted.',
}

const issueReasons: Record<string, string> = {
  immutable_conflict: 'Different position or solution for a stored ID; stored Puzzle kept',
  duplicate_id: 'Puzzle ID repeated in the snapshot',
  illegal_move_line: 'Illegal move line',
  invalid_position: 'Invalid position',
  malformed_record: 'Malformed record',
  invalid_identity: 'Invalid ID, position, or game link',
}

export function AdminPuzzleCorpus({
  onSessionFailure,
}: {
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [action, setAction] = useState<Action>('idle')
  const [actionError, setActionError] = useState<string | null>(null)

  const handleFailure = useCallback((error: unknown) => {
    if (
      error instanceof CorpusRequestError &&
      (error.failure === 'not_connected' || error.failure === 'session_unavailable')
    ) {
      onSessionFailure(error.failure)
      return true
    }
    return false
  }, [onSessionFailure])

  useEffect(() => {
    const controller = new AbortController()
    setState({ kind: 'loading' })
    requestCorpusStatus(controller.signal)
      .then((status) => setState({ kind: 'ready', status }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (handleFailure(error)) return
        setState({ kind: 'error' })
      })
    return () => controller.abort()
  }, [attempt, handleFailure])

  const running = state.kind === 'ready' && state.status.latestImport !== null &&
    isActive(state.status.latestImport.phase)

  useEffect(() => {
    if (!running) return
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      requestCorpusStatus(controller.signal)
        .then((status) => setState({ kind: 'ready', status }))
        .catch((error: unknown) => {
          if (error instanceof DOMException && error.name === 'AbortError') return
          if (handleFailure(error)) return
          setAttempt((value) => value + 1)
        })
    }, POLL_MS)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [running, state, handleFailure])

  const runAction = async (kind: Exclude<Action, 'idle'>) => {
    setAction(kind)
    setActionError(null)
    try {
      const status = kind === 'checking'
        ? await checkCorpusUpdate()
        : kind === 'starting'
          ? await startCorpusImport()
          : await cancelCorpusImport()
      setState({ kind: 'ready', status })
    } catch (error: unknown) {
      if (handleFailure(error)) return
      setActionError(actionFailure(error, state.kind === 'ready' ? state.status : null))
      if (error instanceof CorpusRequestError && error.failure === 'no_active_import') {
        setAttempt((value) => value + 1)
      }
    } finally {
      setAction('idle')
    }
  }

  return (
    <section className={styles.panel} aria-labelledby="puzzle-corpus-title">
      <p className={styles.eyebrow}>Puzzle Library</p>
      <h2 id="puzzle-corpus-title">Lichess Puzzle database</h2>
      <p className={styles.muted}>
        Chess Tweek stores the complete official Lichess Puzzle snapshot (CC0) once on this Mac.
        Updates happen only when you check and import here.
      </p>

      {state.kind === 'loading' && <p className={styles.muted} aria-live="polite">Loading the Puzzle Library status…</p>}

      {state.kind === 'error' && (
        <div role="alert">
          <h3 className={styles.subheading}>Puzzle Library status could not be loaded</h3>
          <p className={styles.muted}>Check the local backend and try again.</p>
          <button className={styles.secondaryButton} type="button" onClick={() => setAttempt((value) => value + 1)}>
            Retry
          </button>
        </div>
      )}

      {state.kind === 'ready' && (
        <CorpusDetails
          status={state.status}
          action={action}
          onCheck={() => void runAction('checking')}
          onStart={() => void runAction('starting')}
          onCancel={() => void runAction('cancelling')}
        />
      )}
      {actionError && <p className={styles.error} role="alert">{actionError}</p>}
    </section>
  )
}

function CorpusDetails({
  status,
  action,
  onCheck,
  onStart,
  onCancel,
}: {
  status: CorpusStatus
  action: Action
  onCheck: () => void
  onStart: () => void
  onCancel: () => void
}) {
  const latest = status.latestImport
  const active = latest !== null && isActive(latest.phase)
  const resumable = latest !== null && latest.phase === 'paused'
  const updateAvailable = status.lastCheck?.updateAvailable === true
  const startReason = !status.zstdAvailable
    ? 'Install zstd before importing.'
    : active
      ? null
      : resumable
        ? null
        : status.lastCheck === null
          ? 'Check for update first.'
          : updateAvailable
            ? null
            : 'The stored snapshot is already current.'
  const busy = action !== 'idle'

  return (
    <>
      <dl className={styles.facts}>
        <div>
          <dt>Stored snapshot</dt>
          <dd>{status.stored ? formatDay(status.stored.snapshotDate) : 'None yet'}</dd>
        </div>
        <div>
          <dt>Puzzles</dt>
          <dd>{status.stored ? formatNumber(status.stored.recordCount) : '0 imported'}</dd>
        </div>
        <div>
          <dt>Database</dt>
          <dd>{formatBytes(status.storage.databaseBytes)}</dd>
        </div>
        <div>
          <dt>Free disk</dt>
          <dd>{formatBytes(status.storage.usableBytes)}</dd>
        </div>
      </dl>

      {status.stored && (
        <p className={styles.provenance}>
          Imported {formatInstant(status.stored.importedAt)} with {status.stored.importerVersion} from{' '}
          <span className={styles.technical}>{status.stored.sourceUrl}</span> · SHA-256{' '}
          <span className={styles.technical}>{status.stored.downloadChecksum}</span>
        </p>
      )}

      {!status.zstdAvailable && (
        <div className={styles.warning} role="status">
          <p>The zstd prerequisite is not installed, so the compressed snapshot cannot be read.</p>
          <p className={styles.command}>
            <span>Terminal</span>
            <code>brew install zstd</code>
          </p>
        </div>
      )}

      <section className={styles.section} aria-labelledby="corpus-update-title">
        <h3 id="corpus-update-title" className={styles.subheading}>Update</h3>
        <p className={styles.muted}>
          Check for update asks Lichess only for the file date and size. Nothing is downloaded until
          you start the import.
        </p>
        {status.lastCheck && (
          <p className={styles.result} role="status">
            Checked {formatInstant(status.lastCheck.checkedAt)}: Lichess snapshot of{' '}
            {formatDay(status.lastCheck.remoteSnapshotDate)} ({formatBytes(status.lastCheck.remoteBytes)}).{' '}
            {status.lastCheck.updateAvailable ? 'An update is available.' : 'You already have this snapshot.'}
          </p>
        )}
        <div className={styles.actions}>
          <button className={styles.secondaryButton} type="button" disabled={busy || active} onClick={onCheck}>
            {action === 'checking' ? 'Checking…' : 'Check for update'}
          </button>
          {active ? (
            <button className={styles.secondaryButton} type="button" disabled={busy} onClick={onCancel}>
              {action === 'cancelling' ? 'Cancelling…' : 'Cancel import'}
            </button>
          ) : (
            <button
              className={styles.primaryButton}
              type="button"
              disabled={busy || startReason !== null}
              aria-describedby={startReason ? 'corpus-start-reason' : undefined}
              onClick={onStart}
            >
              {action === 'starting' ? 'Starting…' : resumable ? 'Resume import' : 'Download and import'}
            </button>
          )}
        </div>
        {startReason && !active && (
          <p id="corpus-start-reason" className={styles.muted}>{startReason}</p>
        )}
        {!active && !resumable && updateAvailable && status.lastCheck && (
          <p className={styles.muted}>
            The import downloads {formatBytes(status.lastCheck.remoteBytes)} and needs about{' '}
            {formatBytes(status.storage.requiredFreeBytes)} of additional free space while it builds
            the library. The downloaded file is deleted after verification.
          </p>
        )}
      </section>

      {latest && <ImportSection latest={latest} />}
      {latest && status.issues.length > 0 && <IssueList issues={status.issues} latest={latest} />}
    </>
  )
}

function ImportSection({ latest }: { latest: CorpusImport }) {
  return (
    <section className={styles.section} aria-labelledby="corpus-import-title">
      <h3 id="corpus-import-title" className={styles.subheading}>
        {latest.phase === 'verified' ? 'Last import' : `Import of the ${formatDay(latest.snapshotDate)} snapshot`}
      </h3>
      <div aria-live="polite">
        <ImportState latest={latest} />
      </div>
      <p className={styles.counts}>
        {formatNumber(latest.processedRecords)} records read · {formatNumber(latest.insertedPuzzles)} new ·{' '}
        {formatNumber(latest.updatedPuzzles)} updated · {formatNumber(latest.unchangedPuzzles)} unchanged ·{' '}
        {formatNumber(latest.rejectedRecords)} rejected · {formatNumber(latest.conflictingRecords)} conflicts
        {latest.phase === 'verified' && (
          <>
            {' '}· {formatNumber(latest.removedPuzzles)} removed · {formatNumber(latest.retainedPuzzles)} kept
            because your work references them
          </>
        )}
      </p>
    </section>
  )
}

function ImportState({ latest }: { latest: CorpusImport }) {
  switch (latest.phase) {
    case 'downloading':
      return (
        <Progress
          label={`Downloading ${formatBytes(latest.downloadedBytes)} of ${formatBytes(latest.sourceBytes)}`}
          value={latest.downloadedBytes}
          max={latest.sourceBytes}
        />
      )
    case 'importing':
      return (
        <Progress
          label={`Importing and validating Puzzles · ${percent(latest.consumedBytes, latest.sourceBytes)} of the file read`}
          value={latest.consumedBytes}
          max={latest.sourceBytes}
        />
      )
    case 'finalizing':
      return <Progress label="Removing Puzzles missing from this snapshot and verifying the library" />
    case 'paused':
      return (
        <p className={styles.warningText}>
          <strong>Paused.</strong> {pausedReasons[latest.failureCode ?? ''] ?? 'Saved progress is kept.'}{' '}
          Resume import continues where it stopped.
        </p>
      )
    case 'failed':
      return (
        <p className={styles.errorText}>
          <strong>Not imported.</strong> {failedReasons[latest.failureCode ?? ''] ?? 'The snapshot was not accepted.'}{' '}
          Your existing Puzzles and progress are unchanged. Check for update to start again.
        </p>
      )
    case 'verified':
      return (
        <p className={styles.successText}>
          <strong>Verified.</strong> Completed {latest.completedAt ? formatInstant(latest.completedAt) : ''}.{' '}
          {latest.sourceDeleted ? 'The downloaded file was deleted.' : 'The downloaded file is being removed.'}
        </p>
      )
  }
}

function Progress({ label, value, max }: { label: string; value?: number; max?: number }) {
  return (
    <div className={styles.progress}>
      <p>{label}</p>
      <progress aria-label={label} value={value} max={max} />
    </div>
  )
}

function IssueList({ issues, latest }: { issues: CorpusIssue[]; latest: CorpusImport }) {
  const total = latest.rejectedRecords + latest.conflictingRecords
  return (
    <section className={styles.section} aria-labelledby="corpus-issues-title">
      <h3 id="corpus-issues-title" className={styles.subheading}>Records not imported</h3>
      <p className={styles.muted}>
        Showing {issues.length} of {formatNumber(total)}. Conflicts never replace a stored position
        or solution.
      </p>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th scope="col">Record</th>
              <th scope="col">Puzzle ID</th>
              <th scope="col">Type</th>
              <th scope="col">Reason</th>
            </tr>
          </thead>
          <tbody>
            {issues.map((issue) => (
              <tr key={issue.recordNumber}>
                <td>{formatNumber(issue.recordNumber)}</td>
                <td className={styles.technical}>{issue.externalId ?? 'Unreadable'}</td>
                <td>{issue.kind === 'conflict' ? 'Conflict' : 'Rejected'}</td>
                <td>{issueReasons[issue.reason] ?? readable(issue.reason)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function actionFailure(error: unknown, status: CorpusStatus | null) {
  const failure = error instanceof CorpusRequestError ? error.failure : null
  switch (failure) {
    case 'update_check_failed':
      return 'Lichess could not be reached for the file details. Nothing changed; try again later.'
    case 'import_running':
      return 'An import is already running.'
    case 'no_update_available':
      return 'No update is available. Check for update first.'
    case 'zstd_missing':
      return 'zstd is not installed. Run brew install zstd, then try again.'
    case 'insufficient_disk':
      return `There is not enough free disk space. The import needs the download plus about ${
        formatBytes(status?.storage.requiredFreeBytes ?? 0)} free.`
    case 'no_active_import':
      return 'The import had already stopped.'
    default:
      return 'The Puzzle Library action could not run. Check the local backend and try again.'
  }
}

function readable(code: string) {
  const words = code.replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function percent(value: number, max: number) {
  return max <= 0 ? '0%' : `${Math.min(100, Math.floor((value / max) * 100))}%`
}

function formatNumber(value: number) {
  return value.toLocaleString('en-US')
}

function formatBytes(value: number) {
  const units = ['bytes', 'KB', 'MB', 'GB', 'TB']
  let amount = value
  let unit = 0
  while (amount >= 1000 && unit < units.length - 1) {
    amount /= 1000
    unit += 1
  }
  return unit === 0 ? `${value} bytes` : `${amount.toLocaleString('en-US', { maximumFractionDigits: 1 })} ${units[unit]}`
}

function formatDay(value: string) {
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${value}T00:00:00Z`))
}

function formatInstant(value: string) {
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}
