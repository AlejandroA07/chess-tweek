export type EngineState =
  | 'not_checked'
  | 'ready'
  | 'not_installed'
  | 'not_executable'
  | 'unsupported_engine'
  | 'not_responding'

export type EngineStatus = {
  state: EngineState
  path: string | null
  engineName: string | null
}

export type EngineFailure = 'not_connected' | 'session_unavailable' | 'engine_busy' | 'engine_unavailable'

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
  }
}
