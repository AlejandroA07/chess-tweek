export type TrainingList =
  | 'hard_to_solve'
  | 'wrong_moves'
  | 'worth_repeating'
  | 'retiring'
  | 'mastered'

export type CategoryKind = 'theme' | 'opening-family' | 'opening-variation'

export type PuzzleCategory = {
  kind: CategoryKind
  key: string
  name: string
  group: string
  parentKey: string | null
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

export type PuzzleMoveResult = {
  outcome: 'correct' | 'solved' | 'wrong'
  opponentReply: string | null
  wrongMoves: number
  completion: PuzzleCompletion | null
}

export type PuzzleRevealResult = {
  remainingMoves: string[]
  completion: PuzzleCompletion
}

export type PuzzleFailure =
  | 'not_connected'
  | 'session_unavailable'
  | 'no_puzzle_available'
  | 'attempt_finished'
  | 'illegal_move'
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

export async function startRandomPuzzle(): Promise<PuzzleAttempt> {
  return parseAttempt(await (await send(`${base}/random/attempts`)).json())
}

export async function startCategoryPuzzle(kind: CategoryKind, key: string): Promise<PuzzleAttempt> {
  if (!KINDS.includes(kind) || !isCategoryKey(key)) throw new PuzzleRequestError('unavailable')
  const response = await send(`${base}/categories/${kind}/${encodeURIComponent(key)}/attempts`)
  return parseAttempt(await response.json())
}

export async function submitPuzzleMove(attemptId: number, move: string): Promise<PuzzleMoveResult> {
  const response = await send(`${base}/attempts/${attemptId}/moves`, { move })
  const value: unknown = await response.json()
  if (
    !isRecord(value) ||
    (value.outcome !== 'correct' && value.outcome !== 'solved' && value.outcome !== 'wrong') ||
    !(value.opponentReply === null || value.opponentReply === undefined || isUci(value.opponentReply)) ||
    !isInteger(value.wrongMoves, 0, 10_000)
  ) throw new PuzzleRequestError('unavailable')
  const reply = typeof value.opponentReply === 'string' ? value.opponentReply : null
  const completion = value.completion === null || value.completion === undefined
    ? null
    : parseCompletion(value.completion)
  if ((value.outcome === 'correct') !== (reply !== null) ||
      (value.outcome === 'solved') !== (completion !== null)) {
    throw new PuzzleRequestError('unavailable')
  }
  return { outcome: value.outcome, opponentReply: reply, wrongMoves: value.wrongMoves, completion }
}

export async function requestPuzzleHint(attemptId: number): Promise<string> {
  const value: unknown = await (await send(`${base}/attempts/${attemptId}/hint`)).json()
  if (!isRecord(value) || !isSquare(value.fromSquare)) throw new PuzzleRequestError('unavailable')
  return value.fromSquare
}

export async function revealPuzzle(attemptId: number): Promise<PuzzleRevealResult> {
  const value: unknown = await (await send(`${base}/attempts/${attemptId}/reveal`)).json()
  if (
    !isRecord(value) || !Array.isArray(value.remainingMoves) ||
    value.remainingMoves.length < 1 || value.remainingMoves.length > 200 ||
    !value.remainingMoves.every(isUci)
  ) throw new PuzzleRequestError('unavailable')
  return { remainingMoves: value.remainingMoves, completion: parseCompletion(value.completion) }
}

async function send(url: string, body?: unknown) {
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
  if (code === 'session_unavailable') throw new PuzzleRequestError('session_unavailable')
  if (code === 'no_puzzle_available') throw new PuzzleRequestError('no_puzzle_available')
  if (code === 'attempt_finished' || code === 'attempt_not_found') {
    throw new PuzzleRequestError('attempt_finished')
  }
  if (code === 'illegal_training_move') throw new PuzzleRequestError('illegal_move')
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
    !(value.parentKey === null || value.parentKey === undefined || isCategoryKey(value.parentKey))
  ) throw new PuzzleRequestError('unavailable')
  return {
    kind: value.kind as CategoryKind,
    key: value.key,
    name: value.name,
    group: value.group,
    parentKey: typeof value.parentKey === 'string' ? value.parentKey : null,
  }
}

function parseAttempt(value: unknown): PuzzleAttempt {
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

function parseCompletion(value: unknown): PuzzleCompletion {
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maximum
}

function isCategoryKey(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{1,120}$/.test(value)
}

function isList(value: unknown): value is TrainingList {
  return typeof value === 'string' && LISTS.includes(value as TrainingList)
}

function isUci(value: unknown): value is string {
  return typeof value === 'string' && /^[a-h][1-8][a-h][1-8][qrbn]?$/.test(value)
}

function isSquare(value: unknown): value is string {
  return typeof value === 'string' && /^[a-h][1-8]$/.test(value)
}

function isInteger(value: unknown, minimum: number, maximum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
}
