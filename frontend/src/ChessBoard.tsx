import { useEffect, useId, useState, type DragEvent, type MouseEvent as ReactMouseEvent } from 'react'
import styles from './ChessBoard.module.css'

export type BoardOrientation = 'white' | 'black'

type Piece = { color: 'white' | 'black'; glyph: string; kind: string }

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
}: {
  fen: string
  orientation: BoardOrientation
  label: string
  disabled: boolean
  solution: string | null
  onMove: (move: string) => void | boolean | Promise<void | boolean>
}) {
  const [placement, setPlacement] = useState(() => parsePlacement(fen))
  const [selected, setSelected] = useState<string | null>(null)
  const [dragged, setDragged] = useState<string | null>(null)
  const [arrowStart, setArrowStart] = useState<string | null>(null)
  const [userArrows, setUserArrows] = useState<string[]>([])
  const [annotationMode, setAnnotationMode] = useState(false)
  const [lastMove, setLastMove] = useState<string | null>(solution)

  useEffect(() => {
    const initial = parsePlacement(fen)
    setPlacement(solution === null || initial === null ? initial : applyMove(initial, solution))
    setSelected(null)
    setDragged(null)
    setArrowStart(null)
    setUserArrows([])
    setAnnotationMode(false)
    setLastMove(solution)
  }, [fen, solution])

  if (placement === null) {
    return <p className={styles.unreadable} role="alert">This position could not be drawn.</p>
  }
  const side = fen.split(' ')[1] === 'b' ? 'black' : 'white'
  const ranks = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]
  const files = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]

  const submit = async (move: string) => {
    const previous = placement
    const previousLastMove = lastMove
    setUserArrows([])
    setPlacement(applyMove(previous, move))
    setLastMove(move)
    const accepted = await onMove(move)
    if (accepted === false) {
      setPlacement(previous)
      setLastMove(previousLastMove)
    }
  }

  const addArrow = (from: string, to: string) => {
    if (from === to) return
    const move = `${from}${to}`
    setUserArrows((current) => current.includes(move) ? current : [...current, move])
  }

  const choose = (square: string, piece: Piece | null) => {
    if (disabled) return
    if (annotationMode) {
      if (arrowStart === null) {
        setArrowStart(square)
      } else {
        addArrow(arrowStart, square)
        setArrowStart(null)
      }
      return
    }
    setUserArrows([])
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
    setArrowStart(square)
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
  }

  return (
    <div className={styles.frame} role="group" aria-label={label}>
      <div className={styles.board} onContextMenu={(event) => event.preventDefault()}>
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
          {annotationMode
            ? arrowStart === null ? 'Choose the start square' : 'Choose the end square'
            : 'Right-drag shortcut'}
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
  testId = 'best-move-arrow',
}: {
  moves: string[]
  orientation: BoardOrientation
  testId?: string
}) {
  const reactId = useId()
  const markerId = `board-arrow-head-${reactId.replace(/:/g, '')}`
  const arrows = moves.flatMap((move) => {
    const from = screenPosition(move.slice(0, 2), orientation)
    const to = screenPosition(move.slice(2, 4), orientation)
    const dx = to.x - from.x
    const dy = to.y - from.y
    const length = Math.hypot(dx, dy)
    if (length === 0) return []
    // Stop short of the destination centre so the head sits inside the square.
    const shorten = 0.3 / length
    return [{ move, from, to, dx, dy, shorten }]
  })
  if (arrows.length === 0) return null
  return (
    <svg className={styles.arrow} viewBox="0 0 8 8" data-testid={testId}>
      <defs>
        <marker id={markerId} viewBox="0 0 4 4" refX="2" refY="2" markerWidth="3" markerHeight="3" orient="auto">
          <path d="M0 0 4 2 0 4z" />
        </marker>
      </defs>
      {arrows.map(({ move, from, to, dx, dy, shorten }) => (
        <line
          key={move}
          x1={from.x + 0.5}
          y1={from.y + 0.5}
          x2={to.x + 0.5 - dx * shorten}
          y2={to.y + 0.5 - dy * shorten}
          markerEnd={`url(#${markerId})`}
        />
      ))}
    </svg>
  )
}
