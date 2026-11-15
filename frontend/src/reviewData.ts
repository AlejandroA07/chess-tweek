import { parseGame, type CatalogGame } from './gameCatalog'

export type ReviewSide = 'my_moves' | 'opponent_moves'
export type Phase = 'opening' | 'middlegame' | 'endgame'
export type Severity = 'none' | 'inaccuracy' | 'mistake' | 'blunder'

export type Ply = { ply: number; color: 'white' | 'black'; san: string; uci: string; fen: string }

export type Phases = { source: 'supplied' | 'inferred'; middlegameStart: number; endgameStart: number | null }

export type ReviewVersion = { id: number; completedAt: string; currentProfile: boolean }

export type PhaseStatistics = {
  moves: number
  inaccuracies: number
  mistakes: number
  blunders: number
  averageLoss: number
  averageCentipawnLoss: number
}

export type Statistics = PhaseStatistics & { byPhase: Record<Phase, PhaseStatistics> }

export type PositionEvaluation = {
  index: number
  whiteExpected: number
  whiteCp: number | null
  whiteMate: number | null
}

export type EngineLine = {
  move: string
  moveSan: string
  cp: number | null
  mate: number | null
  win: number
  draw: number
  loss: number
  pv: string[]
  pvSan: string[]
}

export type TacticTheme =
  | 'allowed_mate'
  | 'missed_mate'
  | 'back_rank'
  | 'hanging_piece'
  | 'fork'
  | 'general_tactics'

export type PieceName = 'pawn' | 'knight' | 'bishop' | 'rook' | 'queen' | 'king'

/** A conservative tactic label proven by the server from the stored engine lines. */
export type Tactic = {
  theme: TacticTheme
  kind: 'allowed' | 'missed'
  piece: PieceName | null
  square: string | null
  targets: string[]
}

export type JudgedMove = {
  ply: number
  san: string
  uci: string
  fenBefore: string
  phase: Phase
  pass: 'quick' | 'deep'
  label: Severity
  mateEvent: 'none' | 'missed_mate' | 'allowed_mate'
  playedBest: boolean
  bestMove: string
  bestMoveSan: string | null
  expectedBefore: number
  expectedAfter: number
  loss: number
  afterTerminal: 'none' | 'checkmate' | 'stalemate'
  before: EngineLine[]
  after: EngineLine[]
  tactics: Tactic[]
}

export type SelectedSide = {
  side: ReviewSide
  versionId: number
  analyzedColor: 'white' | 'black'
  engineName: string
  quickNodes: number
  deepNodes: number
  completedAt: string
  currentProfile: boolean
  statistics: Statistics
  evaluations: PositionEvaluation[]
  moves: JudgedMove[]
}

export type GameReview = {
  game: CatalogGame
  initialFen: string
  plies: Ply[]
  phases: Phases | null
  myMoves: ReviewVersion | null
  opponentMoves: ReviewVersion | null
  selected: SelectedSide | null
}

export type ReviewFailure =
  | 'not_connected'
  | 'session_unavailable'
  | 'game_not_found'
  | 'review_unavailable'

export class ReviewRequestError extends Error {
  constructor(readonly failure: ReviewFailure) {
    super(failure)
  }
}

export const GAME_ID_PATTERN = /^[A-Za-z0-9]{8,32}$/
const MAX_PLIES = 1_200
const UCI = /^[a-h][1-8][a-h][1-8][qrbn]?$/
const PHASES: readonly Phase[] = ['opening', 'middlegame', 'endgame']

export async function requestReview(
  gameId: string,
  side: ReviewSide | null,
  signal: AbortSignal,
): Promise<GameReview> {
  if (!GAME_ID_PATTERN.test(gameId)) throw new ReviewRequestError('game_not_found')
  const query = side === null ? '' : `?side=${side}`
  const response = await fetch(`/api/games/${encodeURIComponent(gameId)}/review${query}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    const error = await safeErrorCode(response)
    if (response.status === 401 || error === 'not_connected') throw new ReviewRequestError('not_connected')
    if (error === 'session_unavailable') throw new ReviewRequestError('session_unavailable')
    if (response.status === 404 || error === 'game_not_found') throw new ReviewRequestError('game_not_found')
    throw new ReviewRequestError('review_unavailable')
  }
  try {
    return parseReview(await response.json() as unknown)
  } catch {
    throw new ReviewRequestError('review_unavailable')
  }
}

async function safeErrorCode(response: Response) {
  try {
    const body: unknown = await response.json()
    return isRecord(body) && isText(body.error, 64) ? body.error : null
  } catch {
    return null
  }
}

/** Validates the whole response; any unexpected shape is rejected rather than partially shown. */
export function parseReview(value: unknown): GameReview {
  if (!isRecord(value) || !isFen(value.initialFen) || !Array.isArray(value.plies)) {
    throw new Error('Invalid review')
  }
  const game = parseGame(value.game)
  if (value.plies.length > MAX_PLIES) throw new Error('Invalid review')
  const plies = value.plies.map((ply, index) => parsePly(ply, index))
  const phases = value.phases === null ? null : parsePhases(value.phases)
  const selected = value.selected === null ? null : parseSelected(value.selected, plies.length)
  const myMoves = parseVersion(value.myMoves)
  const opponentMoves = parseVersion(value.opponentMoves)
  if (
    selected !== null &&
    (selected.side === 'my_moves' ? myMoves : opponentMoves)?.id !== selected.versionId
  ) {
    throw new Error('Invalid review')
  }
  return { game, initialFen: value.initialFen, plies, phases, myMoves, opponentMoves, selected }
}

function parsePly(value: unknown, index: number): Ply {
  if (
    !isRecord(value) ||
    value.ply !== index + 1 ||
    !isOneOf(value.color, ['white', 'black']) ||
    !isText(value.san, 10) ||
    !isUci(value.uci) ||
    !isFen(value.fen)
  ) {
    throw new Error('Invalid ply')
  }
  return value as Ply
}

function parsePhases(value: unknown): Phases {
  if (
    !isRecord(value) ||
    !isOneOf(value.source, ['supplied', 'inferred']) ||
    // A short game can end before the inferred middlegame or endgame starts.
    !isInteger(value.middlegameStart, 0, MAX_PLIES) ||
    !(value.endgameStart === null || isInteger(value.endgameStart, value.middlegameStart, MAX_PLIES))
  ) {
    throw new Error('Invalid phases')
  }
  return value as Phases
}

function parseVersion(value: unknown): ReviewVersion | null {
  if (value === null) return null
  if (
    !isRecord(value) ||
    !isInteger(value.id, 1, Number.MAX_SAFE_INTEGER) ||
    !isDate(value.completedAt) ||
    typeof value.currentProfile !== 'boolean'
  ) {
    throw new Error('Invalid version')
  }
  return value as ReviewVersion
}

function parseSelected(value: unknown, plyCount: number): SelectedSide {
  if (
    !isRecord(value) ||
    !isOneOf(value.side, ['my_moves', 'opponent_moves']) ||
    !isInteger(value.versionId, 1, Number.MAX_SAFE_INTEGER) ||
    !isOneOf(value.analyzedColor, ['white', 'black']) ||
    !isText(value.engineName, 64) ||
    !isInteger(value.quickNodes, 1, Number.MAX_SAFE_INTEGER) ||
    !isInteger(value.deepNodes, 1, Number.MAX_SAFE_INTEGER) ||
    !isDate(value.completedAt) ||
    typeof value.currentProfile !== 'boolean' ||
    !Array.isArray(value.evaluations) ||
    value.evaluations.length > plyCount + 1 ||
    !Array.isArray(value.moves) ||
    value.moves.length > plyCount
  ) {
    throw new Error('Invalid selected side')
  }
  const statistics = parseStatistics(value.statistics)
  const evaluations = value.evaluations.map((evaluation) => parseEvaluation(evaluation, plyCount))
  const moves = value.moves.map((move) => parseJudgedMove(move, plyCount))
  return { ...(value as SelectedSide), statistics, evaluations, moves }
}

function parseStatistics(value: unknown): Statistics {
  if (!isRecord(value) || !isPhaseStatistics(value)) throw new Error('Invalid statistics')
  const byPhase = (value as Record<string, unknown>).byPhase
  if (!isRecord(byPhase) || !PHASES.every((phase) => isPhaseStatistics(byPhase[phase]))) {
    throw new Error('Invalid statistics')
  }
  return value as Statistics
}

function isPhaseStatistics(value: unknown): value is PhaseStatistics {
  return (
    isRecord(value) &&
    isInteger(value.moves, 0, MAX_PLIES) &&
    isInteger(value.inaccuracies, 0, value.moves) &&
    isInteger(value.mistakes, 0, value.moves) &&
    isInteger(value.blunders, 0, value.moves) &&
    isNumber(value.averageLoss, 0, 100) &&
    isNumber(value.averageCentipawnLoss, 0, 1_000)
  )
}

function parseEvaluation(value: unknown, plyCount: number): PositionEvaluation {
  if (
    !isRecord(value) ||
    !isInteger(value.index, 0, plyCount) ||
    !isInteger(value.whiteExpected, 0, 2_000) ||
    !(value.whiteCp === null || isInteger(value.whiteCp, -100_000, 100_000)) ||
    !(value.whiteMate === null || isInteger(value.whiteMate, -1_000, 1_000))
  ) {
    throw new Error('Invalid evaluation')
  }
  return value as PositionEvaluation
}

function parseJudgedMove(value: unknown, plyCount: number): JudgedMove {
  if (
    !isRecord(value) ||
    !isInteger(value.ply, 1, plyCount) ||
    !isText(value.san, 10) ||
    !isUci(value.uci) ||
    !isFen(value.fenBefore) ||
    !isOneOf(value.phase, PHASES) ||
    !isOneOf(value.pass, ['quick', 'deep']) ||
    !isOneOf(value.label, ['none', 'inaccuracy', 'mistake', 'blunder']) ||
    !isOneOf(value.mateEvent, ['none', 'missed_mate', 'allowed_mate']) ||
    typeof value.playedBest !== 'boolean' ||
    !isUci(value.bestMove) ||
    !(value.bestMoveSan === null || isText(value.bestMoveSan, 10)) ||
    !isInteger(value.expectedBefore, 0, 2_000) ||
    !isInteger(value.expectedAfter, 0, 2_000) ||
    !isInteger(value.loss, 0, 2_000) ||
    !isOneOf(value.afterTerminal, ['none', 'checkmate', 'stalemate']) ||
    !Array.isArray(value.before) ||
    value.before.length > 3 ||
    !Array.isArray(value.after) ||
    value.after.length > 3 ||
    !Array.isArray(value.tactics) ||
    value.tactics.length > 6
  ) {
    throw new Error('Invalid judged move')
  }
  return {
    ...(value as JudgedMove),
    before: value.before.map(parseLine),
    after: value.after.map(parseLine),
    tactics: value.tactics.map(parseTactic),
  }
}

const SQUARE = /^[a-h][1-8]$/

function parseTactic(value: unknown): Tactic {
  if (
    !isRecord(value) ||
    !isOneOf(value.theme, ['allowed_mate', 'missed_mate', 'back_rank', 'hanging_piece', 'fork', 'general_tactics']) ||
    !isOneOf(value.kind, ['allowed', 'missed']) ||
    !(value.piece === null || isOneOf(value.piece, ['pawn', 'knight', 'bishop', 'rook', 'queen', 'king'])) ||
    !(value.square === null || (typeof value.square === 'string' && SQUARE.test(value.square))) ||
    !Array.isArray(value.targets) ||
    value.targets.length > 8 ||
    !value.targets.every((target) => typeof target === 'string' && SQUARE.test(target))
  ) {
    throw new Error('Invalid tactic')
  }
  return value as Tactic
}

function parseLine(value: unknown): EngineLine {
  if (
    !isRecord(value) ||
    !isUci(value.move) ||
    !isText(value.moveSan, 10) ||
    !(value.cp === null || isInteger(value.cp, -100_000, 100_000)) ||
    !(value.mate === null || isInteger(value.mate, -1_000, 1_000)) ||
    (value.cp === null) === (value.mate === null) ||
    !isInteger(value.win, 0, 1_000) ||
    !isInteger(value.draw, 0, 1_000) ||
    !isInteger(value.loss, 0, 1_000) ||
    !Array.isArray(value.pv) ||
    value.pv.length > 64 ||
    !value.pv.every(isUci) ||
    !Array.isArray(value.pvSan) ||
    value.pvSan.length > value.pv.length ||
    !value.pvSan.every((san) => isText(san, 10))
  ) {
    throw new Error('Invalid engine line')
  }
  return value as EngineLine
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isText(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= max
}

function isFen(value: unknown): value is string {
  return isText(value, 100) && /^[1-8pnbrqkPNBRQK/]+ [wb] /.test(value)
}

function isUci(value: unknown): value is string {
  return typeof value === 'string' && UCI.test(value)
}

function isInteger(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
}

function isNumber(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max
}

function isDate(value: unknown): value is string {
  return isText(value, 40) && Number.isFinite(Date.parse(value))
}

function isOneOf<T extends string>(value: unknown, allowed: readonly T[]): value is T {
  return typeof value === 'string' && allowed.includes(value as T)
}
