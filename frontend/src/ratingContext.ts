export type HistorySpeed = 'blitz' | 'rapid' | 'classical'

export const HISTORY_SPEEDS: readonly HistorySpeed[] = ['blitz', 'rapid', 'classical']

const MAX_HISTORY_POINTS = 30
const MAX_GAMES = 10_000_000

export type RatingPoint = { playedAt: string; rating: number }

export type SpeedRating = {
  speed: HistorySpeed
  gamesCounted: number
  latestChange: number | null
  points: RatingPoint[]
}

export type BotGroup =
  | { type: 'lichess_ai'; aiLevel: number; wins: number; draws: number; losses: number; games: number }
  | {
      type: 'bot_rating_band'
      ratingFrom: number
      ratingTo: number
      wins: number
      draws: number
      losses: number
      games: number
    }
  | { type: 'bot_unrated'; wins: number; draws: number; losses: number; games: number }

export type BotRecord = {
  wins: number
  draws: number
  losses: number
  games: number
  lowestRating: number | null
  highestRating: number | null
  groups: BotGroup[]
}

export type RatingContext = {
  ratings: SpeedRating[]
  botRecord: BotRecord
}

export type RatingContextFailure =
  | 'not_connected'
  | 'session_unavailable'
  | 'rating_context_unavailable'

export class RatingContextRequestError extends Error {
  constructor(readonly failure: RatingContextFailure) {
    super(failure)
  }
}

export async function requestRatingContext(signal: AbortSignal): Promise<RatingContext> {
  const response = await fetch('/api/dashboard/rating-context', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    const error = await safeErrorCode(response)
    if (response.status === 401 || error === 'not_connected') {
      throw new RatingContextRequestError('not_connected')
    }
    if (error === 'session_unavailable') {
      throw new RatingContextRequestError('session_unavailable')
    }
    throw new RatingContextRequestError('rating_context_unavailable')
  }
  return parseRatingContext(await response.json() as unknown)
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

export function parseRatingContext(value: unknown): RatingContext {
  if (!isRecord(value) || !Array.isArray(value.ratings) || !isRecord(value.botRecord)) {
    throw new Error('Invalid rating context')
  }
  const ratings = value.ratings.map(parseSpeedRating)
  if (
    ratings.length !== HISTORY_SPEEDS.length ||
    ratings.some((rating, index) => rating.speed !== HISTORY_SPEEDS[index])
  ) {
    throw new Error('Invalid rating speeds')
  }
  return { ratings, botRecord: parseBotRecord(value.botRecord) }
}

function parseSpeedRating(value: unknown): SpeedRating {
  if (
    !isRecord(value) ||
    !isOneOf(value.speed, HISTORY_SPEEDS) ||
    !isInteger(value.gamesCounted, 0, MAX_GAMES) ||
    !(value.latestChange === null || isInteger(value.latestChange, -1_000, 1_000)) ||
    !Array.isArray(value.points) ||
    value.points.length > MAX_HISTORY_POINTS ||
    value.points.length > value.gamesCounted
  ) {
    throw new Error('Invalid rating history')
  }
  const points = value.points.map((point: unknown) => {
    if (
      !isRecord(point) ||
      typeof point.playedAt !== 'string' ||
      point.playedAt.length > 40 ||
      !Number.isFinite(Date.parse(point.playedAt)) ||
      !isInteger(point.rating, 0, 6_000)
    ) {
      throw new Error('Invalid rating point')
    }
    return { playedAt: point.playedAt, rating: point.rating }
  })
  return {
    speed: value.speed,
    gamesCounted: value.gamesCounted,
    latestChange: value.latestChange,
    points,
  }
}

function parseBotRecord(value: Record<string, unknown>): BotRecord {
  const tally = parseTally(value)
  if (
    !(value.lowestRating === null || isInteger(value.lowestRating, 0, 5_000)) ||
    !(value.highestRating === null || isInteger(value.highestRating, 0, 5_000)) ||
    (value.lowestRating === null) !== (value.highestRating === null) ||
    (value.lowestRating !== null && value.highestRating !== null &&
      value.lowestRating > value.highestRating) ||
    !Array.isArray(value.groups) ||
    value.groups.length > 64
  ) {
    throw new Error('Invalid bot record')
  }
  const groups = value.groups.map(parseBotGroup)
  if (groups.reduce((sum, group) => sum + group.games, 0) !== tally.games) {
    throw new Error('Bot groups do not match the record')
  }
  return {
    ...tally,
    lowestRating: value.lowestRating,
    highestRating: value.highestRating,
    groups,
  }
}

function parseBotGroup(value: unknown): BotGroup {
  if (!isRecord(value)) throw new Error('Invalid bot group')
  const tally = parseTally(value)
  if (value.type === 'lichess_ai' && isInteger(value.aiLevel, 1, 8)) {
    return { type: 'lichess_ai', aiLevel: value.aiLevel, ...tally }
  }
  if (
    value.type === 'bot_rating_band' &&
    isInteger(value.ratingFrom, 0, 5_000) &&
    isInteger(value.ratingTo, 0, 5_200) &&
    value.ratingFrom < value.ratingTo
  ) {
    return {
      type: 'bot_rating_band',
      ratingFrom: value.ratingFrom,
      ratingTo: value.ratingTo,
      ...tally,
    }
  }
  if (value.type === 'bot_unrated') {
    return { type: 'bot_unrated', ...tally }
  }
  throw new Error('Invalid bot group')
}

function parseTally(value: Record<string, unknown>) {
  if (
    !isInteger(value.wins, 0, MAX_GAMES) ||
    !isInteger(value.draws, 0, MAX_GAMES) ||
    !isInteger(value.losses, 0, MAX_GAMES) ||
    !isInteger(value.games, 0, MAX_GAMES) ||
    value.wins + value.draws + value.losses !== value.games
  ) {
    throw new Error('Invalid result tally')
  }
  return { wins: value.wins, draws: value.draws, losses: value.losses, games: value.games }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isInteger(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
}

function isOneOf<T extends string>(value: unknown, allowed: readonly T[]): value is T {
  return typeof value === 'string' && allowed.includes(value as T)
}
