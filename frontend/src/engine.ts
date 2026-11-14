export type EngineState =
  | 'not_checked'
  | 'ready'
  | 'not_installed'
  | 'not_executable'
  | 'unsupported_engine'
  | 'not_responding'

export type EngineProfile = {
  id: number
  engineName: string
  threads: number
  hashMb: number
  multiPv: number
  benchmarkPositions: number
  benchmarkNodesPerPosition: number
  nodesPerSecond: number
  quickTargetMs: number
  deepTargetMs: number
  quickNodes: number
  deepNodes: number
  createdAt: string
}

export type EngineStatus = {
  state: EngineState
  path: string | null
  engineName: string | null
  profile: EngineProfile | null
}

export type EngineFailure =
  | 'not_connected'
  | 'session_unavailable'
  | 'engine_busy'
  | 'engine_not_ready'
  | 'benchmark_failed'
  | 'engine_unavailable'

export class EngineRequestError extends Error {
  constructor(readonly failure: EngineFailure) {
    super(failure)
  }
}

const STATES: readonly EngineState[] = [
  'not_checked',
  'ready',
  'not_installed',
  'not_executable',
  'unsupported_engine',
  'not_responding',
]

export async function requestEngineStatus(signal: AbortSignal): Promise<EngineStatus> {
  return send('/api/engine', { method: 'GET', signal })
}

export async function runEngineCheck(signal?: AbortSignal): Promise<EngineStatus> {
  return send('/api/engine/health-checks', { method: 'POST', signal })
}

export async function runEngineBenchmark(signal?: AbortSignal): Promise<EngineStatus> {
  return send('/api/engine/benchmarks', { method: 'POST', signal })
}

async function send(url: string, init: RequestInit): Promise<EngineStatus> {
  const response = await fetch(url, {
    ...init,
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    const error = await safeErrorCode(response)
    if (response.status === 401 || error === 'not_connected') {
      throw new EngineRequestError('not_connected')
    }
    if (error === 'session_unavailable') throw new EngineRequestError('session_unavailable')
    if (error === 'engine_busy') throw new EngineRequestError('engine_busy')
    if (error === 'engine_not_ready') throw new EngineRequestError('engine_not_ready')
    if (error === 'benchmark_failed') throw new EngineRequestError('benchmark_failed')
    throw new EngineRequestError('engine_unavailable')
  }
  return parseEngineStatus(await response.json() as unknown)
}

async function safeErrorCode(response: Response) {
  try {
    const body: unknown = await response.json()
    return typeof body === 'object' && body !== null && 'error' in body &&
      typeof body.error === 'string' && body.error.length <= 64
      ? body.error
      : null
  } catch {
    return null
  }
}

export function parseEngineStatus(value: unknown): EngineStatus {
  if (typeof value !== 'object' || value === null) throw new Error('Invalid engine status')
  const record = value as Record<string, unknown>
  if (
    typeof record.state !== 'string' ||
    !STATES.includes(record.state as EngineState) ||
    !(record.path === null || (typeof record.path === 'string' && record.path.length <= 1024)) ||
    !(record.engineName === null ||
      (typeof record.engineName === 'string' && /^Stockfish [A-Za-z0-9 ._+-]{1,48}$/.test(record.engineName))) ||
    (record.state === 'ready') !== (record.engineName !== null)
  ) {
    throw new Error('Invalid engine status')
  }
  return {
    state: record.state as EngineState,
    path: record.path as string | null,
    engineName: record.engineName as string | null,
    profile: record.profile === null || record.profile === undefined ? null : parseProfile(record.profile),
  }
}

const ENGINE_NAME = /^Stockfish [A-Za-z0-9 ._+-]{1,48}$/

function parseProfile(value: unknown): EngineProfile {
  if (typeof value !== 'object' || value === null) throw new Error('Invalid engine profile')
  const record = value as Record<string, unknown>
  const integer = (field: string, min: number, max: number) => {
    const number = record[field]
    if (typeof number !== 'number' || !Number.isSafeInteger(number) || number < min || number > max) {
      throw new Error('Invalid engine profile')
    }
    return number
  }
  if (
    typeof record.engineName !== 'string' ||
    !ENGINE_NAME.test(record.engineName) ||
    typeof record.createdAt !== 'string' ||
    record.createdAt.length > 40 ||
    !Number.isFinite(Date.parse(record.createdAt))
  ) {
    throw new Error('Invalid engine profile')
  }
  const profile: EngineProfile = {
    id: integer('id', 1, Number.MAX_SAFE_INTEGER),
    engineName: record.engineName,
    threads: integer('threads', 1, 64),
    hashMb: integer('hashMb', 16, 4096),
    multiPv: integer('multiPv', 1, 10),
    benchmarkPositions: integer('benchmarkPositions', 1, 64),
    benchmarkNodesPerPosition: integer('benchmarkNodesPerPosition', 1_000, 100_000_000),
    nodesPerSecond: integer('nodesPerSecond', 10_000, 1_000_000_000),
    quickTargetMs: integer('quickTargetMs', 1, 600_000),
    deepTargetMs: integer('deepTargetMs', 1, 600_000),
    quickNodes: integer('quickNodes', 10_000, 1_000_000_000),
    deepNodes: integer('deepNodes', 10_000, 1_000_000_000),
    createdAt: record.createdAt,
  }
  if (profile.deepNodes < profile.quickNodes) throw new Error('Invalid engine profile')
  return profile
}
