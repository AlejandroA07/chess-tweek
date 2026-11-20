import {
  useId,
  useState,
  type DragEvent,
  type MouseEvent as ReactMouseEvent,
  type WheelEvent as ReactWheelEvent,
} from 'react'
import styles from './ChessBoard.module.css'

export type BoardOrientation = 'white' | 'black'

type Piece = { color: 'white' | 'black'; glyph: string; kind: string }
type Side = Piece['color']
type Placement = (Piece | null)[][]
type BoardStep = {
  placement: Placement
  side: Side
  lastMove: string | null
  castling: string
  enPassant: string | null
}
type Point = { x: number; y: number }
type ArrowGeometry =
  | { kind: 'knight'; move: string; points: Point[] }
  | { kind: 'straight'; move: string; start: Point; end: Point }

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']
// Filled glyphs for both colors; CSS colors them. U+FE0E keeps macOS from drawing emoji.
const GLYPHS: Record<string, string> = {
  k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟',
}

/** Reads the piece placement of a FEN into rank 8 → 1, file a → h; null for an unreadable FEN. */
export function parsePlacement(fen: string): (Piece | null)[][] | null {
  const rows = fen.split(' ')[0]?.split('/') ?? []
  if (rows.length !== 8) return null
  const board: (Piece | null)[][] = []
  for (const row of rows) {
    const squares: (Piece | null)[] = []
    for (const character of row) {
      if (/[1-8]/.test(character)) {
        for (let index = 0; index < Number(character); index += 1) squares.push(null)
      } else {
        const glyph = GLYPHS[character.toLowerCase()]
        if (glyph === undefined) return null
        squares.push({
          color: character === character.toUpperCase() ? 'white' : 'black',
          glyph,
          kind: character.toLowerCase(),
        })
      }
    }
    if (squares.length !== 8) return null
    board.push(squares)
  }
  return board
}

function squareName(rankIndex: number, fileIndex: number) {
  return `${FILES[fileIndex]}${8 - rankIndex}`
}

/** Board coordinates (0–7, top-left origin) of a square for the given orientation. */
function screenPosition(square: string, orientation: BoardOrientation) {
  const file = FILES.indexOf(square[0])
  const rank = Number(square[1]) - 1
  return orientation === 'white' ? { x: file, y: 7 - rank } : { x: 7 - file, y: rank }
}

/**
 * A read-only board drawn with project styles. {@code highlight} marks the from and to squares of
 * the last move; {@code arrow} draws one engine move. The board is described by {@code label}.
 */
export function ChessBoard({
  fen,
  orientation,
  label,
  highlight,
  arrow,
}: {
  fen: string
  orientation: BoardOrientation
  label: string
  highlight: string | null
  arrow: string | null
}) {
  const placement = parsePlacement(fen)
  if (placement === null) {
    return <p className={styles.unreadable} role="alert">This position could not be drawn.</p>
  }
  const ranks = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]
  const files = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]

  return (
    <div className={styles.frame} role="img" aria-label={label}>
      <div className={styles.board} aria-hidden="true">
        {ranks.map((rankIndex, row) =>
          files.map((fileIndex, column) => {
            const square = squareName(rankIndex, fileIndex)
            const piece = placement[rankIndex][fileIndex]
            const lastMoveRole = moveSquareRole(highlight, square)
            return (
              <div
                key={square}
                className={styles.square}
                data-shade={(rankIndex + fileIndex) % 2 === 0 ? 'light' : 'dark'}
                data-marked={lastMoveRole !== 'none'}
                data-last-move={lastMoveRole}
                data-square={square}
              >
                {piece && (
                  <span className={styles.piece} data-color={piece.color}>
                    {piece.glyph}{'︎'}
                  </span>
                )}
                {column === 0 && <span className={styles.rankLabel}>{8 - rankIndex}</span>}
                {row === 7 && <span className={styles.fileLabel}>{FILES[fileIndex]}</span>}
              </div>
            )
          }),
        )}
        {arrow !== null && <ArrowOverlay moves={[arrow]} orientation={orientation} />}
      </div>
    </div>
  )
}

/**
 * A move-entry board. Click, keyboard, and drag all produce one UCI move; the server remains
 * responsible for legality and answer validation while accepted moves update the displayed board.
 */
export function InteractiveChessBoard({
  fen,
  orientation,
  label,
  disabled,
  solution,
  onMove,
  line = NO_MOVES,
  initialLastMove = null,
  hintSquare = null,
}: {
  fen: string
  orientation: BoardOrientation
  label: string
  disabled: boolean
  solution: string | null
  onMove: (move: string) => void | boolean | Promise<void | boolean>
  /** Moves already played from {@code fen}, such as accepted Puzzle moves and replies. */
  line?: readonly string[]
  /** The move that produced {@code fen}, highlighted until another move is shown. */
  initialLastMove?: string | null
  /** A square to mark as a hint, described in its accessible name. */
  hintSquare?: string | null
}) {
  // The inputs that define the shown line; the array itself is a new object on every render.
  const inputKey = [fen, solution ?? '', line.join(' '), initialLastMove ?? ''].join('|')
  const [history, setHistory] = useState<BoardStep[]>(
    () => createHistory(fen, solution, line, initialLastMove),
  )
  const [historyIndex, setHistoryIndex] = useState(() => Math.max(0, history.length - 1))
  const [syncedKey, setSyncedKey] = useState(inputKey)
  const [selected, setSelected] = useState<string | null>(null)
  const [dragged, setDragged] = useState<string | null>(null)
  const [arrowStart, setArrowStart] = useState<string | null>(null)
  const [userArrows, setUserArrows] = useState<string[]>([])
  const [annotationMode, setAnnotationMode] = useState(false)
  const [annotationMessage, setAnnotationMessage] = useState<string | null>(null)

  // Reset during render, and only when the inputs change: a reset in an effect would run after
  // the board is shown and could discard a selection the owner made in the meantime.
  if (syncedKey !== inputKey) {
    const nextHistory = createHistory(fen, solution, line, initialLastMove)
    setSyncedKey(inputKey)
    setHistory(nextHistory)
    setHistoryIndex(Math.max(0, nextHistory.length - 1))
    setSelected(null)
    setDragged(null)
    setArrowStart(null)
    setUserArrows([])
    setAnnotationMode(false)
    setAnnotationMessage(null)
  }

  const step = history[historyIndex]
  if (step === undefined) {
    return <p className={styles.unreadable} role="alert">This position could not be drawn.</p>
  }
  const { placement, side, lastMove } = step
  const ranks = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]
  const files = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]

  const submit = async (move: string) => {
    if (!isLegalMove(step, move)) {
      setAnnotationMessage('That move is not legal in this position')
      return
    }
    const previousHistory = history
    const previousIndex = historyIndex
    const nextHistory = [
      ...history.slice(0, historyIndex + 1),
      applyMove(step, move),
    ]
    setUserArrows([])
    setAnnotationMessage(null)
    setHistory(nextHistory)
    setHistoryIndex(nextHistory.length - 1)
    if (solution !== null) return
    const accepted = await onMove(move)
    if (accepted === false) {
      setHistory(previousHistory)
      setHistoryIndex(previousIndex)
    }
  }

  const addArrow = (from: string, to: string) => {
    if (from === to) return
    if (!isChessMovementVector(from, to)) {
      setAnnotationMessage('That arrow does not match a chess move')
      return
    }
    const move = `${from}${to}`
    setUserArrows((current) => current.includes(move) ? current : [...current, move])
    setAnnotationMessage(null)
  }

  const beginArrow = (square: string) => {
    setArrowStart(square)
    setAnnotationMessage(null)
  }

  const choose = (square: string, piece: Piece | null) => {
    if (disabled) return
    if (annotationMode) {
      if (arrowStart === null) {
        beginArrow(square)
      } else {
        addArrow(arrowStart, square)
        setArrowStart(null)
      }
      return
    }
    setUserArrows([])
    setAnnotationMessage(null)
    if (selected === null) {
      if (piece?.color === side) setSelected(square)
      return
    }
    if (selected === square) {
      setSelected(null)
      return
    }
    if (piece?.color === side) {
      setSelected(square)
      return
    }
    const source = pieceForSquare(placement, selected)
    const promotion = source?.kind === 'p' && (square.endsWith('1') || square.endsWith('8')) ? 'q' : ''
    setSelected(null)
    void submit(`${selected}${square}${promotion}`)
  }

  const drop = (event: DragEvent, square: string) => {
    event.preventDefault()
    if (disabled || dragged === null || dragged === square) {
      setDragged(null)
      return
    }
    const source = pieceForSquare(placement, dragged)
    const promotion = source?.kind === 'p' && (square.endsWith('1') || square.endsWith('8')) ? 'q' : ''
    setDragged(null)
    setSelected(null)
    void submit(`${dragged}${square}${promotion}`)
  }

  const startArrow = (event: ReactMouseEvent, square: string) => {
    if (event.button !== 2) return
    event.preventDefault()
    setSelected(null)
    setAnnotationMode(false)
    beginArrow(square)
  }

  const finishArrow = (event: ReactMouseEvent, square: string) => {
    if (event.button !== 2 || arrowStart === null) return
    event.preventDefault()
    addArrow(arrowStart, square)
    setArrowStart(null)
  }

  const toggleAnnotationMode = () => {
    setAnnotationMode((active) => !active)
    setSelected(null)
    setArrowStart(null)
    setAnnotationMessage(null)
  }

  const scrollHistory = (event: ReactWheelEvent<HTMLDivElement>) => {
    if (event.deltaY === 0) return
    const nextIndex = historyIndex + (event.deltaY > 0 ? 1 : -1)
    if (nextIndex < 0 || nextIndex >= history.length) return
    event.preventDefault()
    setHistoryIndex(nextIndex)
    setSelected(null)
    setDragged(null)
    setArrowStart(null)
    setUserArrows([])
    setAnnotationMode(false)
    setAnnotationMessage(null)
  }

  return (
    <div className={styles.frame} role="group" aria-label={label}>
      <div
        className={styles.board}
        onContextMenu={(event) => event.preventDefault()}
        onWheel={scrollHistory}
      >
        {ranks.map((rankIndex, row) =>
          files.map((fileIndex, column) => {
            const square = squareName(rankIndex, fileIndex)
            const piece = placement[rankIndex][fileIndex]
            const lastMoveRole = moveSquareRole(lastMove, square)
            return (
              <button
                key={square}
                className={`${styles.square} ${styles.moveSquare}`}
                type="button"
                data-shade={(rankIndex + fileIndex) % 2 === 0 ? 'light' : 'dark'}
                data-marked={lastMoveRole !== 'none'}
                data-last-move={lastMoveRole}
                data-selected={selected === square || (annotationMode && arrowStart === square)}
                data-hint={hintSquare === square}
                disabled={disabled}
                aria-pressed={selected === square || (annotationMode && arrowStart === square)}
                aria-label={hintSquare === square
                  ? `${squareLabel(square, piece)}, hint: move this piece`
                  : squareLabel(square, piece)}
                onClick={() => choose(square, piece)}
                onMouseDown={(event) => startArrow(event, square)}
                onMouseUp={(event) => finishArrow(event, square)}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => drop(event, square)}
              >
                {piece && (
                  <span
                    className={styles.piece}
                    data-color={piece.color}
                    draggable={!disabled && piece.color === side}
                    onDragStart={() => {
                      setUserArrows([])
                      setDragged(square)
                    }}
                    onDragEnd={() => setDragged(null)}
                  >
                    {piece.glyph}{'︎'}
                  </span>
                )}
                {column === 0 && <span className={styles.rankLabel}>{8 - rankIndex}</span>}
                {row === 7 && <span className={styles.fileLabel}>{FILES[fileIndex]}</span>}
              </button>
            )
          }),
        )}
        {userArrows.length > 0 && (
          <ArrowOverlay
            moves={userArrows}
            orientation={orientation}
            testId="user-drawn-arrow"
          />
        )}
      </div>
      <div className={styles.annotationControls}>
        <button
          className={styles.annotationToggle}
          type="button"
          aria-pressed={annotationMode}
          disabled={disabled}
          onClick={toggleAnnotationMode}
        >
          Draw arrow
        </button>
        <span className={styles.annotationStatus} aria-live="polite">
          {annotationMessage ?? (annotationMode
            ? arrowStart === null ? 'Choose the start square' : 'Choose the end square'
            : `Right-drag shortcut · History ${historyIndex + 1} of ${history.length}`)}
        </span>
      </div>
    </div>
  )
}

function pieceForSquare(placement: (Piece | null)[][], square: string) {
  const file = FILES.indexOf(square[0])
  const rank = 8 - Number(square[1])
  return placement[rank]?.[file] ?? null
}

function sideFromFen(fen: string): Side {
  return fen.split(' ')[1] === 'b' ? 'black' : 'white'
}

function oppositeSide(side: Side): Side {
  return side === 'white' ? 'black' : 'white'
}

const NO_MOVES: readonly string[] = []

function createHistory(
  fen: string,
  solution: string | null,
  line: readonly string[] = NO_MOVES,
  initialLastMove: string | null = null,
): BoardStep[] {
  const initial = parsePlacement(fen)
  if (initial === null) return []
  const fields = fen.split(' ')
  const side = sideFromFen(fen)
  const initialStep: BoardStep = {
    placement: initial,
    side,
    lastMove: initialLastMove,
    castling: fields[2] === undefined || fields[2] === '-' ? '' : fields[2],
    enPassant: fields[3] === undefined || fields[3] === '-' ? null : fields[3],
  }
  const history: BoardStep[] = [initialStep]
  for (const move of line) {
    history.push(applyMove(history[history.length - 1], move))
  }
  if (solution !== null) {
    history.push(applyMove(history[history.length - 1], solution))
  }
  return history
}

function squareVector(from: string, to: string) {
  return {
    file: FILES.indexOf(to[0]) - FILES.indexOf(from[0]),
    rank: Number(to[1]) - Number(from[1]),
  }
}

function isKnightVector(file: number, rank: number) {
  const absoluteFile = Math.abs(file)
  const absoluteRank = Math.abs(rank)
  return (absoluteFile === 1 && absoluteRank === 2) || (absoluteFile === 2 && absoluteRank === 1)
}

function isChessMovementVector(from: string, to: string) {
  const { file, rank } = squareVector(from, to)
  const absoluteFile = Math.abs(file)
  const absoluteRank = Math.abs(rank)
  if (absoluteFile === 0 && absoluteRank === 0) return false
  return file === 0 || rank === 0 || absoluteFile === absoluteRank || isKnightVector(file, rank)
}

function applyMove(step: BoardStep, move: string): BoardStep {
  const { placement } = step
  const fromFile = FILES.indexOf(move[0])
  const fromRank = 8 - Number(move[1])
  const toFile = FILES.indexOf(move[2])
  const toRank = 8 - Number(move[3])
  const source = placement[fromRank]?.[fromFile]
  if (source === undefined || source === null || placement[toRank]?.[toFile] === undefined) return step

  const next = placement.map((rank) => [...rank])
  if (source.kind === 'p' && fromFile !== toFile && next[toRank][toFile] === null && step.enPassant === move.slice(2, 4)) {
    next[fromRank][toFile] = null
  }
  if (source.kind === 'k' && Math.abs(toFile - fromFile) === 2) {
    const rookFile = toFile > fromFile ? 7 : 0
    const rookDestination = toFile > fromFile ? 5 : 3
    next[fromRank][rookDestination] = next[fromRank][rookFile]
    next[fromRank][rookFile] = null
  }

  const promotion = move[4]
  next[fromRank][fromFile] = null
  next[toRank][toFile] = promotion === undefined
    ? source
    : { ...source, kind: promotion, glyph: GLYPHS[promotion] }

  let castling = step.castling
  const removeCastling = (rights: string) => {
    castling = [...castling].filter((right) => !rights.includes(right)).join('')
  }
  if (source.kind === 'k') removeCastling(source.color === 'white' ? 'KQ' : 'kq')
  if (move.slice(0, 2) === 'a1' || move.slice(2, 4) === 'a1') removeCastling('Q')
  if (move.slice(0, 2) === 'h1' || move.slice(2, 4) === 'h1') removeCastling('K')
  if (move.slice(0, 2) === 'a8' || move.slice(2, 4) === 'a8') removeCastling('q')
  if (move.slice(0, 2) === 'h8' || move.slice(2, 4) === 'h8') removeCastling('k')

  const enPassant = source.kind === 'p' && Math.abs(Number(move[3]) - Number(move[1])) === 2
    ? `${move[0]}${(Number(move[1]) + Number(move[3])) / 2}`
    : null
  return {
    placement: next,
    side: oppositeSide(step.side),
    lastMove: move,
    castling,
    enPassant,
  }
}

function isLegalMove(step: BoardStep, move: string) {
  if (!/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(move)) return false
  const from = move.slice(0, 2)
  const to = move.slice(2, 4)
  const source = pieceForSquare(step.placement, from)
  const target = pieceForSquare(step.placement, to)
  if (source === null || source.color !== step.side || target?.color === source.color || target?.kind === 'k') return false

  const { file, rank } = squareVector(from, to)
  const absoluteFile = Math.abs(file)
  const absoluteRank = Math.abs(rank)
  const promotion = move[4]
  const reachesLastRank = source.kind === 'p' && (to[1] === '1' || to[1] === '8')
  if ((promotion !== undefined) !== reachesLastRank) return false

  let followsPieceRules = false
  if (source.kind === 'p') {
    const direction = source.color === 'white' ? 1 : -1
    const startRank = source.color === 'white' ? '2' : '7'
    if (file === 0 && target === null) {
      followsPieceRules = rank === direction
        || (from[1] === startRank && rank === direction * 2 && pathIsClear(step.placement, from, to))
    } else if (absoluteFile === 1 && rank === direction) {
      followsPieceRules = target?.color === oppositeSide(source.color)
        || (step.enPassant === to && isEnPassantCapture(step.placement, source.color, from, to))
    }
  } else if (source.kind === 'n') {
    followsPieceRules = isKnightVector(file, rank)
  } else if (source.kind === 'b') {
    followsPieceRules = absoluteFile === absoluteRank && pathIsClear(step.placement, from, to)
  } else if (source.kind === 'r') {
    followsPieceRules = (file === 0 || rank === 0) && pathIsClear(step.placement, from, to)
  } else if (source.kind === 'q') {
    followsPieceRules = (file === 0 || rank === 0 || absoluteFile === absoluteRank)
      && pathIsClear(step.placement, from, to)
  } else if (source.kind === 'k') {
    followsPieceRules = absoluteFile <= 1 && absoluteRank <= 1
    if (rank === 0 && absoluteFile === 2) followsPieceRules = canCastle(step, from, to)
  }
  if (!followsPieceRules) return false

  return !kingIsAttacked(applyMove(step, move).placement, source.color)
}

function pathIsClear(placement: Placement, from: string, to: string) {
  const fromFile = FILES.indexOf(from[0])
  const fromRank = 8 - Number(from[1])
  const toFile = FILES.indexOf(to[0])
  const toRank = 8 - Number(to[1])
  const fileStep = Math.sign(toFile - fromFile)
  const rankStep = Math.sign(toRank - fromRank)
  let file = fromFile + fileStep
  let rank = fromRank + rankStep
  while (file !== toFile || rank !== toRank) {
    if (placement[rank]?.[file] !== null) return false
    file += fileStep
    rank += rankStep
  }
  return true
}

function isEnPassantCapture(placement: Placement, side: Side, from: string, to: string) {
  const captured = pieceForSquare(placement, `${to[0]}${from[1]}`)
  return captured?.kind === 'p' && captured.color === oppositeSide(side)
}

function canCastle(step: BoardStep, from: string, to: string) {
  const side = step.side
  const homeRank = side === 'white' ? '1' : '8'
  if (from !== `e${homeRank}` || (to !== `g${homeRank}` && to !== `c${homeRank}`)) return false
  const kingSide = to[0] === 'g'
  const requiredRight = side === 'white' ? (kingSide ? 'K' : 'Q') : (kingSide ? 'k' : 'q')
  if (!step.castling.includes(requiredRight)) return false
  const rookSquare = `${kingSide ? 'h' : 'a'}${homeRank}`
  const rook = pieceForSquare(step.placement, rookSquare)
  if (rook?.kind !== 'r' || rook.color !== side || !pathIsClear(step.placement, from, rookSquare)) return false
  if (kingIsAttacked(step.placement, side)) return false

  const transit = `${kingSide ? 'f' : 'd'}${homeRank}`
  const transitPlacement = movePieceOnly(step.placement, from, transit)
  return !squareIsAttacked(transitPlacement, transit, oppositeSide(side))
}

function movePieceOnly(placement: Placement, from: string, to: string) {
  const next = placement.map((rank) => [...rank])
  const fromFile = FILES.indexOf(from[0])
  const fromRank = 8 - Number(from[1])
  const toFile = FILES.indexOf(to[0])
  const toRank = 8 - Number(to[1])
  next[toRank][toFile] = next[fromRank][fromFile]
  next[fromRank][fromFile] = null
  return next
}

function kingIsAttacked(placement: Placement, side: Side) {
  for (let rank = 0; rank < 8; rank += 1) {
    for (let file = 0; file < 8; file += 1) {
      const piece = placement[rank][file]
      if (piece?.kind === 'k' && piece.color === side) {
        return squareIsAttacked(placement, squareName(rank, file), oppositeSide(side))
      }
    }
  }
  return true
}

function squareIsAttacked(placement: Placement, target: string, attacker: Side) {
  for (let rankIndex = 0; rankIndex < 8; rankIndex += 1) {
    for (let fileIndex = 0; fileIndex < 8; fileIndex += 1) {
      const piece = placement[rankIndex][fileIndex]
      if (piece?.color !== attacker) continue
      const from = squareName(rankIndex, fileIndex)
      const vector = squareVector(from, target)
      const absoluteFile = Math.abs(vector.file)
      const absoluteRank = Math.abs(vector.rank)
      if (piece.kind === 'p') {
        const direction = piece.color === 'white' ? 1 : -1
        if (absoluteFile === 1 && vector.rank === direction) return true
      } else if (piece.kind === 'n' && isKnightVector(vector.file, vector.rank)) {
        return true
      } else if (piece.kind === 'k' && absoluteFile <= 1 && absoluteRank <= 1) {
        return true
      } else if (piece.kind === 'b' && absoluteFile === absoluteRank && pathIsClear(placement, from, target)) {
        return true
      } else if (piece.kind === 'r' && (vector.file === 0 || vector.rank === 0) && pathIsClear(placement, from, target)) {
        return true
      } else if (piece.kind === 'q'
        && (vector.file === 0 || vector.rank === 0 || absoluteFile === absoluteRank)
        && pathIsClear(placement, from, target)) {
        return true
      }
    }
  }
  return false
}

function squareLabel(square: string, piece: Piece | null) {
  if (piece === null) return `${square}, empty`
  const names: Record<string, string> = {
    p: 'pawn', n: 'knight', b: 'bishop', r: 'rook', q: 'queen', k: 'king',
  }
  return `${square}, ${piece.color} ${names[piece.kind]}`
}

function moveSquareRole(move: string | null, square: string): 'from' | 'to' | 'none' {
  if (move === null) return 'none'
  if (move.slice(0, 2) === square) return 'from'
  if (move.slice(2, 4) === square) return 'to'
  return 'none'
}

function ArrowOverlay({
  moves,
  orientation,
  testId = 'best-move-arrow',
}: {
  moves: string[]
  orientation: BoardOrientation
  testId?: string
}) {
  const reactId = useId()
  const markerId = `board-arrow-head-${reactId.replace(/:/g, '')}`
  const arrows = moves.reduce<ArrowGeometry[]>((current, move) => {
    const from = screenPosition(move.slice(0, 2), orientation)
    const to = screenPosition(move.slice(2, 4), orientation)
    const start = { x: from.x + 0.5, y: from.y + 0.5 }
    const destination = { x: to.x + 0.5, y: to.y + 0.5 }
    const dx = destination.x - start.x
    const dy = destination.y - start.y
    const length = Math.hypot(dx, dy)
    if (length === 0) return current
    if (isKnightVector(dx, dy)) {
      const corner = Math.abs(dx) > Math.abs(dy)
        ? { x: destination.x, y: start.y }
        : { x: start.x, y: destination.y }
      const finalDx = destination.x - corner.x
      const finalDy = destination.y - corner.y
      const finalLength = Math.hypot(finalDx, finalDy)
      const shorten = 0.3 / finalLength
      current.push({
        kind: 'knight',
        move,
        points: [
          start,
          corner,
          { x: destination.x - finalDx * shorten, y: destination.y - finalDy * shorten },
        ],
      })
      return current
    }
    // Stop short of the destination centre so the head sits inside the square.
    const shorten = 0.3 / length
    current.push({
      kind: 'straight',
      move,
      start,
      end: { x: destination.x - dx * shorten, y: destination.y - dy * shorten },
    })
    return current
  }, [])
  if (arrows.length === 0) return null
  return (
    <svg className={styles.arrow} viewBox="0 0 8 8" data-testid={testId}>
      <defs>
        <marker id={markerId} viewBox="0 0 4 4" refX="2" refY="2" markerWidth="3" markerHeight="3" orient="auto">
          <path d="M0 0 4 2 0 4z" />
        </marker>
      </defs>
      {arrows.map((arrow) => arrow.kind === 'knight'
        ? (
            <polyline
              key={arrow.move}
              points={arrow.points.map(({ x, y }) => `${x},${y}`).join(' ')}
              markerEnd={`url(#${markerId})`}
            />
          )
        : (
            <line
              key={arrow.move}
              x1={arrow.start.x}
              y1={arrow.start.y}
              x2={arrow.end.x}
              y2={arrow.end.y}
              markerEnd={`url(#${markerId})`}
            />
          ))}
    </svg>
  )
}
