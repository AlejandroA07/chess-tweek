import {
  useEffect,
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
type BoardStep = { placement: Placement; side: Side; lastMove: string | null }
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
        {arrow !== null && <ArrowOverlay moves={[arrow]} orientation={orientation} placement={placement} />}
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
}: {
  fen: string
  orientation: BoardOrientation
  label: string
  disabled: boolean
  solution: string | null
  onMove: (move: string) => void | boolean | Promise<void | boolean>
}) {
  const [history, setHistory] = useState<BoardStep[]>(() => createHistory(fen, solution))
  const [historyIndex, setHistoryIndex] = useState(() => Math.max(0, createHistory(fen, solution).length - 1))
  const [selected, setSelected] = useState<string | null>(null)
  const [dragged, setDragged] = useState<string | null>(null)
  const [arrowStart, setArrowStart] = useState<string | null>(null)
  const [userArrows, setUserArrows] = useState<string[]>([])
  const [annotationMode, setAnnotationMode] = useState(false)
  const [annotationMessage, setAnnotationMessage] = useState<string | null>(null)

  useEffect(() => {
    const nextHistory = createHistory(fen, solution)
    setHistory(nextHistory)
    setHistoryIndex(Math.max(0, nextHistory.length - 1))
    setSelected(null)
    setDragged(null)
    setArrowStart(null)
    setUserArrows([])
    setAnnotationMode(false)
    setAnnotationMessage(null)
  }, [fen, solution])

  const step = history[historyIndex]
  if (step === undefined) {
    return <p className={styles.unreadable} role="alert">This position could not be drawn.</p>
  }
  const { placement, side, lastMove } = step
  const ranks = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]
  const files = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]

  const submit = async (move: string) => {
    const previousHistory = history
    const previousIndex = historyIndex
    const nextHistory = [
      ...history.slice(0, historyIndex + 1),
      { placement: applyMove(placement, move), side: oppositeSide(side), lastMove: move },
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
    const source = pieceForSquare(placement, from)
    if (source === null) {
      setAnnotationMessage('Start from a piece')
      return
    }
    if (!canPieceTrace(source, from, to)) {
      setAnnotationMessage('That piece cannot move that way')
      return
    }
    const move = `${from}${to}`
    setUserArrows((current) => current.includes(move) ? current : [...current, move])
    setAnnotationMessage(null)
  }

  const beginArrow = (square: string) => {
    if (pieceForSquare(placement, square) === null) {
      setArrowStart(null)
      setAnnotationMessage('Start from a piece')
      return
    }
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
                disabled={disabled}
                aria-pressed={selected === square || (annotationMode && arrowStart === square)}
                aria-label={squareLabel(square, piece)}
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
            placement={placement}
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

function createHistory(fen: string, solution: string | null): BoardStep[] {
  const initial = parsePlacement(fen)
  if (initial === null) return []
  const side = sideFromFen(fen)
  const history: BoardStep[] = [{ placement: initial, side, lastMove: null }]
  if (solution !== null) {
    history.push({
      placement: applyMove(initial, solution),
      side: oppositeSide(side),
      lastMove: solution,
    })
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

function canPieceTrace(piece: Piece, from: string, to: string) {
  const { file, rank } = squareVector(from, to)
  const absoluteFile = Math.abs(file)
  const absoluteRank = Math.abs(rank)
  if (absoluteFile === 0 && absoluteRank === 0) return false
  if (piece.kind === 'n') return isKnightVector(file, rank)
  if (piece.kind === 'b') return absoluteFile === absoluteRank
  if (piece.kind === 'r') return file === 0 || rank === 0
  if (piece.kind === 'q') return file === 0 || rank === 0 || absoluteFile === absoluteRank
  if (piece.kind === 'k') {
    const castlingVector = from[0] === 'e'
      && (from[1] === '1' || from[1] === '8')
      && rank === 0
      && absoluteFile === 2
    return castlingVector || (absoluteFile <= 1 && absoluteRank <= 1)
  }
  if (piece.kind === 'p') {
    const direction = piece.color === 'white' ? 1 : -1
    const startRank = piece.color === 'white' ? '2' : '7'
    if (file === 0) return rank === direction || (from[1] === startRank && rank === direction * 2)
    return absoluteFile === 1 && rank === direction
  }
  return false
}

function applyMove(placement: (Piece | null)[][], move: string) {
  const fromFile = FILES.indexOf(move[0])
  const fromRank = 8 - Number(move[1])
  const toFile = FILES.indexOf(move[2])
  const toRank = 8 - Number(move[3])
  const source = placement[fromRank]?.[fromFile]
  if (source === undefined || source === null || placement[toRank]?.[toFile] === undefined) return placement

  const next = placement.map((rank) => [...rank])
  if (source.kind === 'p' && fromFile !== toFile && next[toRank][toFile] === null) {
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
  return next
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
  placement,
  testId = 'best-move-arrow',
}: {
  moves: string[]
  orientation: BoardOrientation
  placement: Placement
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
    const source = pieceForSquare(placement, move.slice(0, 2))
    if (source?.kind === 'n' && isKnightVector(dx, dy)) {
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
