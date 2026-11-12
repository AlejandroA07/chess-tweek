export type GameRetrievalResult = {
  requested: number
  received: number
  rejected: number
  created: number
  updated: number
  unchanged: number
  before: string | null
}

export type GameRetrievalFailure =
  | 'not_connected'
  | 'rate_limited'
  | 'busy'
  | 'unavailable'

export class GameRetrievalError extends Error {
  constructor(
    readonly failure: GameRetrievalFailure,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(failure)
  }
}

export async function retrieveGames(
  count: number,
  before: string | null,
  signal: AbortSignal,
): Promise<GameRetrievalResult> {
  const response = await fetch('/api/lichess/games/retrievals', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ count, before }),
    signal,
  })
  if (!response.ok) {
    const code = await safeErrorCode(response)
    if (response.status === 401 || code === 'not_connected') {
      throw new GameRetrievalError('not_connected')
    }
    if (response.status === 429 || code === 'lichess_rate_limited') {
      throw new GameRetrievalError('rate_limited', retryDelay(response))
    }
    if (response.status === 409 || code === 'retrieval_already_running') {
      throw new GameRetrievalError('busy')
    }
    throw new GameRetrievalError('unavailable')
  }

  const body: unknown = await response.json()
  if (
    !isRecord(body) ||
    !isInteger(body.requested, 1, 50) ||
    !isInteger(body.received, 0, body.requested) ||
    !isInteger(body.rejected, 0, body.requested) ||
    !isInteger(body.created, 0, body.received) ||
    !isInteger(body.updated, 0, body.received) ||
    !isInteger(body.unchanged, 0, body.received) ||
    body.received + body.rejected > body.requested ||
    body.created + body.updated + body.unchanged !== body.received ||
    !isBeforeDate(body.before)
  ) {
    throw new Error('Invalid retrieval response')
  }

  return body as GameRetrievalResult
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

function retryDelay(response: Response) {
  const value = response.headers.get('Retry-After')
  if (value === null || !/^\d{1,6}$/.test(value)) return 60
  const seconds = Number(value)
  return Number.isSafeInteger(seconds) && seconds >= 1 && seconds <= 86_400 ? seconds : 60
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isInteger(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
}

function isBeforeDate(value: unknown): value is string | null {
  if (value === null) return true
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const timestamp = Date.parse(`${value}T00:00:00Z`)
  return !Number.isNaN(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === value
}
