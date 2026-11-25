import { useEffect, useRef, useState, type ReactNode } from 'react'
import { InteractiveChessBoard } from './ChessBoard'
import styles from './PuzzleTraining.module.css'
import {
  LIST_LABELS,
  PuzzleRequestError,
  seconds,
  type MoveResult,
  type RevealResult,
  type TrainingList,
} from './puzzles'

export type SessionFailure = 'not_connected' | 'session_unavailable'

/** One attempt as the player shows it, whatever kind of Puzzle it is. */
export type PlayerAttempt = {
  attemptId: number
  puzzleId: number
  /** The position with the owner to move. */
  positionFen: string
  /** The move that led into the position, marked on the board, or null. */
  lastMove: string | null
  ownerMoves: number
  fastSolveLimitMillis: number
  currentList: TrainingList | null
  /** Names the board for assistive technology, for example "Puzzle 00sHx". */
  name: string
  /** Shown before the number of moves to find, for example "Lichess 1500". */
  detail: string
  /** A New Own Puzzle can be discarded instead of solved. */
  discardable: boolean
  /** Names a discardable puzzle in its confirmation, for example "Blunder at move 12 (Black), Nov 20, 2026". */
  title?: string
}

/** The completion fields every kind of Puzzle shares. */
export type PlayerCompletion = {
  previousList: TrainingList | null
  list: TrainingList
  successes: number
  strikes: number
  wrongMoves: number
  hintUsed: boolean
  elapsedMillis: number
  fastSolveLimitMillis: number
}

export type SessionStep = { attempt: PlayerAttempt; position: number; total: number }

/**
 * Where the player's puzzles come from and how it talks about them. A `single` source starts one
 * attempt at a time from a pool; a `session` source walks a Practice Session and starts the next
 * round when one ends.
 */
export type PlayerSource<C extends PlayerCompletion> = {
  key: string
  back: { href: string; label: string }
  eyebrow: string
  title: string
  description: string
  /** Mastered practice: attempts never change list state. */
  practice: boolean
  nextLabel: string
  start:
    | { kind: 'single'; next: () => Promise<PlayerAttempt> }
    | { kind: 'session'; create: () => Promise<number>; next: (sessionId: number) => Promise<SessionStep> }
  /** The explanation when the pool or list is empty. */
  emptyText: string
  /** The explanation when one chosen puzzle can no longer be started. */
  unavailableText?: string
  move: (attemptId: number, uci: string) => Promise<MoveResult<C>>
  hint: (attemptId: number) => Promise<string>
  reveal: (attemptId: number) => Promise<RevealResult<C>>
  /** Discards a discardable attempt's puzzle; the player then starts the next one. */
  discard?: (attempt: PlayerAttempt) => Promise<void>
  discardText?: string
  /** Facts and links below the shared completion summary. */
  completionExtra: (completion: C) => ReactNode
}

type Phase<C> =
  | { kind: 'ready' }
  | { kind: 'starting' }
  | { kind: 'playing'; attempt: PlayerAttempt }
  | { kind: 'complete'; attempt: PlayerAttempt; completion: C; revealed: boolean }

export function PuzzlePlayer<C extends PlayerCompletion>({
  source,
  onSessionFailure,
}: {
  source: PlayerSource<C>
  onSessionFailure: (failure: SessionFailure) => void
}) {
  const [phase, setPhase] = useState<Phase<C>>({ kind: 'starting' })
  // A Practice Session is created when the route opens. When every puzzle has been presented,
  // the next round starts at once with a fresh order, so practice continues without leaving.
  const [sessionId, setSessionId] = useState<number | null>(null)
  const [progress, setProgress] = useState<{ position: number; total: number; round: number } | null>(null)
  const practice = source.practice
  const inSession = source.start.kind === 'session'
  const [line, setLine] = useState<string[]>([])
  const [wrongMoves, setWrongMoves] = useState(0)
  const [hintSquare, setHintSquare] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  const [feedback, setFeedback] = useState<{ tone: 'correct' | 'incorrect'; text: string } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const statusRef = useRef<HTMLDivElement>(null)
  const puzzleTitleRef = useRef<HTMLHeadingElement>(null)
  const startRef = useRef<HTMLButtonElement>(null)
  const confirmRef = useRef<HTMLButtonElement>(null)
  const discardRef = useRef<HTMLButtonElement>(null)

  const nextAttempt = async (): Promise<PlayerAttempt> => {
    const start = source.start
    if (start.kind === 'single') return start.next()
    const newSession = async () => {
      const created = await start.create()
      setSessionId(created)
      return created
    }
    let round = progress?.round ?? 1
    let current = sessionId ?? await newSession()
    let next
    try {
      next = await start.next(current)
    } catch (requestError) {
      if (!(requestError instanceof PuzzleRequestError && requestError.code === 'session_complete')) {
        throw requestError
      }
      round += 1
      current = await newSession()
      next = await start.next(current)
    }
    setProgress({ position: next.position, total: next.total, round })
    return next.attempt
  }

  const startFailure = (requestError: unknown) => {
    const code = requestError instanceof PuzzleRequestError ? requestError.code : null
    if (code === 'session_complete' || code === 'session_not_found') setSessionId(null)
    if (code === 'session_not_found') {
      return 'This session ended because another Practice Session was started. Start a new session to continue.'
    }
    if (code === 'own_puzzle_not_found' && source.unavailableText !== undefined) return source.unavailableText
    if (code !== 'no_puzzle_available') return 'A puzzle could not be started. Try again.'
    return source.emptyText
  }

  const start = async (afterNotice: string | null = null) => {
    setPhase({ kind: 'starting' })
    setLine([])
    setWrongMoves(0)
    setHintSquare(null)
    setFeedback(null)
    setConfirmingDiscard(false)
    setNotice(afterNotice)
    setError(null)
    try {
      // The attempt (and its Solve Time) starts here; the board is interactive as soon as it returns.
      const attempt = await nextAttempt()
      setPhase({ kind: 'playing', attempt })
    } catch (requestError) {
      reportSessionFailure(requestError, onSessionFailure)
      setPhase({ kind: 'ready' })
      setError(startFailure(requestError))
    }
  }

  const failed = (requestError: unknown) => {
    reportSessionFailure(requestError, onSessionFailure)
    if (requestError instanceof PuzzleRequestError && requestError.code === 'attempt_finished') {
      setPhase({ kind: 'ready' })
      setError('This attempt has ended, for example because another puzzle was started.')
      return
    }
    setError('That action could not be completed. Try again.')
  }

  const move = async (uci: string) => {
    // After completion the board stays open for local exploration; nothing is sent or saved.
    if (phase.kind === 'complete') {
      setFeedback(null)
      return true
    }
    if (phase.kind !== 'playing') return false
    setBusy(true)
    setError(null)
    setConfirmingDiscard(false)
    try {
      const result = await source.move(phase.attempt.attemptId, uci)
      setWrongMoves(result.wrongMoves)
      if (result.outcome === 'wrong') {
        setFeedback({ tone: 'incorrect', text: 'Not the solution. The position is unchanged; try another move.' })
        return false
      }
      setHintSquare(null)
      setNotice(null)
      if (result.outcome === 'solved') {
        setLine((current) => [...current, uci])
        setFeedback({ tone: 'correct', text: 'Solved.' })
        setPhase({ kind: 'complete', attempt: phase.attempt, completion: result.completion, revealed: false })
        return true
      }
      setLine((current) => [...current, uci, result.opponentReply])
      setFeedback({ tone: 'correct', text: 'Correct. Your opponent replied; find the next move.' })
      return true
    } catch (requestError) {
      if (requestError instanceof PuzzleRequestError && requestError.code === 'illegal_move') {
        setError('That move is not legal in this position.')
        return false
      }
      failed(requestError)
      return false
    } finally {
      setBusy(false)
    }
  }

  const hint = async () => {
    if (phase.kind !== 'playing') return
    setBusy(true)
    setError(null)
    try {
      setHintSquare(await source.hint(phase.attempt.attemptId))
      setFeedback(null)
    } catch (requestError) {
      failed(requestError)
    } finally {
      setBusy(false)
    }
  }

  const reveal = async () => {
    if (phase.kind !== 'playing') return
    setBusy(true)
    setError(null)
    setConfirmingDiscard(false)
    try {
      const result = await source.reveal(phase.attempt.attemptId)
      setHintSquare(null)
      setNotice(null)
      setLine((current) => [...current, ...result.remainingMoves])
      setFeedback(null)
      setPhase({ kind: 'complete', attempt: phase.attempt, completion: result.completion, revealed: true })
    } catch (requestError) {
      failed(requestError)
    } finally {
      setBusy(false)
    }
  }

  const discard = async () => {
    if (phase.kind !== 'playing' || source.discard === undefined) return
    setBusy(true)
    setError(null)
    try {
      await source.discard(phase.attempt)
    } catch (requestError) {
      reportSessionFailure(requestError, onSessionFailure)
      setBusy(false)
      setConfirmingDiscard(false)
      setError(requestError instanceof PuzzleRequestError && requestError.code === 'own_puzzle_classified'
        ? 'This puzzle is already in a Training List, so it can no longer be discarded.'
        : 'The puzzle could not be discarded. Nothing has changed; try again.')
      return
    }
    setBusy(false)
    await start(`${phase.attempt.title ?? 'Puzzle'} discarded. Restore it from Discarded at any time.`)
  }

  // Opening a player route starts its first puzzle directly. The ref keeps React's development
  // double-mount from starting (and abandoning) a second attempt.
  const autoStarted = useRef(false)
  useEffect(() => {
    if (autoStarted.current) return
    autoStarted.current = true
    void start()
  }, [])

  // Keep focus on the content that replaced the control the owner just used.
  const previousPhase = useRef<Phase<C>['kind']>(phase.kind)
  useEffect(() => {
    const before = previousPhase.current
    previousPhase.current = phase.kind
    if (before === phase.kind) return
    if (phase.kind === 'complete') statusRef.current?.focus()
    else if (phase.kind === 'playing') puzzleTitleRef.current?.focus()
    else if (phase.kind === 'ready') startRef.current?.focus()
  }, [phase.kind])

  // The confirmation takes focus at its safest control; cancelling returns focus to Discard.
  const wasConfirming = useRef(false)
  useEffect(() => {
    if (confirmingDiscard) confirmRef.current?.focus()
    else if (wasConfirming.current && phase.kind === 'playing') discardRef.current?.focus()
    wasConfirming.current = confirmingDiscard
  }, [confirmingDiscard, phase.kind])

  const attempt = phase.kind === 'playing' || phase.kind === 'complete' ? phase.attempt : null
  const side = attempt === null ? null : sideToMove(attempt.positionFen)
  const canDiscard = phase.kind === 'playing' && phase.attempt.discardable && source.discard !== undefined

  return (
    <article className={styles.playerPage}>
      <a className={styles.backLink} href={source.back.href}>← {source.back.label}</a>
      <header className={styles.playerHeader}>
        <div>
          <p className={styles.eyebrow}>{source.eyebrow}</p>
          <h2>{source.title}</h2>
          <p>{source.description}</p>
        </div>
      </header>

      {attempt === null ? (
        <section className={styles.startPanel} aria-labelledby="start-title">
          <h3 id="start-title" aria-live="polite">
            {phase.kind === 'starting' ? 'Loading a puzzle…' : 'No puzzle is open'}
          </h3>
          {notice && <p className={styles.muted} role="status">{notice}</p>}
          {error && <p className={styles.error} role="alert">{error}</p>}
          {phase.kind === 'ready' && (
            <button
              ref={startRef}
              className={styles.primaryButton}
              type="button"
              onClick={() => void start()}
            >
              {inSession && sessionId === null ? 'Start a new session' : 'Try another puzzle'}
            </button>
          )}
        </section>
      ) : (
        <section className={styles.puzzleCard} aria-labelledby="puzzle-title">
          <div className={styles.board} data-active-board>
            <InteractiveChessBoard
              key={attempt.attemptId}
              fen={attempt.positionFen}
              orientation={side ?? 'white'}
              label={`${attempt.name}: ${side === 'black' ? 'Black' : 'White'} to move`}
              disabled={busy || confirmingDiscard}
              solution={null}
              line={line}
              initialLastMove={attempt.lastMove}
              hintSquare={hintSquare}
              onMove={move}
            />
          </div>
          <div className={styles.panel}>
            <p className={styles.eyebrow}>
              {attempt.detail} · {attempt.ownerMoves} {attempt.ownerMoves === 1 ? 'move' : 'moves'} to find
            </p>
            <h3 id="puzzle-title" ref={puzzleTitleRef} tabIndex={-1}>
              {side === 'black' ? 'Black' : 'White'} to move
            </h3>
            <dl className={styles.facts}>
              <div>
                <dt>Training List</dt>
                <dd>{attempt.currentList === null ? 'New puzzle' : LIST_LABELS[attempt.currentList]}</dd>
              </div>
              <div>
                <dt>Fast-solve limit</dt>
                <dd>{seconds(attempt.fastSolveLimitMillis)}</dd>
              </div>
              <div>
                <dt>Wrong Moves</dt>
                <dd>{wrongMoves}</dd>
              </div>
              {inSession && progress !== null && (
                <div>
                  <dt>Session</dt>
                  <dd>
                    {progress.round > 1 && `Round ${progress.round} · `}
                    {progress.position} of {progress.total}
                  </dd>
                </div>
              )}
            </dl>
            <div ref={statusRef} tabIndex={-1} className={styles.status} aria-live="polite">
              {notice && phase.kind === 'playing' && <p className={styles.muted}>{notice}</p>}
              {inSession && progress !== null && progress.round > 1 && progress.position === 1 &&
                phase.kind === 'playing' && (
                <p className={styles.muted}>
                  You practiced every puzzle in the last round. Round {progress.round} has started
                  with the puzzles now in this list, in a new order.
                </p>
              )}
              {feedback && (
                <p className={styles.feedback} data-outcome={feedback.tone}>
                  <strong>{feedback.tone === 'correct' ? 'Correct' : 'Wrong Move'}</strong>
                  {' '}{feedback.text}
                </p>
              )}
              {hintSquare && phase.kind === 'playing' && (
                <p className={styles.hint}>
                  <strong>Hint:</strong> move the piece on {hintSquare}.{' '}
                  {practice
                    ? 'Practice only: the puzzle stays Mastered.'
                    : 'This puzzle goes to Hard to Solve, with its progress there restarted.'}
                </p>
              )}
              {phase.kind === 'complete' && (
                <CompletionSummary
                  completion={phase.completion}
                  revealed={phase.revealed}
                  practice={practice}
                  extra={source.completionExtra(phase.completion)}
                />
              )}
            </div>
            {error && <p className={styles.error} role="alert">{error}</p>}
            {confirmingDiscard && phase.kind === 'playing' ? (
              <div className={styles.confirm} role="group" aria-labelledby="discard-confirm-title">
                <p id="discard-confirm-title">
                  <strong>Discard {attempt.title ?? 'this puzzle'}?</strong> {source.discardText}
                </p>
                <div className={styles.actions}>
                  <button
                    ref={confirmRef}
                    className={styles.secondaryButton}
                    type="button"
                    disabled={busy}
                    onClick={() => setConfirmingDiscard(false)}
                  >
                    Keep solving
                  </button>
                  <button
                    className={styles.dangerButton}
                    type="button"
                    disabled={busy}
                    onClick={() => void discard()}
                  >
                    Discard puzzle
                  </button>
                </div>
              </div>
            ) : (
              <div className={styles.actions}>
                {phase.kind === 'playing' ? (
                  <>
                    <button
                      className={styles.secondaryButton}
                      type="button"
                      disabled={busy || hintSquare !== null}
                      onClick={() => void hint()}
                    >
                      {hintSquare === null ? 'Hint' : 'Hint shown on the board'}
                    </button>
                    <button
                      className={styles.textButton}
                      type="button"
                      disabled={busy}
                      onClick={() => void reveal()}
                    >
                      Show solution
                    </button>
                    {canDiscard && (
                      <button
                        ref={discardRef}
                        className={styles.textButton}
                        type="button"
                        disabled={busy}
                        onClick={() => setConfirmingDiscard(true)}
                      >
                        Discard
                      </button>
                    )}
                  </>
                ) : (
                  <button className={styles.primaryButton} type="button" onClick={() => void start()}>
                    {source.nextLabel}
                  </button>
                )}
              </div>
            )}
            {phase.kind === 'playing' && (
              <p className={styles.muted}>
                {practice
                  ? 'Practice only: a hint or Show solution does not change Mastered status.'
                  : 'A hint or Show solution places this puzzle in Hard to Solve and restarts its progress there.'}
              </p>
            )}
          </div>
        </section>
      )}
    </article>
  )
}

function CompletionSummary({
  completion,
  revealed,
  practice,
  extra,
}: {
  completion: PlayerCompletion
  revealed: boolean
  practice: boolean
  extra: ReactNode
}) {
  const list = LIST_LABELS[completion.list]
  return (
    <div className={styles.summary}>
      <p>
        <strong>{revealed ? 'Solution shown.' : 'Puzzle complete.'}</strong>{' '}
        {practice
          ? 'Practice only: stays Mastered.'
          : completion.previousList === null
          ? `Placed in ${list}.`
          : completion.previousList === completion.list
            ? `Stays in ${list}.`
            : `Moved from ${LIST_LABELS[completion.previousList]} to ${list}.`}
      </p>
      <dl className={styles.facts}>
        <div>
          <dt>Solve Time</dt>
          <dd>
            {revealed
              ? `${seconds(completion.elapsedMillis)}, solution shown`
              : `${seconds(completion.elapsedMillis)} of ${seconds(completion.fastSolveLimitMillis)}`}
          </dd>
        </div>
        <div>
          <dt>Wrong Moves</dt>
          <dd>{completion.wrongMoves}</dd>
        </div>
        <div>
          <dt>Hint</dt>
          <dd>{completion.hintUsed ? 'Used' : 'Not used'}</dd>
        </div>
        {completion.previousList !== null && completion.list !== 'mastered' && (
          <div>
            <dt>Progress in {list}</dt>
            <dd>
              {completion.successes} {completion.successes === 1 ? 'success' : 'successes'}
              {completion.list === 'retiring' && `, ${completion.strikes} time ${completion.strikes === 1 ? 'strike' : 'strikes'}`}
            </dd>
          </div>
        )}
      </dl>
      {extra}
    </div>
  )
}

function sideToMove(fen: string): 'white' | 'black' {
  return fen.split(' ')[1] === 'b' ? 'black' : 'white'
}

export function reportSessionFailure(error: unknown, onSessionFailure: (failure: SessionFailure) => void) {
  if (error instanceof PuzzleRequestError &&
      (error.code === 'not_connected' || error.code === 'session_unavailable')) {
    onSessionFailure(error.code)
  }
}
