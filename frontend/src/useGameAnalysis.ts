import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AnalysisRequestError,
  cancelAnalysis,
  requestCurrentAnalysis,
  startAnalysis,
  type AnalysisJob,
  type AnalysisPerspective,
  type AnalysisRequestFailure,
} from './analysis'

export const POLL_INTERVAL_MS = 1_000

export type GameAnalysisControls = {
  /** The running analysis, or one that finished while this view was open. */
  job: AnalysisJob | null
  starting: boolean
  canceling: boolean
  startFailure: AnalysisRequestFailure | null
  start: (gameId: string, perspective: AnalysisPerspective) => void
  cancel: () => void
  dismiss: () => void
}

/**
 * Tracks the single local analysis. Only a running analysis is picked up on load; a finished one
 * is shown only when it finished while this view was watching it.
 */
export function useGameAnalysis({
  enabled,
  onCompleted,
  onSessionFailure,
}: {
  enabled: boolean
  onCompleted: () => void
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}): GameAnalysisControls {
  const [job, setJob] = useState<AnalysisJob | null>(null)
  const [starting, setStarting] = useState(false)
  const [canceling, setCanceling] = useState(false)
  const [startFailure, setStartFailure] = useState<AnalysisRequestFailure | null>(null)
  const onCompletedRef = useRef(onCompleted)
  useEffect(() => {
    onCompletedRef.current = onCompleted
  }, [onCompleted])

  const sessionFailure = useCallback((error: unknown) => {
    if (error instanceof AnalysisRequestError
      && (error.failure === 'not_connected' || error.failure === 'session_unavailable')) {
      onSessionFailure(error.failure)
      return true
    }
    return false
  }, [onSessionFailure])

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    requestCurrentAnalysis(controller.signal)
      .then((current) => {
        if (current?.state === 'running') setJob(current)
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        sessionFailure(error)
      })
    return () => controller.abort()
  }, [enabled, sessionFailure])

  const running = job?.state === 'running'
  useEffect(() => {
    if (!enabled || !running) return
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      requestCurrentAnalysis(controller.signal)
        .then((current) => {
          if (current === null) {
            setJob(null)
            return
          }
          setJob(current)
          if (current.state !== 'running') {
            setCanceling(false)
            if (current.state === 'completed') onCompletedRef.current()
          }
        })
        .catch((error: unknown) => {
          if (error instanceof DOMException && error.name === 'AbortError') return
          if (sessionFailure(error)) return
          // A missed poll is retried on the next tick by touching the job.
          setJob((previous) => previous && { ...previous })
        })
    }, POLL_INTERVAL_MS)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [enabled, running, job, sessionFailure])

  const start = (gameId: string, perspective: AnalysisPerspective) => {
    setStarting(true)
    setStartFailure(null)
    startAnalysis(gameId, perspective)
      .then((started) => setJob(started))
      .catch((error: unknown) => {
        if (sessionFailure(error)) return
        setStartFailure(error instanceof AnalysisRequestError ? error.failure : 'analysis_unavailable')
      })
      .finally(() => setStarting(false))
  }

  const cancel = () => {
    setCanceling(true)
    cancelAnalysis()
      .then((current) => setJob(current))
      .catch((error: unknown) => {
        if (sessionFailure(error)) return
        setCanceling(false)
      })
  }

  const dismiss = () => {
    setJob(null)
    setStartFailure(null)
  }

  return { job, starting, canceling, startFailure, start, cancel, dismiss }
}
