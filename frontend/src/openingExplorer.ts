import { isGameRef, type GameRef } from './gameRef'

/** The client for `/api/explorer`. Every response is parsed strictly. */

export const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'
export const NOTE_LIMIT = 4000

export type Database = 'masters' | 'lichess' | 'mine'
export type Speed = 'ultraBullet' | 'bullet' | 'blitz' | 'rapid' | 'classical' | 'correspondence'
export const SPEEDS: readonly Speed[] = ['ultraBullet', 'bullet', 'blitz', 'rapid', 'classical', 'correspondence']
export const SPEED_LABELS: Record<Speed, string> = {
  ultraBullet: 'UltraBullet', bullet: 'Bullet', blitz: 'Blitz', rapid: 'Rapid',
  classical: 'Classical', correspondence: 'Correspondence',
}
/** The Lichess explorer's rating groups; each runs from its value to the next one. */
export const RATING_GROUPS = [0, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2500] as const
export type RatingGroup = (typeof RATING_GROUPS)[number]

export type Results = { white: number; draws: number; black: number }
export type Opening = { eco: string; name: string }
export type ExplorerMove = {
  uci: string
  san: string
  averageRating: number | null
  results: Results
}
export type Player = { name: string; rating: number | null; title: string | null }
export type TopGame = {
  id: string
  uci: string
  white: Player
  black: Player
  winner: 'white' | 'black' | null
  year: number | null
}
export type LichessStatistics = {
  opening: Opening | null
  results: Results
  moves: ExplorerMove[]
  topGames: TopGame[]
  fetchedAt: string
  stale: boolean
}
export type RecentGame = {
  game: GameRef
  playedAt: string
  ownerColor: 'white' | 'black'
  ownerResult: 'win' | 'draw' | 'loss'
  opponentName: string
  opponentRating: number | null
  speed: Speed
  nextSan: string | null
}
export type MyGamesStatistics = { results: Results; moves: ExplorerMove[]; recentGames: RecentGame[] }

export type LineStep = { uci: string; san: string; fen: string }
export type Line = { start: string; moves: LineStep[]; fen: string }

export type StarterNote = { opening: string; title: string; moves: string[]; text: string }
export type Note = {
  source: 'starter' | 'owner'
  opening: string | null
  title: string | null
  text: string
  starter: StarterNote | null
  updatedAt: string | null
}
export type NoteEntry = {
  source: 'starter' | 'owner'
  opening: string | null
  title: string | null
  moves: string[]
  uciMoves: string[]
  fen: string
  excerpt: string
}

export type ExplorerFailure =
  | { kind: 'not_connected' }
  | { kind: 'session_unavailable' }
  | { kind: 'rate_limited'; retryAfterSeconds: number }
  | { kind: 'lichess_unavailable' }
  | { kind: 'invalid' }
  | { kind: 'unavailable' }

export class ExplorerRequestError extends Error {
  constructor(readonly failure: ExplorerFailure) {
    super(failure.kind)
  }
}

export type MyGamesFilter = { color: '' | 'white' | 'black'; speed: '' | Speed; source: '' | 'lichess' | 'chesscom' }

export async function fetchLine(from: string | null, moves: readonly string[], signal?: AbortSignal): Promise<Line> {
  const query = new URLSearchParams()
  if (from !== null) query.set('from', from)
  if (moves.length > 0) query.set('moves', moves.join(','))
  return parseLine(await read(`/api/explorer/line?${query}`, signal))
}

export async function fetchMasters(fen: string, signal?: AbortSignal): Promise<LichessStatistics> {
  return parseLichess(await read(`/api/explorer/masters?${new URLSearchParams({ fen })}`, signal))
}

export async function fetchLichess(
  fen: string, speeds: readonly Speed[], ratings: readonly RatingGroup[], signal?: AbortSignal,
): Promise<LichessStatistics> {
  const query = new URLSearchParams({ fen })
  if (speeds.length > 0) query.set('speeds', speeds.join(','))
  if (ratings.length > 0) query.set('ratings', ratings.join(','))
  return parseLichess(await read(`/api/explorer/lichess?${query}`, signal))
}

export async function fetchMyGames(fen: string, filter: MyGamesFilter, signal?: AbortSignal): Promise<MyGamesStatistics> {
  const query = new URLSearchParams({ fen })
  if (filter.color !== '') query.set('color', filter.color)
  if (filter.speed !== '') query.set('speed', filter.speed)
  if (filter.source !== '') query.set('source', filter.source)
  return parseMyGames(await read(`/api/explorer/my-games?${query}`, signal))
}

export async function fetchNote(fen: string, signal?: AbortSignal): Promise<Note | null> {
  const body = await read(`/api/explorer/notes?${new URLSearchParams({ fen })}`, signal)
  if (!isRecord(body)) throw invalid()
  return body.note === null ? null : parseNote(body.note)
}

export async function saveNote(fen: string, text: string): Promise<void> {
  await write('PUT', '/api/explorer/notes', { fen, text })
}

export async function resetNote(fen: string): Promise<void> {
  await write('DELETE', `/api/explorer/notes?${new URLSearchParams({ fen })}`)
}

export async function fetchNoteIndex(signal?: AbortSignal): Promise<NoteEntry[]> {
  const body = await read('/api/explorer/notes/index', signal)
  if (!isRecord(body) || !Array.isArray(body.entries) || body.entries.length > 2000) throw invalid()
  return body.entries.map(parseEntry)
}

/** The share of games, rounded to whole percent, as text. */
export function percent(part: number, total: number): string {
  return total === 0 ? '0%' : `${Math.round((part / total) * 100)}%`
}

export function games(results: Results): number {
  return results.white + results.draws + results.black
}

async function read(url: string, signal?: AbortSignal): Promise<unknown> {
  const response = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' }, signal })
  await requireResponse(response)
  return response.json()
}

async function write(method: 'PUT' | 'DELETE', url: string, body?: unknown): Promise<void> {
  const response = await fetch(url, {
    method,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  await requireResponse(response)
}

async function requireResponse(response: Response) {
  if (response.ok) return
  if (response.status === 401) throw new ExplorerRequestError({ kind: 'not_connected' })
  let code: unknown = null
  try {
    const body: unknown = await response.json()
    code = isRecord(body) ? body.error : null
  } catch {
    code = null
  }
  if (code === 'session_unavailable') throw new ExplorerRequestError({ kind: 'session_unavailable' })
  if (response.status === 429) {
    const seconds = Number(response.headers.get('Retry-After'))
    throw new ExplorerRequestError({
      kind: 'rate_limited',
      retryAfterSeconds: Number.isInteger(seconds) && seconds > 0 && seconds <= 86_400 ? seconds : 60,
    })
  }
  if (code === 'lichess_unavailable' || code === 'invalid_lichess_response' || code === 'lichess_explorer_refused') {
    throw new ExplorerRequestError({ kind: 'lichess_unavailable' })
  }
  if (response.status === 400) throw new ExplorerRequestError({ kind: 'invalid' })
  throw new ExplorerRequestError({ kind: 'unavailable' })
}

function invalid() {
  return new ExplorerRequestError({ kind: 'unavailable' })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.length <= maximum
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

const UCI = /^[a-h][1-8][a-h][1-8][qrbn]?$/
const SAN = /^(O-O(-O)?|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?)[+#]?$/
const FEN = /^[1-8pnbrqkPNBRQK/]{15,71} [wb] (-|K?Q?k?q?) (-|[a-h][36]) \d{1,3} \d{1,4}$/

function parseResults(value: unknown): Results {
  if (!isRecord(value) || !isCount(value.white) || !isCount(value.draws) || !isCount(value.black)) throw invalid()
  return { white: value.white, draws: value.draws, black: value.black }
}

function parseOpening(value: unknown): Opening | null {
  if (value === null || value === undefined) return null
  if (!isRecord(value) || !isText(value.eco, 3) || !isText(value.name, 160)) throw invalid()
  return { eco: value.eco, name: value.name }
}

function parseMove(value: unknown): ExplorerMove {
  if (!isRecord(value) || typeof value.uci !== 'string' || !UCI.test(value.uci) ||
      typeof value.san !== 'string' || !SAN.test(value.san)) throw invalid()
  const rating = value.averageRating
  if (rating !== null && rating !== undefined && !isCount(rating)) throw invalid()
  return { uci: value.uci, san: value.san, averageRating: typeof rating === 'number' ? rating : null, results: parseResults(value.results) }
}

function parsePlayer(value: unknown): Player {
  if (!isRecord(value) || !isText(value.name, 80) ||
      (value.rating !== null && !isCount(value.rating)) ||
      (value.title !== null && !isText(value.title, 4))) throw invalid()
  return { name: value.name, rating: value.rating as number | null, title: value.title as string | null }
}

function parseTopGame(value: unknown): TopGame {
  if (!isRecord(value) || !isText(value.id, 12) || typeof value.uci !== 'string' || !UCI.test(value.uci) ||
      (value.winner !== null && value.winner !== 'white' && value.winner !== 'black') ||
      (value.year !== null && !isCount(value.year))) throw invalid()
  return {
    id: value.id, uci: value.uci, white: parsePlayer(value.white), black: parsePlayer(value.black),
    winner: value.winner as TopGame['winner'], year: value.year as number | null,
  }
}

function parseList<T>(value: unknown, parse: (item: unknown) => T, maximum = 64): T[] {
  if (!Array.isArray(value) || value.length > maximum) throw invalid()
  return value.map(parse)
}

export function parseLichess(value: unknown): LichessStatistics {
  if (!isRecord(value) || !isText(value.fetchedAt, 40) || typeof value.stale !== 'boolean') throw invalid()
  return {
    opening: parseOpening(value.opening),
    results: parseResults(value.results),
    moves: parseList(value.moves, parseMove),
    topGames: parseList(value.topGames, parseTopGame),
    fetchedAt: value.fetchedAt,
    stale: value.stale,
  }
}

function parseRecent(value: unknown): RecentGame {
  if (!isRecord(value) || !isGameRef(value.source, value.id) || !isText(value.playedAt, 40) ||
      (value.ownerColor !== 'white' && value.ownerColor !== 'black') ||
      (value.ownerResult !== 'win' && value.ownerResult !== 'draw' && value.ownerResult !== 'loss') ||
      !isText(value.opponentName, 64) || (value.opponentRating !== null && !isCount(value.opponentRating)) ||
      !SPEEDS.includes(value.speed as Speed) ||
      (value.nextSan !== null && (typeof value.nextSan !== 'string' || !SAN.test(value.nextSan)))) throw invalid()
  return {
    game: { source: value.source, id: value.id } as GameRef,
    playedAt: value.playedAt,
    ownerColor: value.ownerColor,
    ownerResult: value.ownerResult,
    opponentName: value.opponentName,
    opponentRating: value.opponentRating as number | null,
    speed: value.speed as Speed,
    nextSan: value.nextSan as string | null,
  }
}

export function parseMyGames(value: unknown): MyGamesStatistics {
  if (!isRecord(value)) throw invalid()
  return {
    results: parseResults(value.results),
    moves: parseList(value.moves, (item) => {
      if (!isRecord(item)) throw invalid()
      return parseMove({ ...item, averageRating: null })
    }, 400),
    recentGames: parseList(value.recentGames, parseRecent, 8),
  }
}

function parseLine(value: unknown): Line {
  if (!isRecord(value) || typeof value.start !== 'string' || !FEN.test(value.start) ||
      typeof value.fen !== 'string' || !FEN.test(value.fen)) throw invalid()
  return {
    start: value.start,
    fen: value.fen,
    moves: parseList(value.moves, (item) => {
      if (!isRecord(item) || typeof item.uci !== 'string' || !UCI.test(item.uci) ||
          typeof item.san !== 'string' || !SAN.test(item.san) ||
          typeof item.fen !== 'string' || !FEN.test(item.fen)) throw invalid()
      return { uci: item.uci, san: item.san, fen: item.fen }
    }, 200),
  }
}

function parseStarter(value: unknown): StarterNote | null {
  if (value === null) return null
  if (!isRecord(value) || !isText(value.opening, 80) || !isText(value.title, 80) ||
      !isText(value.text, NOTE_LIMIT) || !Array.isArray(value.moves) || value.moves.length > 40 ||
      !value.moves.every((move) => typeof move === 'string' && SAN.test(move))) throw invalid()
  return { opening: value.opening, title: value.title, moves: value.moves as string[], text: value.text }
}

function parseNote(value: unknown): Note {
  if (!isRecord(value) || (value.source !== 'starter' && value.source !== 'owner') ||
      (value.opening !== null && !isText(value.opening, 80)) || (value.title !== null && !isText(value.title, 80)) ||
      !isText(value.text, NOTE_LIMIT) || (value.updatedAt !== null && !isText(value.updatedAt, 40))) throw invalid()
  return {
    source: value.source,
    opening: value.opening as string | null,
    title: value.title as string | null,
    text: value.text,
    starter: parseStarter(value.starter),
    updatedAt: value.updatedAt as string | null,
  }
}

function parseEntry(value: unknown): NoteEntry {
  if (!isRecord(value) || (value.source !== 'starter' && value.source !== 'owner') ||
      (value.opening !== null && !isText(value.opening, 80)) || (value.title !== null && !isText(value.title, 80)) ||
      !Array.isArray(value.moves) || value.moves.length > 40 ||
      !value.moves.every((move) => typeof move === 'string' && SAN.test(move)) ||
      !Array.isArray(value.uciMoves) || value.uciMoves.length !== value.moves.length ||
      !value.uciMoves.every((move) => typeof move === 'string' && UCI.test(move)) ||
      typeof value.fen !== 'string' || !FEN.test(value.fen) || !isText(value.excerpt, 200)) throw invalid()
  return {
    source: value.source, opening: value.opening as string | null, title: value.title as string | null,
    moves: value.moves as string[], uciMoves: value.uciMoves as string[], fen: value.fen, excerpt: value.excerpt,
  }
}

/** The explorer route: an optional start FEN and a line of UCI moves from it. */
export type ExplorerRoute = { from: string | null; moves: string[] }

export function explorerHref(route: ExplorerRoute): string {
  let href = '#/openings/explore'
  if (route.from !== null && route.from !== START_FEN) href += `/from/${encodeURIComponent(route.from)}`
  if (route.moves.length > 0) href += `/line/${route.moves.join(',')}`
  return href
}

/** Parses `#/openings/explore[/from/{fen}][/line/{uci,...}]`; null for any other hash. */
export function parseExplorerRoute(hash: string): ExplorerRoute | null {
  const match = /^#\/openings\/explore(?:\/from\/([^/]+))?(?:\/line\/([a-h1-8qrbn,]+))?$/.exec(hash)
  if (match === null) return null
  let from: string | null = null
  if (match[1] !== undefined) {
    try {
      from = decodeURIComponent(match[1])
    } catch {
      return null
    }
    if (!FEN.test(from)) return null
  }
  const moves = match[2] === undefined ? [] : match[2].split(',')
  if (moves.length > 200 || moves.some((move) => !UCI.test(move))) return null
  return { from, moves }
}
