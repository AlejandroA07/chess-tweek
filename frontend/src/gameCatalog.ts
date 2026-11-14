export type CatalogGame = {
  id: string
  playedAt: string
  result: 'win' | 'draw' | 'loss'
  ownerColor: 'white' | 'black'
  ownerRating: number | null
  ownerRatingChange: number | null
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
    status: 'not_analyzed' | 'analyzed' | 'unsupported_variant'
    myMoves: AnalysisVersionSummary | null
    opponentMoves: AnalysisVersionSummary | null
  }
}

export type AnalysisVersionSummary = {
  id: number
  completedAt: string
  currentProfile: boolean
  moves: number
  inaccuracies: number
  mistakes: number
  blunders: number
}

export type CatalogPage = {
  total: number
  offset: number
  limit: number
  games: CatalogGame[]
}

export type CatalogFilters = {
  result: '' | 'win' | 'loss' | 'draw'
  color: '' | 'white' | 'black'
  opening: string
  opponent: '' | 'human' | 'lichess_ai' | 'bot' | 'anonymous'
  speed: '' | 'ultraBullet' | 'bullet' | 'blitz' | 'rapid' | 'classical' | 'correspondence'
  from: string
  to: string
  variant: '' | 'standard' | 'chess960' | 'crazyhouse' | 'antichess' | 'atomic'
    | 'horde' | 'kingOfTheHill' | 'racingKings' | 'threeCheck' | 'fromPosition'
  analysis: '' | 'eligible' | 'catalog_only'
  sort: 'newest' | 'oldest'
}

export const EMPTY_CATALOG_FILTERS: CatalogFilters = {
  result: '',
  color: '',
  opening: '',
  opponent: '',
  speed: '',
  from: '',
  to: '',
  variant: '',
  analysis: '',
  sort: 'newest',
}

export type CatalogFailure = 'not_connected' | 'session_unavailable' | 'catalog_unavailable'

export class CatalogRequestError extends Error {
  constructor(readonly failure: CatalogFailure) {
    super(failure)
  }
}

export const CATALOG_PAGE_SIZE = 50
const PAGE_SIZE = CATALOG_PAGE_SIZE

export async function requestCatalog(
  offset: number,
  signal: AbortSignal,
  filters: CatalogFilters = EMPTY_CATALOG_FILTERS,
): Promise<CatalogPage> {
  return requestCatalogPage(offset, PAGE_SIZE, signal, filters)
}

export async function requestEligibleSelectionTotal(
  signal: AbortSignal,
  filters: CatalogFilters,
): Promise<number> {
  if (filters.analysis === 'catalog_only') return 0
  const page = await requestCatalogPage(0, 1, signal, { ...filters, analysis: 'eligible' })
  return page.total
}

async function requestCatalogPage(
  offset: number,
  limit: number,
  signal: AbortSignal,
  filters: CatalogFilters,
): Promise<CatalogPage> {
  const parameters = new URLSearchParams({ offset: offset.toString(), limit: limit.toString() })
  if (filters.result) parameters.set('result', filters.result)
  if (filters.color) parameters.set('color', filters.color)
  if (filters.opening) parameters.set('opening', filters.opening)
  if (filters.opponent) parameters.set('opponent', filters.opponent)
  if (filters.speed) parameters.set('speed', filters.speed)
  if (filters.from) parameters.set('from', filters.from)
  if (filters.to) parameters.set('to', filters.to)
  if (filters.variant) parameters.set('variant', filters.variant)
  if (filters.analysis) parameters.set('analysis', filters.analysis)
  if (filters.sort === 'oldest') parameters.set('sort', filters.sort)
  const response = await fetch(`/api/games?${parameters}`, {
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
    !isNullableInteger(value.ownerRatingChange, -1_000, 1_000) ||
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
    !isOneOf(value.analysis.status, ['not_analyzed', 'analyzed', 'unsupported_variant']) ||
    (value.analysis.eligible !== (value.analysis.status !== 'unsupported_variant')) ||
    !isVersionSummary(value.analysis.myMoves) ||
    !isVersionSummary(value.analysis.opponentMoves) ||
    ((value.analysis.status === 'analyzed')
      !== (value.analysis.myMoves !== null || value.analysis.opponentMoves !== null))
  ) {
    throw new Error('Invalid game card')
  }

  return value as CatalogGame
}

function isVersionSummary(value: unknown) {
  return value === null || (
    isRecord(value) &&
    isSafeInteger(value.id, 1, Number.MAX_SAFE_INTEGER) &&
    isDate(value.completedAt) &&
    typeof value.currentProfile === 'boolean' &&
    isSafeInteger(value.moves, 0, 600) &&
    isSafeInteger(value.inaccuracies, 0, value.moves) &&
    isSafeInteger(value.mistakes, 0, value.moves) &&
    isSafeInteger(value.blunders, 0, value.moves) &&
    value.inaccuracies + value.mistakes + value.blunders <= value.moves
  )
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
