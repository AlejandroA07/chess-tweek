import { useEffect, useState } from 'react'
import styles from './TrainingShortcut.module.css'
import { loadProgress, TrainingRequestError, type Progress } from './training'

export function TrainingShortcut({
  connected,
  onSessionFailure,
}: {
  connected: boolean
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [progress, setProgress] = useState<Progress | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!connected) {
      setProgress(null)
      setFailed(false)
      return
    }
    const controller = new AbortController()
    void loadProgress(controller.signal)
      .then((loaded) => {
        setProgress(loaded)
        setFailed(false)
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (error instanceof TrainingRequestError &&
            (error.code === 'not_connected' || error.code === 'session_unavailable')) {
          onSessionFailure(error.code)
        }
        setFailed(true)
      })
    return () => controller.abort()
  }, [connected, onSessionFailure])

  const started = progress !== null && Object.values(progress.lessons)
    .some((lesson) => lesson.state !== 'not_started')
  return (
    <section className={styles.shortcut} aria-labelledby="training-shortcut-title">
      <div>
        <p>Guided training</p>
        <h2 id="training-shortcut-title">Grandmaster Thinking Method</h2>
        <span>
          {!connected
            ? 'Explore the original course offline; connect to save progress.'
            : failed
              ? 'The course is available, but saved progress could not be loaded.'
              : progress === null
                ? 'Loading saved course progress…'
                : `${progress.completedLessons} of ${progress.totalLessons} lessons completed`}
        </span>
      </div>
      <a href="#/training">{started ? 'Continue training' : connected ? 'Start the method' : 'Explore course'}</a>
    </section>
  )
}
