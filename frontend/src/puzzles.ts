import { SERVER_ERROR } from './trainingErrorCodes'

export type TrainingList =
  | 'hard_to_solve'
  | 'wrong_moves'
  | 'worth_repeating'
  | 'retiring'
  | 'mastered'

/** The lists a repetition Practice Session covers; Mastered is practiced by category. */
export type ActiveList = Exclude<TrainingList, 'mastered'>

export type CategoryKind = 'theme' | 'opening-family' | 'opening-variation'

export type PuzzleCategory = {
  kind: CategoryKind
  key: string
  name: string
  group: string
  parentKey: string | null
  /** Puzzles in the category, as counted after the latest import. */
  puzzles: number
}

export type PuzzleAttempt = {
  attemptId: number
  puzzleId: number
  externalId: string
  positionFen: string
  setupMove: string
  rating: number
  ownerMoves: number
  fastSolveLimitMillis: number
  currentList: TrainingList | null
}

export type ListCount = { list: TrainingList; puzzles: number }

/** {@code categories[].puzzles} is the owner's Mastered count in that category. */
export type MasteredSummary = { total: number; categories: PuzzleCategory[] }

export type PracticeSession = { sessionId: number; list: TrainingList; total: number }

/** {@code position} is the one-based place of the puzzle in the session's shuffled order. */
export type SessionAttempt = { attempt: PuzzleAttempt; position: number; total: number }

export type PuzzleCompletion = {
  previousList: TrainingList | null
  list: TrainingList
  successes: number
  strikes: number
  wrongMoves: number
  hintUsed: boolean
  elapsedMillis: number
  fastSolveLimitMillis: number
  gameUrl: string
  themes: string[]
}

/** The result of one move; {@code C} is the completion shape of the kind of Puzzle. */
export type MoveResult<C> =
  | { outcome: 'correct'; opponentReply: string; wrongMoves: number }
  | { outcome: 'solved'; completion: C; wrongMoves: number }
  | { outcome: 'wrong'; wrongMoves: number }

export type RevealResult<C> = { remainingMoves: string[]; completion: C }

export type PuzzleMoveResult = MoveResult<PuzzleCompletion>

export type PuzzleRevealResult = RevealResult<PuzzleCompletion>

export type PuzzleFailure =
  | 'not_connected'
  | 'session_unavailable'
  | 'no_puzzle_available'
  | 'attempt_finished'
  | 'illegal_move'
  | 'session_complete'
  | 'session_not_found'
  | 'own_puzzle_not_found'
  | 'own_puzzle_classified'
  | 'own_puzzle_not_discarded'
  | 'pattern_not_found'
  | 'ladder_complete'
  | 'unavailable'

export class PuzzleRequestError extends Error {
  constructor(readonly code: PuzzleFailure) {
    super(code)
  }
}

export const LIST_LABELS: Record<TrainingList, string> = {
  hard_to_solve: 'Hard to Solve',
  wrong_moves: 'Wrong Moves',
  worth_repeating: 'Worth Repeating',
  retiring: 'Retiring',
  mastered: 'Mastered',
}

/** Rounds down, so a time under the limit never displays as the limit itself. */
export function seconds(milliseconds: number) {
  const tenths = Math.floor(milliseconds / 100)
  return tenths % 10 === 0 ? `${tenths / 10} s` : `${(tenths / 10).toFixed(1)} s`
}

export const ACTIVE_LISTS: ActiveList[] = ['hard_to_solve', 'wrong_moves', 'worth_repeating', 'retiring']

const base = '/api/training/puzzles'
const LISTS = Object.keys(LIST_LABELS) as TrainingList[]
const KINDS: CategoryKind[] = ['theme', 'opening-family', 'opening-variation']

export async function loadCategories(signal?: AbortSignal): Promise<PuzzleCategory[]> {
  const response = await fetch(`${base}/categories`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  })
  await requireResponse(response)
  const value: unknown = await response.json()
  if (!isRecord(value) || !Array.isArray(value.categories) || value.categories.length > 25_000) {
    throw new PuzzleRequestError('unavailable')
  }
  return value.categories.map(parseCategory)
}

export async function loadListCounts(signal?: AbortSignal): Promise<ListCount[]> {
  const value = await read(`${base}/repetition`, signal)
  if (
    !isRecord(value) || !Array.isArray(value.lists) || value.lists.length !== LISTS.length ||
    !value.lists.every((entry, index) =>
      isRecord(entry) && entry.list === LISTS[index] && isInteger(entry.puzzles, 0, 100_000_000))
  ) throw new PuzzleRequestError('unavailable')
  return value.lists.map((entry) => ({ list: entry.list, puzzles: entry.puzzles }))
}

export async function loadMastered(signal?: AbortSignal): Promise<MasteredSummary> {
  const value = await read(`${base}/repetition/mastered`, signal)
  if (
    !isRecord(value) || !isInteger(value.total, 0, 100_000_000) ||
    !Array.isArray(value.categories) || value.categories.length > 25_000
  ) throw new PuzzleRequestError('unavailable')
  return { total: value.total, categories: value.categories.map(parseCategory) }
}

export async function startListSession(list: ActiveList): Promise<PracticeSession> {
  if (!ACTIVE_LISTS.includes(list)) throw new PuzzleRequestError('unavailable')
  return parseSession(await (await send(`${base}/repetition/${list}/sessions`)).json())
}

export async function startMasteredSession(kind: CategoryKind, key: string): Promise<PracticeSession> {
  if (!KINDS.includes(kind) || !isCategoryKey(key)) throw new PuzzleRequestError('unavailable')
  const response = await send(`${base}/repetition/mastered/${kind}/${encodeURIComponent(key)}/sessions`)
  return parseSession(await response.json())
}

export async function nextSessionPuzzle(sessionId: number): Promise<SessionAttempt> {
  if (!isInteger(sessionId, 1, Number.MAX_SAFE_INTEGER)) throw new PuzzleRequestError('unavailable')
  const value: unknown = await (await send(`${base}/sessions/${sessionId}/attempts`)).json()
  if (
    !isRecord(value) || !isInteger(value.total, 1, 100_000_000) ||
    !isInteger(value.position, 1, value.total)
  ) throw new PuzzleRequestError('unavailable')
  return { attempt: parseAttempt(value.attempt), position: value.position, total: value.total }
}

export async function startRandomPuzzle(): Promise<PuzzleAttempt> {
  return parseAttempt(await (await send(`${base}/random/attempts`)).json())
}

export async function startCategoryPuzzle(kind: CategoryKind, key: string): Promise<PuzzleAttempt> {
  if (!KINDS.includes(kind) || !isCategoryKey(key)) throw new PuzzleRequestError('unavailable')
  const response = await send(`${base}/categories/${kind}/${encodeURIComponent(key)}/attempts`)
  return parseAttempt(await response.json())
}

/** A fixed rating package of a pattern ladder; `high` is exclusive. */
export type RatingPackageKey = 'up-to-1000' | '1000-1400' | '1400-1800' | '1800-2200' | '2200-2600' | '2600-plus'

export const RATING_PACKAGES: { key: RatingPackageKey; label: string }[] = [
  { key: 'up-to-1000', label: 'Below 1000' },
  { key: '1000-1400', label: '1000–1399' },
  { key: '1400-1800', label: '1400–1799' },
  { key: '1800-2200', label: '1800–2199' },
  { key: '2200-2600', label: '2200–2599' },
  { key: '2600-plus', label: '2600 and above' },
]

export type LadderPattern = { key: string; name: string; group: string; puzzles: number }

/** How many puzzles were presented since the package was started or restarted, and the last rating. */
export type LadderProgress = { presented: number; lastRating: number }

export type LadderPackage = {
  key: RatingPackageKey
  low: number
  high: number
  puzzles: number
  progress: LadderProgress | null
}

export type Ladder = { pattern: LadderPattern; packages: LadderPackage[] }

export type LadderStep = { attempt: PuzzleAttempt; progress: LadderProgress }

export async function loadLadderPatterns(signal?: AbortSignal): Promise<LadderPattern[]> {
  const value = await read(`${base}/ladders`, signal)
  if (!isRecord(value) || !Array.isArray(value.patterns) || value.patterns.length > 500) {
    throw new PuzzleRequestError('unavailable')
  }
  return value.patterns.map((entry) => parseLadderPattern(entry))
}

export async function loadLadder(pattern: string, signal?: AbortSignal): Promise<Ladder> {
  if (!isCategoryKey(pattern)) throw new PuzzleRequestError('unavailable')
  const value = await read(`${base}/ladders/${encodeURIComponent(pattern)}`, signal)
  if (
    !isRecord(value) || !Array.isArray(value.packages) ||
    value.packages.length !== RATING_PACKAGES.length
  ) throw new PuzzleRequestError('unavailable')
  const packages = value.packages.map((entry, index) => parseLadderPackage(entry, RATING_PACKAGES[index].key))
  return { pattern: parseLadderPattern(value.pattern, true), packages }
}

export async function nextLadderPuzzle(pattern: string, ratingPackage: RatingPackageKey): Promise<LadderStep> {
  if (!isCategoryKey(pattern) || !isPackageKey(ratingPackage)) throw new PuzzleRequestError('unavailable')
  const response = await send(`${base}/ladders/${encodeURIComponent(pattern)}/${ratingPackage}/attempts`)
  const value: unknown = await response.json()
  if (!isRecord(value)) throw new PuzzleRequestError('unavailable')
  const progress = parseProgress(value.progress)
  if (progress === null) throw new PuzzleRequestError('unavailable')
  return { attempt: parseAttempt(value.attempt), progress }
}

export async function restartLadder(pattern: string, ratingPackage: RatingPackageKey): Promise<void> {
  if (!isCategoryKey(pattern) || !isPackageKey(ratingPackage)) throw new PuzzleRequestError('unavailable')
  await send(`${base}/ladders/${encodeURIComponent(pattern)}/${ratingPackage}/restart`)
}

/** Moves the place so the package's next puzzle is the one at `position` (1-based). */
export async function jumpInLadder(pattern: string, ratingPackage: RatingPackageKey, position: number): Promise<void> {
  if (!isCategoryKey(pattern) || !isPackageKey(ratingPackage) || !isInteger(position, 1, 100_000_000)) {
    throw new PuzzleRequestError('unavailable')
  }
  await send(`${base}/ladders/${encodeURIComponent(pattern)}/${ratingPackage}/jump`, { position })
}

export type FastLadder = { fastLadderId: number; total: number }

/** A rating step names its range (`ratingHigh` exclusive); a combination step names the other pattern. */
export type FastStep =
  | { kind: 'rating'; ratingLow: number; ratingHigh: number }
  | { kind: 'combination'; key: string; name: string }

export type FastLadderStep = { attempt: PuzzleAttempt; position: number; total: number; step: FastStep }

export async function startFastLadder(pattern: string): Promise<FastLadder> {
  if (!isCategoryKey(pattern)) throw new PuzzleRequestError('unavailable')
  const value: unknown = await (await send(`${base}/ladders/${encodeURIComponent(pattern)}/fast`)).json()
  if (
    !isRecord(value) || !isInteger(value.fastLadderId, 1, Number.MAX_SAFE_INTEGER) ||
    !isInteger(value.total, 1, 200)
  ) throw new PuzzleRequestError('unavailable')
  return { fastLadderId: value.fastLadderId, total: value.total }
}

export async function nextFastLadderPuzzle(fastLadderId: number): Promise<FastLadderStep> {
  if (!isInteger(fastLadderId, 1, Number.MAX_SAFE_INTEGER)) throw new PuzzleRequestError('unavailable')
  const value: unknown = await (await send(`${base}/ladders/fast/${fastLadderId}/attempts`)).json()
  if (
    !isRecord(value) || !isInteger(value.total, 1, 200) || !isInteger(value.position, 1, value.total) ||
    !isRecord(value.step)
  ) throw new PuzzleRequestError('unavailable')
  return { attempt: parseAttempt(value.attempt), position: value.position, total: value.total, step: parseFastStep(value.step) }
}

function parseFastStep(value: Record<string, unknown>): FastStep {
  const combined = value.combinedWith
  if (combined !== null && combined !== undefined) {
    if (!isRecord(combined) || !isCategoryKey(combined.key) || !isText(combined.name, 180)) {
      throw new PuzzleRequestError('unavailable')
    }
    return { kind: 'combination', key: combined.key, name: combined.name }
  }
  if (!isInteger(value.ratingLow, 0, 4100) || !isInteger(value.ratingHigh, 100, 4100) || value.ratingHigh <= value.ratingLow) {
    throw new PuzzleRequestError('unavailable')
  }
  return { kind: 'rating', ratingLow: value.ratingLow, ratingHigh: value.ratingHigh }
}

export function isPackageKey(value: unknown): value is RatingPackageKey {
  return RATING_PACKAGES.some((entry) => entry.key === value)
}

/** A pattern in the list has puzzles; the one a ladder names may have none after an import. */
function parseLadderPattern(value: unknown, allowEmpty = false): LadderPattern {
  if (
    !isRecord(value) || !isCategoryKey(value.key) || !isText(value.name, 180) ||
    !isText(value.group, 80) || !isInteger(value.puzzles, allowEmpty ? 0 : 1, 100_000_000)
  ) throw new PuzzleRequestError('unavailable')
  return { key: value.key, name: value.name, group: value.group, puzzles: value.puzzles }
}

function parseLadderPackage(value: unknown, key: RatingPackageKey): LadderPackage {
  if (
    !isRecord(value) || value.key !== key || !isInteger(value.low, 0, 4100) ||
    !isInteger(value.high, 100, 4100) || value.high <= value.low ||
    !isInteger(value.puzzles, 0, 100_000_000)
  ) throw new PuzzleRequestError('unavailable')
  const progress = value.progress === null || value.progress === undefined ? null : parseProgress(value.progress)
  if (value.progress !== null && value.progress !== undefined && progress === null) {
    throw new PuzzleRequestError('unavailable')
  }
  return { key, low: value.low, high: value.high, puzzles: value.puzzles, progress }
}

function parseProgress(value: unknown): LadderProgress | null {
  if (
    !isRecord(value) || !isInteger(value.presented, 1, 100_000_000) ||
    !isInteger(value.lastRating, 100, 4000)
  ) return null
  return { presented: value.presented, lastRating: value.lastRating }
}

export async function submitPuzzleMove(attemptId: number, move: string): Promise<PuzzleMoveResult> {
  const response = await send(`${base}/attempts/${attemptId}/moves`, { move })
  return parseMoveResult(await response.json(), parseCompletion)
}

/** Shared with the Own Puzzles client, whose completion has its own shape. */
export function parseMoveResult<C>(value: unknown, completion: (value: unknown) => C): MoveResult<C> {
  if (
    !isRecord(value) ||
    (value.outcome !== 'correct' && value.outcome !== 'solved' && value.outcome !== 'wrong') ||
    !(value.opponentReply === null || value.opponentReply === undefined || isUci(value.opponentReply)) ||
    !isInteger(value.wrongMoves, 0, 10_000)
  ) throw new PuzzleRequestError('unavailable')
  const hasCompletion = value.completion !== null && value.completion !== undefined
  if (value.outcome === 'correct') {
    if (typeof value.opponentReply !== 'string' || hasCompletion) throw new PuzzleRequestError('unavailable')
    return { outcome: 'correct', opponentReply: value.opponentReply, wrongMoves: value.wrongMoves }
  }
  if (typeof value.opponentReply === 'string') throw new PuzzleRequestError('unavailable')
  if (value.outcome === 'solved') {
    if (!hasCompletion) throw new PuzzleRequestError('unavailable')
    return { outcome: 'solved', completion: completion(value.completion), wrongMoves: value.wrongMoves }
  }
  if (hasCompletion) throw new PuzzleRequestError('unavailable')
  return { outcome: 'wrong', wrongMoves: value.wrongMoves }
}

export async function requestPuzzleHint(attemptId: number): Promise<string> {
  return parseHint(await (await send(`${base}/attempts/${attemptId}/hint`)).json())
}

/** Shared with the Own Puzzles client. */
export function parseHint(value: unknown): string {
  if (!isRecord(value) || !isSquare(value.fromSquare)) throw new PuzzleRequestError('unavailable')
  return value.fromSquare
}

export async function revealPuzzle(attemptId: number): Promise<PuzzleRevealResult> {
  return parseReveal(await (await send(`${base}/attempts/${attemptId}/reveal`)).json(), parseCompletion)
}

/** Shared with the Own Puzzles client, whose completion has its own shape. */
export function parseReveal<C>(value: unknown, completion: (value: unknown) => C): RevealResult<C> {
  if (
    !isRecord(value) || !Array.isArray(value.remainingMoves) ||
    value.remainingMoves.length < 1 || value.remainingMoves.length > 200 ||
    !value.remainingMoves.every(isUci)
  ) throw new PuzzleRequestError('unavailable')
  return { remainingMoves: value.remainingMoves, completion: completion(value.completion) }
}

/** A same-origin JSON read; shared with the Own Puzzles client. */
export async function read(url: string, signal?: AbortSignal): Promise<unknown> {
  const response = await fetch(url, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  })
  await requireResponse(response)
  return response.json()
}

/** A same-origin JSON {@code POST}; shared with the Own Puzzles client. */
export async function send(url: string, body?: unknown) {
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  await requireResponse(response)
  return response
}

async function requireResponse(response: Response) {
  if (response.ok) return
  if (response.status === 401) throw new PuzzleRequestError('not_connected')
  const code = await errorCode(response)
  if (code === SERVER_ERROR.sessionUnavailable) throw new PuzzleRequestError('session_unavailable')
  if (code === SERVER_ERROR.noPuzzleAvailable || code === SERVER_ERROR.noNewOwnPuzzles) throw new PuzzleRequestError('no_puzzle_available')
  if (code === SERVER_ERROR.attemptFinished || code === SERVER_ERROR.attemptNotFound) {
    throw new PuzzleRequestError('attempt_finished')
  }
  if (code === SERVER_ERROR.illegalMove) throw new PuzzleRequestError('illegal_move')
  if (code === SERVER_ERROR.sessionComplete) throw new PuzzleRequestError('session_complete')
  if (code === SERVER_ERROR.sessionNotFound) throw new PuzzleRequestError('session_not_found')
  if (code === SERVER_ERROR.ownPuzzleNotFound) throw new PuzzleRequestError('own_puzzle_not_found')
  if (code === SERVER_ERROR.ownPuzzleClassified) throw new PuzzleRequestError('own_puzzle_classified')
  if (code === SERVER_ERROR.ownPuzzleNotDiscarded) throw new PuzzleRequestError('own_puzzle_not_discarded')
  if (code === SERVER_ERROR.patternNotFound) throw new PuzzleRequestError('pattern_not_found')
  if (code === SERVER_ERROR.ladderComplete) throw new PuzzleRequestError('ladder_complete')
  throw new PuzzleRequestError('unavailable')
}

async function errorCode(response: Response) {
  try {
    const value: unknown = await response.json()
    return isRecord(value) && typeof value.error === 'string' ? value.error : null
  } catch {
    return null
  }
}

function parseCategory(value: unknown): PuzzleCategory {
  if (
    !isRecord(value) || !KINDS.includes(value.kind as CategoryKind) ||
    !isCategoryKey(value.key) || !isText(value.name, 180) || !isText(value.group, 80) ||
    !(value.parentKey === null || value.parentKey === undefined || isCategoryKey(value.parentKey)) ||
    !isInteger(value.puzzles, 1, 100_000_000)
  ) throw new PuzzleRequestError('unavailable')
  return {
    kind: value.kind as CategoryKind,
    key: value.key,
    name: value.name,
    group: value.group,
    parentKey: typeof value.parentKey === 'string' ? value.parentKey : null,
    puzzles: value.puzzles,
  }
}

/** Shared with the Own Puzzles client. */
export function parseSession(value: unknown): PracticeSession {
  if (
    !isRecord(value) || !isInteger(value.sessionId, 1, Number.MAX_SAFE_INTEGER) ||
    !isList(value.list) || !isInteger(value.total, 1, 100_000_000)
  ) throw new PuzzleRequestError('unavailable')
  return { sessionId: value.sessionId, list: value.list, total: value.total }
}

/** Shared with the guided course, whose Exercises are Puzzle attempts. */
export function parseAttempt(value: unknown): PuzzleAttempt {
  if (
    !isRecord(value) || !isInteger(value.attemptId, 1, Number.MAX_SAFE_INTEGER) ||
    !isInteger(value.puzzleId, 1, Number.MAX_SAFE_INTEGER) ||
    typeof value.externalId !== 'string' || !/^[A-Za-z0-9]{1,32}$/.test(value.externalId) ||
    !isText(value.positionFen, 120) || !isUci(value.setupMove) ||
    !isInteger(value.rating, 100, 4000) || !isInteger(value.ownerMoves, 1, 100) ||
    !isInteger(value.fastSolveLimitMillis, 6_000, 1_000_000) ||
    !(value.currentList === null || value.currentList === undefined || isList(value.currentList))
  ) throw new PuzzleRequestError('unavailable')
  return {
    attemptId: value.attemptId,
    puzzleId: value.puzzleId,
    externalId: value.externalId,
    positionFen: value.positionFen,
    setupMove: value.setupMove,
    rating: value.rating,
    ownerMoves: value.ownerMoves,
    fastSolveLimitMillis: value.fastSolveLimitMillis,
    currentList: isList(value.currentList) ? value.currentList : null,
  }
}

/** Shared with the guided course, whose Exercises are Puzzle attempts. */
export function parseCompletion(value: unknown): PuzzleCompletion {
  if (
    !isRecord(value) ||
    !(value.previousList === null || value.previousList === undefined || isList(value.previousList)) ||
    !isList(value.list) || !isInteger(value.successes, 0, 9) || !isInteger(value.strikes, 0, 1) ||
    !isInteger(value.wrongMoves, 0, 10_000) || typeof value.hintUsed !== 'boolean' ||
    !isInteger(value.elapsedMillis, 0, Number.MAX_SAFE_INTEGER) ||
    !isInteger(value.fastSolveLimitMillis, 6_000, 1_000_000) ||
    typeof value.gameUrl !== 'string' || !value.gameUrl.startsWith('https://lichess.org/') ||
    value.gameUrl.length > 240 ||
    !Array.isArray(value.themes) || value.themes.length > 40 || !value.themes.every(isCategoryKey)
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
    gameUrl: value.gameUrl,
    themes: value.themes,
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export function isText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maximum
}

function isCategoryKey(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(value)
}

export function isList(value: unknown): value is TrainingList {
  return typeof value === 'string' && LISTS.includes(value as TrainingList)
}

export function isUci(value: unknown): value is string {
  return typeof value === 'string' && /^[a-h][1-8][a-h][1-8][qrbn]?$/.test(value)
}

export function isSquare(value: unknown): value is string {
  return typeof value === 'string' && /^[a-h][1-8]$/.test(value)
}

export function isInteger(value: unknown, minimum: number, maximum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
}
