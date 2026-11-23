import { useEffect, useState } from 'react'
import { reportSessionFailure, type SessionFailure } from './PuzzlePlayer'
import styles from './PuzzleTraining.module.css'
import type { ActiveList } from './puzzles'

/** Views shared by the normal-Puzzle and Own Puzzle screens. */

export type LoadState = 'loading' | 'ready' | 'failed'

/** The hash segment of each list a Practice Session covers. */
export const LIST_SLUGS: Record<ActiveList, string> = {
  hard_to_solve: 'hard-to-solve',
  wrong_moves: 'wrong-moves',
  worth_repeating: 'worth-repeating',
  retiring: 'retiring',
}

/** What it takes to leave each active list, in the owner-approved rules' terms. */
export const LIST_RULES: Record<ActiveList, string> = {
  hard_to_solve:
    'Ten Successful Repetitions in a row move a puzzle to Wrong Moves. A solution with at most one Wrong Move counts; two Wrong Moves or a hint keep it here and restart the count.',
  wrong_moves:
    'Ten clean solutions in a row move a puzzle to Worth Repeating. One Wrong Move restarts the count; two Wrong Moves or a hint move it to Hard to Solve.',
  worth_repeating:
    'Ten clean solutions in a row within the fast-solve limit move a puzzle to Retiring. A slow solve restarts the count. One Wrong Move moves it to Wrong Moves; two Wrong Moves or a hint move it to Hard to Solve.',
  retiring:
    'Five clean solutions within the fast-solve limit move a puzzle to Mastered. The second slow solve moves it back to Worth Repeating. One Wrong Move moves it to Wrong Moves; two Wrong Moves or a hint move it to Hard to Solve.',
}

/** Loads one read-only view, reporting a revoked session; {@code reload} retries it. */
export function useLoaded<T>(
  load: (signal: AbortSignal) => Promise<T>,
  onSessionFailure: (failure: SessionFailure) => void,
) {
  const [value, setValue] = useState<T | null>(null)
  const [state, setState] = useState<LoadState>('loading')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setState('loading')
    load(controller.signal).then((loaded) => {
      setValue(loaded)
      setState('ready')
    }, (error: unknown) => {
      if (error instanceof DOMException && error.name === 'AbortError') return
      reportSessionFailure(error, onSessionFailure)
      setState('failed')
    })
    return () => controller.abort()
  }, [load, onSessionFailure, attempt])
  return { value, state, reload: () => setAttempt((current) => current + 1) }
}

export function ListCard({
  id,
  title,
  puzzles,
  text,
  href,
  action,
  empty,
}: {
  id: string
  title: string
  puzzles: number
  text: string
  href: string
  action: string
  empty: string
}) {
  return (
    <section className={styles.module} aria-labelledby={`${id}-title`}>
      <p className={styles.eyebrow}>Training List</p>
      <h3 id={`${id}-title`}>
        {title} <span className={styles.count}>{formatCount(puzzles)}</span>
      </h3>
      <p>{text}</p>
      {puzzles === 0 ? <p className={styles.muted}>{empty}</p> : <a className={styles.primaryLink} href={href}>{action}</a>}
    </section>
  )
}

export function formatCount(value: number) {
  return value.toLocaleString('en-US')
}
