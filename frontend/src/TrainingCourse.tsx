import { useCallback, useEffect, useMemo, useState } from 'react'
import { InteractiveChessBoard } from './ChessBoard'
import styles from './TrainingCourse.module.css'
import {
  completeLesson,
  loadCourse,
  loadProgress,
  requestHint,
  revealExercise,
  startLesson,
  submitMove,
  TrainingRequestError,
  type AttemptResult,
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
        {lesson.exercises.map((exercise, index) => (
          <ExerciseCard
            key={exercise.key}
            exercise={exercise}
            number={index + 1}
            connected={connected}
            savedState={progress?.lessons[lesson.key]?.exercises[exercise.key]?.state ?? 'not_started'}
            onResolved={onRefreshProgress}
            onSessionFailure={onSessionFailure}
          />
        ))}
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
  const [action, setAction] = useState<'idle' | 'move' | 'hint' | 'reveal'>('idle')
  const [result, setResult] = useState<AttemptResult | null>(null)
  const [hint, setHint] = useState<{ level: number; text: string } | null>(null)
  const [solution, setSolution] = useState<string | null>(null)
  const [explanation, setExplanation] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const savedResolved = savedState === 'solved' || savedState === 'revealed'
  const resolved = savedResolved || solution !== null

  const move = async (uci: string) => {
    setAction('move')
    setError(null)
    try {
      const answer = await submitMove(exercise.key, uci)
      setResult(answer)
      if (answer.outcome === 'correct') {
        setSolution(answer.solutionMove)
        setExplanation(answer.explanation)
        await onResolved()
      }
    } catch (requestError) {
      handleFailure(requestError, onSessionFailure)
      setError('That move could not be checked. Try again.')
    } finally {
      setAction('idle')
    }
  }

  const askHint = async () => {
    const level = Math.min(2, (hint?.level ?? 0) + 1)
    setAction('hint')
    setError(null)
    try {
      setHint(await requestHint(exercise.key, level))
      await onResolved()
    } catch (requestError) {
      handleFailure(requestError, onSessionFailure)
      setError('The hint could not be loaded. Try again.')
    } finally {
      setAction('idle')
    }
  }

  const reveal = async () => {
    setAction('reveal')
    setError(null)
    try {
      const answer = await revealExercise(exercise.key)
      setSolution(answer.solutionMove)
      setExplanation(answer.explanation)
      setResult(null)
      await onResolved()
    } catch (requestError) {
      handleFailure(requestError, onSessionFailure)
      setError('The answer could not be revealed. Try again.')
    } finally {
      setAction('idle')
    }
  }

  return (
    <article className={styles.exerciseCard} aria-labelledby={`${exercise.key}-title`}>
      <div className={styles.exerciseBoard}>
        <InteractiveChessBoard
          fen={exercise.fen}
          orientation={exercise.orientation}
          label={`Position ${number}: ${exercise.prompt}`}
          disabled={!connected || action !== 'idle' || resolved}
          solution={solution}
          onMove={(uci) => void move(uci)}
        />
      </div>
      <div className={styles.exerciseBody}>
        <p className={styles.eyebrow}>Position {number}</p>
        <h4 id={`${exercise.key}-title`}>{exercise.prompt}</h4>
        <p className={styles.statusText}>{exerciseStatus(savedState, solution)}</p>
        {result && (
          <div className={styles.feedback} data-outcome={result.outcome} role="status">
            <strong>{result.outcome === 'correct' ? 'Correct' : 'Try again'}</strong>
            <p>{result.feedback}</p>
          </div>
        )}
        {hint && <p className={styles.hint} role="status"><strong>Hint {hint.level}:</strong> {hint.text}</p>}
        {explanation && (
          <div className={styles.feedback} data-outcome="correct" role="status">
            <strong>Reference move: {solution}</strong>
            <p>{explanation}</p>
          </div>
        )}
        {error && <p className={styles.error} role="alert">{error}</p>}
        <div className={styles.exerciseActions}>
          <button
            className={styles.secondaryButton}
            type="button"
            disabled={!connected || action !== 'idle' || resolved || hint?.level === 2}
            onClick={() => void askHint()}
          >
            {action === 'hint' ? 'Loading hint…' : hint === null ? 'Hint: piece' : 'Hint: destination'}
          </button>
          <button
            className={styles.textButton}
            type="button"
            disabled={!connected || action !== 'idle' || solution !== null}
            onClick={() => void reveal()}
          >
            {action === 'reveal' ? 'Revealing…' : savedResolved ? 'Review answer' : 'Reveal answer'}
          </button>
        </div>
      </div>
    </article>
  )
}

function lessonState(progress: Progress | null, lessonKey: string) {
  const state = progress?.lessons[lessonKey]?.state
  if (state === 'completed') return 'Completed'
  if (state === 'in_progress') return 'In progress'
  return 'Available'
}

function exerciseStatus(state: string, solution: string | null) {
  if (solution !== null || state === 'solved') return 'Solved'
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
