import { useCallback, useEffect, useMemo, useState } from 'react'
import { InteractiveChessBoard } from './ChessBoard'
import styles from './TrainingCourse.module.css'
import { LIST_LABELS, seconds, type PuzzleAttempt, type PuzzleCompletion } from './puzzles'
import {
  completeLesson,
  loadCourse,
  loadExerciseAnswer,
  loadProgress,
  requestExerciseHint,
  revealExercise,
  startExercise,
  startLesson,
  submitExerciseMove,
  TrainingRequestError,
  type Course,
  type Exercise,
  type Lesson,
  type Progress,
} from './training'

type ConnectionState = 'checking' | 'disconnected' | 'connected' | 'unavailable'

export function TrainingCourse({
  connectionState,
  lessonKey,
  onSessionFailure,
}: {
  connectionState: ConnectionState
  lessonKey: string | null
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [course, setCourse] = useState<Course | null>(null)
  const [progress, setProgress] = useState<Progress | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')

  const refreshProgress = useCallback(async () => {
    if (connectionState !== 'connected') {
      setProgress(null)
      return
    }
    try {
      setProgress(await loadProgress())
    } catch (error) {
      handleFailure(error, onSessionFailure)
    }
  }, [connectionState, onSessionFailure])

  const load = useCallback(async (signal?: AbortSignal) => {
    setState('loading')
    try {
      const loaded = await loadCourse(signal)
      setCourse(loaded)
      if (connectionState === 'connected') {
        try {
          setProgress(await loadProgress(signal))
        } catch (error) {
          handleFailure(error, onSessionFailure)
          setProgress(null)
        }
      } else {
        setProgress(null)
      }
      setState('ready')
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      setState('failed')
    }
  }, [connectionState, onSessionFailure])

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  if (state === 'loading') {
    return (
      <section className={styles.statePanel} aria-live="polite">
        <p className={styles.eyebrow}>Guided course</p>
        <h2>Loading the training method…</h2>
      </section>
    )
  }
  if (state === 'failed' || course === null) {
    return (
      <section className={styles.statePanel} tabIndex={-1}>
        <p className={styles.eyebrow}>Guided course</p>
        <h2>The course could not be loaded</h2>
        <p>The local backend did not return valid course material. Your saved progress is unchanged.</p>
        <button className={styles.secondaryButton} type="button" onClick={() => void load()}>
          Retry
        </button>
      </section>
    )
  }

  const lesson = course.modules
    .flatMap((module) => module.lessons)
    .find((candidate) => candidate.key === lessonKey)
  if (lessonKey !== null && lesson === undefined) {
    return (
      <section className={styles.statePanel}>
        <p className={styles.eyebrow}>Guided course</p>
        <h2>That lesson is not available</h2>
        <p>The course has not published this lesson yet.</p>
        <a className={styles.secondaryLink} href="#/training">Return to course</a>
      </section>
    )
  }
  if (lesson !== undefined) {
    return (
      <LessonView
        // A new lesson gets fresh lesson state, starting at its first position.
        key={lesson.key}
        course={course}
        lesson={lesson}
        progress={progress}
        connected={connectionState === 'connected'}
        onProgress={setProgress}
        onRefreshProgress={refreshProgress}
        onSessionFailure={onSessionFailure}
      />
    )
  }
  return <CourseDashboard course={course} progress={progress} connectionState={connectionState} />
}

function CourseDashboard({
  course,
  progress,
  connectionState,
}: {
  course: Course
  progress: Progress | null
  connectionState: ConnectionState
}) {
  const availableLessons = course.modules.flatMap((module) => module.lessons)
  const recommended = availableLessons.find((lesson) => lesson.key === progress?.recommendedLessonKey)
    ?? availableLessons[0]
  const completed = progress?.completedLessons ?? 0
  const total = progress?.totalLessons ?? availableLessons.length
  const percent = total === 0 ? 0 : Math.round((completed / total) * 100)

  return (
    <div className={styles.coursePage}>
      <section className={styles.hero} aria-labelledby="course-title">
        <div>
          <p className={styles.eyebrow}>Independent guided course</p>
          <h2 id="course-title">{course.title}</h2>
          <p className={styles.subtitle}>{course.subtitle}</p>
        </div>
        <div className={styles.progressCard} aria-label="Course progress">
          <span>{progress === null ? 'Course preview' : `${completed} of ${total} lessons`}</span>
          <div className={styles.progressTrack} aria-hidden="true">
            <span style={{ width: `${percent}%` }} />
          </div>
          <strong>{progress === null ? 'Progress starts after connection' : `${percent}% complete`}</strong>
        </div>
      </section>

      {connectionState !== 'connected' && (
        <section className={styles.notice} aria-label="Offline course access">
          <strong>Course material remains available.</strong>
          <p>Connect Lichess to save progress, request hints, and submit board exercises.</p>
        </section>
      )}

      {recommended && (
        <section className={styles.continuePanel} aria-labelledby="continue-title">
          <div>
            <p className={styles.eyebrow}>Recommended next</p>
            <h3 id="continue-title">{recommended.title}</h3>
            <p>{recommended.summary}</p>
            <span>{recommended.estimatedMinutes} minutes · 3 positions</span>
          </div>
          <a className={styles.primaryLink} href={`#/training/lessons/${recommended.key}`}>
            {progress?.lessons[recommended.key]?.state === 'in_progress' ? 'Continue lesson' : 'Start lesson'}
          </a>
        </section>
      )}

      <section className={styles.routine} aria-labelledby="routine-title">
        <p className={styles.eyebrow}>The move routine</p>
        <h3 id="routine-title">Four questions before you commit</h3>
        <ol>
          <li><strong>Read:</strong> What changed, and what is threatened?</li>
          <li><strong>Generate:</strong> Which checks, captures, threats, or active moves matter?</li>
          <li><strong>Test:</strong> What is the opponent's strongest reply?</li>
          <li><strong>Verify:</strong> Is the chosen move tactically safe?</li>
        </ol>
      </section>

      <section aria-labelledby="roadmap-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Course roadmap</p>
            <h3 id="roadmap-title">Build the method in order, browse at your pace</h3>
          </div>
        </div>
        <div className={styles.moduleGrid}>
          {course.modules.map((module, index) => (
            <article className={styles.moduleCard} key={module.key} data-status={module.status}>
              <span>Module {index + 1}</span>
              <h4>{module.title}</h4>
              <p>{module.description}</p>
              {module.status === 'planned' ? (
                <strong>Planned</strong>
              ) : (
                <ul>
                  {module.lessons.map((lesson) => (
                    <li key={lesson.key}>
                      <a href={`#/training/lessons/${lesson.key}`}>{lesson.title}</a>
                      <span>{lessonState(progress, lesson.key)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </article>
          ))}
        </div>
      </section>

      <aside className={styles.attribution}>
        <strong>Source and independence</strong>
        <p>{course.attribution}</p>
      </aside>
    </div>
  )
}

function LessonView({
  course,
  lesson,
  progress,
  connected,
  onProgress,
  onRefreshProgress,
  onSessionFailure,
}: {
  course: Course
  lesson: Lesson
  progress: Progress | null
  connected: boolean
  onProgress: (progress: Progress) => void
  onRefreshProgress: () => Promise<void>
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [completionError, setCompletionError] = useState<string | null>(null)
  const [completing, setCompleting] = useState(false)
  const [activeExerciseIndex, setActiveExerciseIndex] = useState(0)

  useEffect(() => {
    if (!connected) return
    void startLesson(lesson.key)
      .then(onProgress)
      .catch((error) => handleFailure(error, onSessionFailure))
  }, [connected, lesson.key, onProgress, onSessionFailure])

  const finish = async () => {
    setCompleting(true)
    setCompletionError(null)
    try {
      const updated = await completeLesson(lesson.key)
      onProgress(updated)
      window.location.hash = '#/training'
    } catch (error) {
      if (error instanceof TrainingRequestError && error.code === 'incomplete') {
        setCompletionError('Resolve every position correctly or reveal its answer before completing the lesson.')
      } else {
        handleFailure(error, onSessionFailure)
        setCompletionError('The lesson could not be completed. Your saved exercise progress is unchanged.')
      }
    } finally {
      setCompleting(false)
    }
  }

  const activeExercise = lesson.exercises[activeExerciseIndex]

  return (
    <article className={styles.lessonPage}>
      <a className={styles.backLink} href="#/training">← Course roadmap</a>
      <header className={styles.lessonHeader}>
        <div>
          <p className={styles.eyebrow}>{course.title}</p>
          <h2>{lesson.title}</h2>
          <p>{lesson.summary}</p>
        </div>
        <span>{lesson.estimatedMinutes} minutes</span>
      </header>

      {!connected && (
        <section className={styles.notice} aria-label="Connection required for exercises">
          <strong>Read-only course mode</strong>
          <p>Connect Lichess from Dashboard to submit moves and save lesson progress.</p>
        </section>
      )}

      <section className={styles.lessonText} aria-labelledby="lesson-explanation">
        <div>
          <h3 id="lesson-explanation">The idea</h3>
          {lesson.explanation.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}
        </div>
        <aside>
          <h3>Recall checklist</h3>
          <ol>{lesson.checklist.map((item) => <li key={item}>{item}</li>)}</ol>
        </aside>
      </section>

      <section className={styles.exerciseSection} aria-labelledby="practice-title">
        <div className={styles.sectionHeading}>
          <div>
            <p className={styles.eyebrow}>Guided practice</p>
            <h3 id="practice-title">Apply the routine on the board</h3>
          </div>
          <span>{lesson.exercises.length} positions</span>
        </div>
        <ExerciseCard
          key={activeExercise.key}
          exercise={activeExercise}
          number={activeExerciseIndex + 1}
          connected={connected}
          savedState={progress?.lessons[lesson.key]?.exercises[activeExercise.key]?.state ?? 'not_started'}
          onResolved={onRefreshProgress}
          onSessionFailure={onSessionFailure}
        />
        <nav className={styles.exerciseNavigation} aria-label="Lesson positions">
          <button
            className={styles.secondaryButton}
            type="button"
            disabled={activeExerciseIndex === 0}
            onClick={() => setActiveExerciseIndex((current) => current - 1)}
          >
            Previous position
          </button>
          <span aria-live="polite">
            Position {activeExerciseIndex + 1} of {lesson.exercises.length}
          </span>
          <button
            className={styles.secondaryButton}
            type="button"
            disabled={activeExerciseIndex === lesson.exercises.length - 1}
            onClick={() => setActiveExerciseIndex((current) => current + 1)}
          >
            Next position
          </button>
        </nav>
      </section>

      <footer className={styles.lessonFooter}>
        <div>
          <strong>Lesson summary</strong>
          <p>Use the idea in real games only after it survives the opponent's strongest reply and your final safety check.</p>
          {completionError && <p className={styles.error} role="alert">{completionError}</p>}
        </div>
        <button
          className={styles.primaryButton}
          type="button"
          disabled={!connected || completing}
          onClick={() => void finish()}
        >
          {completing ? 'Completing…' : 'Complete lesson'}
        </button>
      </footer>
    </article>
  )
}

type ExercisePhase =
  | { kind: 'ready' }
  | { kind: 'starting' }
  | { kind: 'playing'; attempt: PuzzleAttempt }
  | {
    kind: 'done'
    attempt: PuzzleAttempt
    completion: PuzzleCompletion
    revealed: boolean
    explanation: string
  }
  | { kind: 'review'; solutionLine: string[]; explanation: string }

function ExerciseCard({
  exercise,
  number,
  connected,
  savedState,
  onResolved,
  onSessionFailure,
}: {
  exercise: Exercise
  number: number
  connected: boolean
  savedState: 'not_started' | 'in_progress' | 'solved' | 'revealed'
  onResolved: () => Promise<void>
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [phase, setPhase] = useState<ExercisePhase>({ kind: 'ready' })
  const [line, setLine] = useState<string[]>([])
  const [wrongMoves, setWrongMoves] = useState(0)
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState<{ tone: 'correct' | 'incorrect'; text: string } | null>(null)
  const [hint, setHint] = useState<{ level: number; text: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const savedResolved = savedState === 'solved' || savedState === 'revealed'
  const attempt = phase.kind === 'playing' || phase.kind === 'done' ? phase.attempt : null

  /** Shows a request failure; an ended attempt returns the exercise to Start solving. */
  const showRequestFailure = (requestError: unknown, message: string) => {
    handleFailure(requestError, onSessionFailure)
    if (requestError instanceof TrainingRequestError && requestError.code === 'attempt_finished') {
      setPhase({ kind: 'ready' })
      setError('This attempt has ended, for example because another puzzle was started. Start solving again.')
      return
    }
    setError(message)
  }

  // Solve Time starts when the server creates the attempt, so the board becomes interactive
  // only after the owner chooses to start, not while the lesson text is being read.
  const start = async () => {
    setPhase({ kind: 'starting' })
    setLine([])
    setWrongMoves(0)
    setFeedback(null)
    setHint(null)
    setError(null)
    try {
      setPhase({ kind: 'playing', attempt: await startExercise(exercise.key) })
    } catch (requestError) {
      handleFailure(requestError, onSessionFailure)
      setPhase({ kind: 'ready' })
      setError('The position could not be started. Try again.')
    }
  }

  const move = async (uci: string) => {
    // After completion or in review the board stays open for local exploration only.
    if (phase.kind === 'done' || phase.kind === 'review') return true
    if (phase.kind !== 'playing') return false
    setBusy(true)
    setError(null)
    try {
      const result = await submitExerciseMove(exercise.key, phase.attempt.attemptId, uci)
      setWrongMoves(result.wrongMoves)
      if (result.outcome === 'wrong') {
        setFeedback({ tone: 'incorrect', text: result.feedback ?? 'Not the solution. Try another move.' })
        return false
      }
      if (result.outcome === 'correct') {
        setLine((current) => [...current, uci, result.opponentReply])
        setFeedback({ tone: 'correct', text: 'Correct. Your opponent replied; find the next move.' })
        return true
      }
      setLine((current) => [...current, uci])
      setFeedback({ tone: 'correct', text: result.feedback ?? 'Solved.' })
      setPhase({
        kind: 'done',
        attempt: phase.attempt,
        completion: result.completion,
        revealed: false,
        explanation: result.explanation,
      })
      await onResolved()
      return true
    } catch (requestError) {
      if (requestError instanceof TrainingRequestError && requestError.code === 'illegal_move') {
        setError('That move is not legal in this position.')
        return false
      }
      showRequestFailure(requestError, 'That move could not be checked. Try again.')
      return false
    } finally {
      setBusy(false)
    }
  }

  const askHint = async () => {
    if (phase.kind !== 'playing') return
    const level = Math.min(2, (hint?.level ?? 0) + 1)
    setBusy(true)
    setError(null)
    try {
      setHint(await requestExerciseHint(exercise.key, phase.attempt.attemptId, level))
      await onResolved()
    } catch (requestError) {
      showRequestFailure(requestError, 'The hint could not be loaded. Try again.')
    } finally {
      setBusy(false)
    }
  }

  const reveal = async () => {
    if (phase.kind !== 'playing') return
    setBusy(true)
    setError(null)
    try {
      const answer = await revealExercise(exercise.key, phase.attempt.attemptId)
      setLine((current) => [...current, ...answer.remainingMoves])
      setFeedback(null)
      setPhase({
        kind: 'done',
        attempt: phase.attempt,
        completion: answer.completion,
        revealed: true,
        explanation: answer.explanation,
      })
      await onResolved()
    } catch (requestError) {
      showRequestFailure(requestError, 'The solution could not be shown. Try again.')
    } finally {
      setBusy(false)
    }
  }

  const review = async () => {
    setBusy(true)
    setError(null)
    try {
      const answer = await loadExerciseAnswer(exercise.key)
      setLine(answer.solutionLine)
      setFeedback(null)
      setHint(null)
      setPhase({ kind: 'review', solutionLine: answer.solutionLine, explanation: answer.explanation })
    } catch (requestError) {
      handleFailure(requestError, onSessionFailure)
      setError('The answer could not be loaded. Try again.')
    } finally {
      setBusy(false)
    }
  }

  const playing = phase.kind === 'playing'
  return (
    <article className={styles.exerciseCard} aria-labelledby={`${exercise.key}-title`}>
      <div className={styles.exerciseBoard}>
        <InteractiveChessBoard
          key={attempt?.attemptId ?? phase.kind}
          fen={exercise.fen}
          orientation={exercise.orientation}
          label={`Position ${number}: ${exercise.prompt}`}
          disabled={!connected || busy || phase.kind === 'ready' || phase.kind === 'starting'}
          solution={null}
          line={line}
          initialLastMove={attempt?.setupMove ?? null}
          onMove={move}
        />
      </div>
      <div className={styles.exerciseBody}>
        <p className={styles.eyebrow}>
          Position {number} · {difficultyLabel(exercise.difficultyTier)} · Lichess {exercise.sourceRating}
        </p>
        <h4 id={`${exercise.key}-title`}>{exercise.prompt}</h4>
        <p className={styles.statusText}>{exerciseStatus(savedState, phase.kind === 'done' && !phase.revealed)}</p>
        {attempt !== null && (
          <p className={styles.statusText}>
            Play the whole line · {attempt.ownerMoves} {attempt.ownerMoves === 1 ? 'move' : 'moves'} to find ·
            Fast-solve limit {seconds(attempt.fastSolveLimitMillis)} · Wrong Moves {wrongMoves}
          </p>
        )}
        {feedback && (
          <div className={styles.feedback} data-outcome={feedback.tone} role="status">
            <strong>{feedback.tone === 'correct' ? 'Correct' : 'Try again'}</strong>
            <p>{feedback.text}</p>
          </div>
        )}
        {hint && <p className={styles.hint} role="status"><strong>Hint {hint.level}:</strong> {hint.text}</p>}
        {phase.kind === 'done' && (
          <div className={styles.feedback} data-outcome="correct" role="status">
            <strong>{phase.revealed ? 'Solution shown' : 'Line complete'}</strong>
            <p>{phase.explanation}</p>
            <p>
              Puzzle training: {listResult(phase.completion)}{' '}
              {phase.revealed
                ? 'Solve Time is not counted.'
                : `Solve Time ${seconds(phase.completion.elapsedMillis)} of ${seconds(phase.completion.fastSolveLimitMillis)}.`}
            </p>
          </div>
        )}
        {phase.kind === 'review' && (
          <div className={styles.feedback} data-outcome="correct" role="status">
            <strong>Answer review</strong>
            <p>{phase.explanation}</p>
            <p>Reviewing the answer does not change your Training Lists.</p>
          </div>
        )}
        {(phase.kind === 'done' || phase.kind === 'review') && (
          <p className={styles.statusText}>The board stays open: keep moving pieces to explore. These moves are not saved.</p>
        )}
        {error && <p className={styles.error} role="alert">{error}</p>}
        <div className={styles.exerciseActions}>
          {playing ? (
            <>
              <button
                className={styles.secondaryButton}
                type="button"
                disabled={!connected || busy || hint?.level === 2}
                onClick={() => void askHint()}
              >
                {hint === null ? 'Hint: thinking step' : 'Hint: tactical area'}
              </button>
              <button
                className={styles.textButton}
                type="button"
                disabled={!connected || busy}
                onClick={() => void reveal()}
              >
                Show solution
              </button>
            </>
          ) : (
            <>
              <button
                className={styles.primaryButton}
                type="button"
                disabled={!connected || busy || phase.kind === 'starting'}
                onClick={() => void start()}
              >
                {phase.kind === 'starting'
                  ? 'Starting…'
                  : savedResolved || phase.kind === 'done' ? 'Solve again' : 'Start solving'}
              </button>
              {savedResolved && phase.kind !== 'review' && (
                <button
                  className={styles.textButton}
                  type="button"
                  disabled={!connected || busy}
                  onClick={() => void review()}
                >
                  Review answer
                </button>
              )}
            </>
          )}
        </div>
        <p className={styles.statusText}>
          {playing
            ? 'A hint or Show solution places this puzzle in Hard to Solve and restarts its progress there.'
            : 'Solving this position also counts as an attempt on its Lichess puzzle in your Training Lists. The timer starts when you start solving.'}
        </p>
      </div>
    </article>
  )
}

function listResult(completion: PuzzleCompletion) {
  const list = LIST_LABELS[completion.list]
  if (completion.previousList === null) return `placed in ${list}.`
  if (completion.previousList === completion.list) return `stays in ${list}.`
  return `moved from ${LIST_LABELS[completion.previousList]} to ${list}.`
}

function difficultyLabel(tier: Exercise['difficultyTier']) {
  if (tier === 'foundation') return 'Foundation'
  if (tier === 'development') return 'Development'
  return 'Stretch'
}

function lessonState(progress: Progress | null, lessonKey: string) {
  const state = progress?.lessons[lessonKey]?.state
  if (state === 'completed') return 'Completed'
  if (state === 'in_progress') return 'In progress'
  return 'Available'
}

function exerciseStatus(state: string, solvedNow: boolean) {
  if (solvedNow || state === 'solved') return 'Solved'
  if (state === 'revealed') return 'Answer revealed'
  if (state === 'in_progress') return 'In progress'
  return 'Not attempted'
}

function handleFailure(
  error: unknown,
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void,
) {
  if (error instanceof TrainingRequestError &&
      (error.code === 'not_connected' || error.code === 'session_unavailable')) {
    onSessionFailure(error.code)
  }
}
