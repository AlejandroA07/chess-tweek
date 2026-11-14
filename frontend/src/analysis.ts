export type AnalysisPerspective = 'my_moves' | 'opponent_moves' | 'both'
export type AnalysisState = 'running' | 'completed' | 'failed' | 'canceled'
export type AnalysisPass = 'starting' | 'quick' | 'deep' | 'saving'
export type AnalysisJobFailure =
  | 'engine_busy'
  | 'engine_unavailable'
  | 'engine_failed'
  | 'profile_outdated'
  | 'storage_failed'

export type AnalysisJob = {
  gameId: string
  perspective: AnalysisPerspective
  state: AnalysisState
  pass: AnalysisPass
  judged: number
  total: number
  failure: AnalysisJobFailure | null
  startedAt: string
  finishedAt: string | null
  versionIds: number[]
}

export type AnalysisRequestFailure =
  | 'not_connected'
  | 'session_unavailable'
  | 'analysis_busy'
  | 'engine_profile_missing'
  | 'analysis_not_supported'
  | 'game_not_found'
  | 'no_running_analysis'
  | 'analysis_unavailable'

export class AnalysisRequestError extends Error {
  constructor(readonly failure: AnalysisRequestFailure) {
    super(failure)
  }
}

const KNOWN_FAILURES: readonly AnalysisRequestFailure[] = [
  'analysis_busy',
  'engine_profile_missing',
  'analysis_not_supported',
  'game_not_found',
  'no_running_analysis',
  'session_unavailable',
]

export async function startAnalysis(
  gameId: string,
  perspective: AnalysisPerspective,
): Promise<AnalysisJob> {
  return send(`/api/games/${encodeURIComponent(gameId)}/analyses`, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ perspective }),
  }) as Promise<AnalysisJob>
}

export async function requestCurrentAnalysis(signal?: AbortSignal): Promise<AnalysisJob | null> {
  return send('/api/analysis', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    signal,
  })
}

export async function cancelAnalysis(): Promise<AnalysisJob> {
  return send('/api/analysis/cancel', {
    method: 'POST',
    headers: { Accept: 'application/json' },
  }) as Promise<AnalysisJob>
}

async function send(url: string, init: RequestInit): Promise<AnalysisJob | null> {
  const response = await fetch(url, { ...init, credentials: 'same-origin' })
  if (!response.ok) {
    const error = await safeErrorCode(response)
    if (response.status === 401 || error === 'not_connected') {
      throw new AnalysisRequestError('not_connected')
    }
    const known = KNOWN_FAILURES.find((failure) => failure === error)
    throw new AnalysisRequestError(known ?? 'analysis_unavailable')
  }
  const body: unknown = await response.json()
  if (!isRecord(body) || !('job' in body)) throw new Error('Invalid analysis response')
  return body.job === null ? null : parseJob(body.job)
}

async function safeErrorCode(response: Response) {
  try {
    const body: unknown = await response.json()
    return isRecord(body) && typeof body.error === 'string' && body.error.length <= 64
      ? body.error
      : null
  } catch {
    return null
  }
}

export function parseJob(value: unknown): AnalysisJob {
  if (
    !isRecord(value) ||
    typeof value.gameId !== 'string' ||
    !/^[A-Za-z0-9]{8,32}$/.test(value.gameId) ||
    !isOneOf(value.perspective, ['my_moves', 'opponent_moves', 'both']) ||
    !isOneOf(value.state, ['running', 'completed', 'failed', 'canceled']) ||
    !isOneOf(value.pass, ['starting', 'quick', 'deep', 'saving']) ||
    !isCount(value.judged) ||
    !isCount(value.total) ||
    value.judged > value.total ||
    !(value.failure === null || isOneOf(value.failure, [
      'engine_busy', 'engine_unavailable', 'engine_failed', 'profile_outdated', 'storage_failed',
    ])) ||
    (value.state === 'failed') !== (value.failure !== null) ||
    !isDate(value.startedAt) ||
    !(value.finishedAt === null || isDate(value.finishedAt)) ||
    (value.state === 'running') !== (value.finishedAt === null) ||
    !Array.isArray(value.versionIds) ||
    value.versionIds.length > 2 ||
    !value.versionIds.every((id) => typeof id === 'number' && Number.isSafeInteger(id) && id > 0)
  ) {
    throw new Error('Invalid analysis job')
  }
  return value as AnalysisJob
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 1_200
}

function isDate(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 40 && Number.isFinite(Date.parse(value))
}

function isOneOf<T extends string>(value: unknown, allowed: readonly T[]): value is T {
  return typeof value === 'string' && allowed.includes(value as T)
}
