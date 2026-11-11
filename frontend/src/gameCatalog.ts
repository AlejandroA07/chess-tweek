export type CatalogGame = {
  id: string
  playedAt: string
  result: 'win' | 'draw' | 'loss'
  ownerColor: 'white' | 'black'
  ownerRating: number | null
  opponent: {
    type: 'human' | 'bot' | 'lichess_ai' | 'anonymous'
    name: string
    rating: number | null
    title: string | null
    aiLevel: number | null
  }
  speed: string
  rated: boolean
  variant: string
  timeControl: {
    initialSeconds: number | null
    incrementSeconds: number | null
    daysPerTurn: number | null
    label: string
  }
  opening: { eco: string; name: string } | null
  analysis: {
    eligible: boolean
    status: 'not_analyzed' | 'unsupported_variant'
  }
}

export type CatalogPage = {
  total: number
  offset: number
  limit: number
  games: CatalogGame[]
}

export type CatalogFailure = 'not_connected' | 'session_unavailable' | 'catalog_unavailable'

export class CatalogRequestError extends Error {
  constructor(readonly failure: CatalogFailure) {
    super(failure)
  }
}

const PAGE_SIZE = 50

export async function requestCatalog(offset: number, signal: AbortSignal): Promise<CatalogPage> {
  const response = await fetch(`/api/games?offset=${offset}&limit=${PAGE_SIZE}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    const error = await safeErrorCode(response)
    if (response.status === 401 || error === 'not_connected') {
      throw new CatalogRequestError('not_connected')
    }
    if (error === 'session_unavailable') {
      throw new CatalogRequestError('session_unavailable')
    }
    throw new CatalogRequestError('catalog_unavailable')
  }
  return parseCatalog(await response.json() as unknown)
}

async function safeErrorCode(response: Response) {
  try {
    const body: unknown = await response.json()
    return isRecord(body) && isBoundedText(body.error, 64) ? body.error : null
  } catch {
    return null
  }
}

function parseCatalog(value: unknown): CatalogPage {
  if (
    !isRecord(value) ||
    !isSafeInteger(value.total, 0, 1_000_000) ||
    !isSafeInteger(value.offset, 0, 100_000) ||
    !isSafeInteger(value.limit, 1, PAGE_SIZE) ||
    !Array.isArray(value.games)
  ) {
    throw new Error('Invalid catalog response')
  }
  const games = value.games.map(parseGame)
  if (
    value.total < games.length ||
    games.length > value.limit ||
    value.offset + games.length > value.total
  ) {
    throw new Error('Invalid catalog response')
  }
  return { total: value.total, offset: value.offset, limit: value.limit, games }
}

function parseGame(value: unknown): CatalogGame {
  if (
    !isRecord(value) ||
    !isBoundedText(value.id, 32) ||
    !isDate(value.playedAt) ||
    !isOneOf(value.result, ['win', 'draw', 'loss']) ||
    !isOneOf(value.ownerColor, ['white', 'black']) ||
    !isNullableInteger(value.ownerRating, 0, 5_000) ||
    !isRecord(value.opponent) ||
    !isOneOf(value.opponent.type, ['human', 'bot', 'lichess_ai', 'anonymous']) ||
    !isBoundedText(value.opponent.name, 64) ||
    !isNullableInteger(value.opponent.rating, 0, 5_000) ||
    !isNullableText(value.opponent.title, 3) ||
    !isNullableInteger(value.opponent.aiLevel, 1, 8) ||
    !isBoundedText(value.speed, 32) ||
    typeof value.rated !== 'boolean' ||
    !isBoundedText(value.variant, 32) ||
    !isRecord(value.timeControl) ||
    !isNullableInteger(value.timeControl.initialSeconds, 0, 604_800) ||
    !isNullableInteger(value.timeControl.incrementSeconds, 0, 86_400) ||
    !isNullableInteger(value.timeControl.daysPerTurn, 1, 30) ||
    !isBoundedText(value.timeControl.label, 32) ||
    !isOpening(value.opening) ||
    !isRecord(value.analysis) ||
    typeof value.analysis.eligible !== 'boolean' ||
    !isOneOf(value.analysis.status, ['not_analyzed', 'unsupported_variant']) ||
    (value.analysis.eligible !== (value.analysis.status === 'not_analyzed'))
  ) {
    throw new Error('Invalid game card')
  }

  return value as CatalogGame
}

function isOpening(value: unknown) {
  return value === null || (
    isRecord(value) &&
    isBoundedText(value.eco, 3) &&
    isBoundedText(value.name, 160)
  )
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isBoundedText(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= max
}

function isNullableText(value: unknown, max: number): value is string | null {
  return value === null || isBoundedText(value, max)
}

function isSafeInteger(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
}

function isNullableInteger(value: unknown, min: number, max: number): value is number | null {
  return value === null || isSafeInteger(value, min, max)
}

function isDate(value: unknown): value is string {
  return isBoundedText(value, 40) && Number.isFinite(Date.parse(value))
}

function isOneOf<T extends string>(value: unknown, allowed: readonly T[]): value is T {
  return typeof value === 'string' && allowed.includes(value as T)
}
