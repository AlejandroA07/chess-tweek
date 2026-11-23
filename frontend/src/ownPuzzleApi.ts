import { TACTIC_TEXT } from './coach'
import { isGameRef, type GameRef, type GameSource } from './gameRef'
import {
  ACTIVE_LISTS,
  isInteger,
  isList,
  isRecord,
  isText,
  isUci,
  parseHint,
  parseMoveResult,
  parseReveal,
  parseSession,
  PuzzleRequestError,
  read,
  send,
  type ActiveList,
  type ListCount,
  type MoveResult,
  type PracticeSession,
  type RevealResult,
  type TrainingList,
} from './puzzles'

/** The client for `/api/training/own-puzzles`. Every response is parsed strictly. */

export type OwnLabel = 'inaccuracy' | 'mistake' | 'blunder'
export type GamePhase = 'opening' | 'middlegame' | 'endgame'
export type GroupKind = 'opening' | 'phase' | 'tactic'
/** Which source games New Own Puzzles come from; `all` is both sites. */
export type SiteFilter = 'all' | GameSource
/** Which New Own Puzzle solving starts next. */
export type NewOrder = 'newest' | 'random'

export type OwnTactic = { theme: string; kind: 'allowed' | 'missed' }

/** The exact Game Review position an Own Puzzle was created from. */
export type SourceGame = GameRef & { ply: number }

export type OwnPuzzle = {
  id: number
  label: OwnLabel
  phase: GamePhase
  openingFamily: string
  openingName: string | null
  tactics: OwnTactic[]
  sourceGame: SourceGame
  gamePlayedAt: string
}

export type OwnPage = { total: number; puzzles: OwnPuzzle[] }

export type GroupCount = { key: string; puzzles: number }

export type OwnGroups = {
  total: number
  openings: GroupCount[]
  phases: GroupCount[]
  tactics: GroupCount[]
}

export type OwnOverview = {
  newPuzzles: number
  newBySource: Record<GameSource, number>
  discarded: number
  lists: ListCount[]
  groups: OwnGroups
}

/** {@code positionFen} has the owner to move; {@code previousMove} led into it, or is null. */
export type OwnAttempt = {
  attemptId: number
  puzzle: OwnPuzzle
  positionFen: string
  previousMove: string | null
  ownerMoves: number
  fastSolveLimitMillis: number
  currentList: TrainingList | null
}

export type OwnCompletion = {
  previousList: TrainingList | null
  list: TrainingList
  successes: number
  strikes: number
  wrongMoves: number
  hintUsed: boolean
  elapsedMillis: number
  fastSolveLimitMillis: number
  sourceGame: SourceGame
}

export type OwnSessionAttempt = { attempt: OwnAttempt; position: number; total: number }

export const LABEL_TEXT: Record<OwnLabel, string> = {
  inaccuracy: 'Inaccuracy',
  mistake: 'Mistake',
  blunder: 'Blunder',
}

export const PHASE_TEXT: Record<GamePhase, string> = {
  opening: 'Opening',
  middlegame: 'Middlegame',
  endgame: 'Endgame',
}

export const SITE_TEXT: Record<GameSource, string> = { lichess: 'Lichess', chesscom: 'chess.com' }

export const PAGE_SIZE = 20

/** A tactic label in the Game Review's words; a theme the interface does not know yet is spelled out. */
export function ownTacticText(tactic: OwnTactic) {
  const known = (TACTIC_TEXT as Record<string, Record<OwnTactic['kind'], string> | undefined>)[tactic.theme]
  if (known !== undefined) return known[tactic.kind]
  const name = tactic.theme.replaceAll('_', ' ')
  return `${tactic.kind === 'missed' ? 'Missed' : 'Allowed'}: ${name}`
}

/** The name of one group key in a summary. */
export function groupText(kind: GroupKind, key: string) {
  if (kind === 'phase') return PHASE_TEXT[key as GamePhase] ?? key
  // Groups count a theme for both kinds, so they name the theme rather than one kind of it.
  if (kind === 'tactic') return TACTIC_GROUP_TEXT[key] ?? capitalize(key.replaceAll('_', ' '))
  return key
}

const TACTIC_GROUP_TEXT: Record<string, string> = {
  allowed_mate: 'Allowed mate',
  missed_mate: 'Missed mate',
  back_rank: 'Back-rank mate',
  hanging_piece: 'Hanging piece',
  fork: 'Fork',
  general_tactics: 'Other tactics',
}

function capitalize(value: string) {
  return value.length === 0 ? value : value[0].toUpperCase() + value.slice(1)
}

const base = '/api/training/own-puzzles'
const LABELS = Object.keys(LABEL_TEXT) as OwnLabel[]
const PHASES = Object.keys(PHASE_TEXT) as GamePhase[]
const LISTS: TrainingList[] = [...ACTIVE_LISTS, 'mastered']
const SITES: SiteFilter[] = ['all', 'lichess', 'chesscom']
const ORDERS: NewOrder[] = ['newest', 'random']
const GROUP_KINDS: GroupKind[] = ['opening', 'phase', 'tactic']

export function isSiteFilter(value: unknown): value is SiteFilter {
  return SITES.includes(value as SiteFilter)
}

export function isNewOrder(value: unknown): value is NewOrder {
  return ORDERS.includes(value as NewOrder)
}

/** A group key the server accepts for Mastered practice. */
export function isGroupKey(kind: GroupKind, key: string) {
  if (kind === 'phase') return PHASES.includes(key as GamePhase)
  if (kind === 'tactic') return /^[a-z_]{1,40}$/.test(key)
  return isOpeningFamily(key)
}

export async function loadOverview(signal?: AbortSignal): Promise<OwnOverview> {
  const value = await read(base, signal)
  if (
    !isRecord(value) || !isInteger(value.newPuzzles, 0, 100_000_000) ||
    !isRecord(value.newBySource) || !isInteger(value.newBySource.lichess, 0, 100_000_000) ||
    !isInteger(value.newBySource.chesscom, 0, 100_000_000) ||
    !isInteger(value.discarded, 0, 100_000_000)
  ) throw new PuzzleRequestError('unavailable')
  return {
    newPuzzles: value.newPuzzles,
    newBySource: { lichess: value.newBySource.lichess, chesscom: value.newBySource.chesscom },
    discarded: value.discarded,
    lists: parseLists(value.lists),
    groups: parseGroups(value.groups),
  }
}

export async function loadNew(
  site: SiteFilter,
  offset: number,
  signal?: AbortSignal,
): Promise<OwnPage> {
  if (!isSiteFilter(site)) throw new PuzzleRequestError('unavailable')
  return parsePage(await read(`${base}/new?${pageQuery(offset)}&source=${site}`, signal))
}

export async function loadDiscarded(offset: number, signal?: AbortSignal): Promise<OwnPage> {
  return parsePage(await read(`${base}/discarded?${pageQuery(offset)}`, signal))
}

export async function loadInList(list: TrainingList, offset: number, signal?: AbortSignal): Promise<OwnPage> {
  if (!LISTS.includes(list)) throw new PuzzleRequestError('unavailable')
  return parsePage(await read(`${base}/lists/${list}?${pageQuery(offset)}`, signal))
}

export async function loadOwnListCounts(signal?: AbortSignal): Promise<ListCount[]> {
  const value = await read(`${base}/repetition`, signal)
  if (!isRecord(value)) throw new PuzzleRequestError('unavailable')
  return parseLists(value.lists)
}

export async function loadOwnMastered(signal?: AbortSignal): Promise<OwnGroups> {
  return parseGroups(await read(`${base}/repetition/mastered`, signal))
}

export async function discardOwnPuzzle(id: number): Promise<void> {
  await send(`${base}/${puzzleId(id)}/discard`)
}

export async function restoreOwnPuzzle(id: number): Promise<void> {
  await send(`${base}/${puzzleId(id)}/restore`)
}

export async function startNextNew(site: SiteFilter, order: NewOrder): Promise<OwnAttempt> {
  if (!isSiteFilter(site) || !isNewOrder(order)) throw new PuzzleRequestError('unavailable')
  return parseOwnAttempt(await (await send(`${base}/new/attempts?source=${site}&order=${order}`)).json())
}

export async function startOwnPuzzle(id: number): Promise<OwnAttempt> {
  return parseOwnAttempt(await (await send(`${base}/${puzzleId(id)}/attempts`)).json())
}

export async function submitOwnMove(attemptId: number, move: string): Promise<MoveResult<OwnCompletion>> {
  const response = await send(`${base}/attempts/${attemptPath(attemptId)}/moves`, { move })
  return parseMoveResult(await response.json(), parseOwnCompletion)
}

export async function requestOwnHint(attemptId: number): Promise<string> {
  return parseHint(await (await send(`${base}/attempts/${attemptPath(attemptId)}/hint`)).json())
}

export async function revealOwnPuzzle(attemptId: number): Promise<RevealResult<OwnCompletion>> {
  return parseReveal(
    await (await send(`${base}/attempts/${attemptPath(attemptId)}/reveal`)).json(),
    parseOwnCompletion,
  )
}

export async function startOwnListSession(list: ActiveList): Promise<PracticeSession> {
  if (!ACTIVE_LISTS.includes(list)) throw new PuzzleRequestError('unavailable')
  return parseSession(await (await send(`${base}/repetition/${list}/sessions`)).json())
}

export async function startOwnMasteredSession(kind: GroupKind, key: string): Promise<PracticeSession> {
  if (!GROUP_KINDS.includes(kind) || !isGroupKey(kind, key)) throw new PuzzleRequestError('unavailable')
  const response = await send(`${base}/repetition/mastered/${kind}/${encodeURIComponent(key)}/sessions`)
  return parseSession(await response.json())
}

export async function nextOwnSessionPuzzle(sessionId: number): Promise<OwnSessionAttempt> {
  if (!isInteger(sessionId, 1, Number.MAX_SAFE_INTEGER)) throw new PuzzleRequestError('unavailable')
  const value: unknown = await (await send(`${base}/sessions/${sessionId}/attempts`)).json()
  if (
    !isRecord(value) || !isInteger(value.total, 1, 100_000_000) ||
    !isInteger(value.position, 1, value.total)
  ) throw new PuzzleRequestError('unavailable')
  return { attempt: parseOwnAttempt(value.attempt), position: value.position, total: value.total }
}

function pageQuery(offset: number) {
  if (!isInteger(offset, 0, 100_000_000)) throw new PuzzleRequestError('unavailable')
  return `offset=${offset}&limit=${PAGE_SIZE}`
}

function puzzleId(id: number) {
  if (!isInteger(id, 1, Number.MAX_SAFE_INTEGER)) throw new PuzzleRequestError('unavailable')
  return id
}

function attemptPath(id: number) {
  if (!isInteger(id, 1, Number.MAX_SAFE_INTEGER)) throw new PuzzleRequestError('unavailable')
  return id
}

function parseLists(value: unknown): ListCount[] {
  if (
    !Array.isArray(value) || value.length !== LISTS.length ||
    !value.every((entry, index) =>
      isRecord(entry) && entry.list === LISTS[index] && isInteger(entry.puzzles, 0, 100_000_000))
  ) throw new PuzzleRequestError('unavailable')
  return value.map((entry) => ({ list: entry.list, puzzles: entry.puzzles }))
}

function parseGroups(value: unknown): OwnGroups {
  if (!isRecord(value) || !isInteger(value.total, 0, 100_000_000)) throw new PuzzleRequestError('unavailable')
  return {
    total: value.total,
    openings: parseGroupCounts(value.openings, 'opening'),
    phases: parseGroupCounts(value.phases, 'phase'),
    tactics: parseGroupCounts(value.tactics, 'tactic'),
  }
}

function parseGroupCounts(value: unknown, kind: GroupKind): GroupCount[] {
  if (!Array.isArray(value) || value.length > 5_000) throw new PuzzleRequestError('unavailable')
  return value.map((entry) => {
    if (
      !isRecord(entry) || typeof entry.key !== 'string' || !isGroupKey(kind, entry.key) ||
      !isInteger(entry.puzzles, 1, 100_000_000)
    ) throw new PuzzleRequestError('unavailable')
    return { key: entry.key, puzzles: entry.puzzles }
  })
}

function parsePage(value: unknown): OwnPage {
  if (
    !isRecord(value) || !isInteger(value.total, 0, 100_000_000) ||
    !Array.isArray(value.puzzles) || value.puzzles.length > 50
  ) throw new PuzzleRequestError('unavailable')
  return { total: value.total, puzzles: value.puzzles.map(parseOwnPuzzle) }
}

function parseOwnPuzzle(value: unknown): OwnPuzzle {
  if (
    !isRecord(value) || !isInteger(value.id, 1, Number.MAX_SAFE_INTEGER) ||
    !LABELS.includes(value.label as OwnLabel) || !PHASES.includes(value.phase as GamePhase) ||
    typeof value.openingFamily !== 'string' || !isOpeningFamily(value.openingFamily) ||
    !(value.openingName === null || value.openingName === undefined || isText(value.openingName, 240)) ||
    !Array.isArray(value.tactics) || value.tactics.length > 20 ||
    !isText(value.gamePlayedAt, 40) || Number.isNaN(Date.parse(value.gamePlayedAt))
  ) throw new PuzzleRequestError('unavailable')
  return {
    id: value.id,
    label: value.label as OwnLabel,
    phase: value.phase as GamePhase,
    openingFamily: value.openingFamily,
    openingName: typeof value.openingName === 'string' ? value.openingName : null,
    tactics: value.tactics.map(parseTactic),
    sourceGame: parseSourceGame(value.sourceGame),
    gamePlayedAt: value.gamePlayedAt,
  }
}

function parseTactic(value: unknown): OwnTactic {
  if (
    !isRecord(value) || typeof value.theme !== 'string' || !/^[a-z_]{1,40}$/.test(value.theme) ||
    (value.kind !== 'allowed' && value.kind !== 'missed')
  ) throw new PuzzleRequestError('unavailable')
  return { theme: value.theme, kind: value.kind }
}

function parseSourceGame(value: unknown): SourceGame {
  if (!isRecord(value) || !isGameRef(value.source, value.gameId) || !isInteger(value.ply, 1, 1200)) {
    throw new PuzzleRequestError('unavailable')
  }
  return { source: value.source as GameSource, id: value.gameId as string, ply: value.ply }
}

function parseOwnAttempt(value: unknown): OwnAttempt {
  if (
    !isRecord(value) || !isInteger(value.attemptId, 1, Number.MAX_SAFE_INTEGER) ||
    !isText(value.positionFen, 120) ||
    !(value.previousMove === null || value.previousMove === undefined || isUci(value.previousMove)) ||
    !isInteger(value.ownerMoves, 1, 100) ||
    !isInteger(value.fastSolveLimitMillis, 6_000, 1_000_000) ||
    !(value.currentList === null || value.currentList === undefined || isList(value.currentList))
  ) throw new PuzzleRequestError('unavailable')
  return {
    attemptId: value.attemptId,
    puzzle: parseOwnPuzzle(value.puzzle),
    positionFen: value.positionFen,
    previousMove: typeof value.previousMove === 'string' ? value.previousMove : null,
    ownerMoves: value.ownerMoves,
    fastSolveLimitMillis: value.fastSolveLimitMillis,
    currentList: isList(value.currentList) ? value.currentList : null,
  }
}

function parseOwnCompletion(value: unknown): OwnCompletion {
  if (
    !isRecord(value) ||
    !(value.previousList === null || value.previousList === undefined || isList(value.previousList)) ||
    !isList(value.list) || !isInteger(value.successes, 0, 9) || !isInteger(value.strikes, 0, 1) ||
    !isInteger(value.wrongMoves, 0, 10_000) || typeof value.hintUsed !== 'boolean' ||
    !isInteger(value.elapsedMillis, 0, Number.MAX_SAFE_INTEGER) ||
    !isInteger(value.fastSolveLimitMillis, 6_000, 1_000_000)
  ) throw new PuzzleRequestError('unavailable')
  return {
    previousList: isList(value.previousList) ? value.previousList : null,
    list: value.list,
    successes: value.successes,
    strikes: value.strikes,
    wrongMoves: value.wrongMoves,
    hintUsed: value.hintUsed,
    elapsedMillis: value.elapsedMillis,
    fastSolveLimitMillis: value.fastSolveLimitMillis,
    sourceGame: parseSourceGame(value.sourceGame),
  }
}

/** As the server validates opening group keys: trimmed, 1 to 160 characters, no controls. */
function isOpeningFamily(value: string) {
  return value.length > 0 && value.length <= 160 && value === value.trim() &&
    // eslint-disable-next-line no-control-regex
    !/[\u0000-\u001f\u007f-\u009f]/.test(value)
}
