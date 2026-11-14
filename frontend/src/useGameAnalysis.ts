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
 * is shown only when it finished while this view was watching it. While one runs, the current
 * state is requested once per {@link POLL_INTERVAL_MS}.
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
  const [pollTick, setPollTick] = useState(0)
  const onCompletedRef = useRef(onCompleted)
  useEffect(() => {
    onCompletedRef.current = onCompleted
  }, [onCompleted])

  /**
   * Only a lost connection affects the whole view. A temporarily unavailable session check is
   * treated like any other missed request: polls retry on the next tick and actions report it.
   */
  const sessionFailure = useCallback((error: unknown) => {
    if (error instanceof AnalysisRequestError && error.failure === 'not_connected') {
      onSessionFailure(error.failure)
      return true
    }
    return false
  }, [onSessionFailure])

  /** Applies a fresh job snapshot, finishing up when it is no longer running. */
  const receive = useCallback((current: AnalysisJob | null) => {
    setJob(current)
    if (current === null || current.state !== 'running') {
      setCanceling(false)
      if (current?.state === 'completed') onCompletedRef.current()
    }
  }, [])

  const followRunning = useCallback((signal?: AbortSignal) =>
    requestCurrentAnalysis(signal).then((current) => {
      if (current?.state === 'running') setJob(current)
    }), [])

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    followRunning(controller.signal).catch((error: unknown) => {
      if (error instanceof DOMException && error.name === 'AbortError') return
      sessionFailure(error)
    })
    return () => controller.abort()
  }, [enabled, followRunning, sessionFailure])

  const running = job?.state === 'running'
  useEffect(() => {
    if (!enabled || !running) return
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      requestCurrentAnalysis(controller.signal)
        .then(receive)
        .catch((error: unknown) => {
          if (error instanceof DOMException && error.name === 'AbortError') return
          if (sessionFailure(error)) return
        })
        .finally(() => {
          if (!controller.signal.aborted) setPollTick((tick) => tick + 1)
        })
    }, POLL_INTERVAL_MS)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [enabled, running, pollTick, receive, sessionFailure])

  const start = (gameId: string, perspective: AnalysisPerspective) => {
    setStarting(true)
    setCanceling(false)
    setStartFailure(null)
    startAnalysis(gameId, perspective)
      .then((started) => setJob(started))
      .catch((error: unknown) => {
        if (sessionFailure(error)) return
        const failure = error instanceof AnalysisRequestError ? error.failure : 'analysis_unavailable'
        setStartFailure(failure)
        // The analysis already running is shown so it can be followed or canceled.
        if (failure === 'analysis_busy') {
          followRunning().catch((followError: unknown) => sessionFailure(followError))
        }
      })
      .finally(() => setStarting(false))
  }

  const cancel = () => {
    setCanceling(true)
    cancelAnalysis()
      .then(receive)
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
