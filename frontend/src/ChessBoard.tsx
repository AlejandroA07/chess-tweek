import { useState, type DragEvent } from 'react'
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
  const marked = highlight === null ? [] : [highlight.slice(0, 2), highlight.slice(2, 4)]
  const ranks = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]
  const files = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]

  return (
    <div className={styles.frame} role="img" aria-label={label}>
      <div className={styles.board} aria-hidden="true">
        {ranks.map((rankIndex, row) =>
          files.map((fileIndex, column) => {
            const square = squareName(rankIndex, fileIndex)
            const piece = placement[rankIndex][fileIndex]
            return (
              <div
                key={square}
                className={styles.square}
                data-shade={(rankIndex + fileIndex) % 2 === 0 ? 'light' : 'dark'}
                data-marked={marked.includes(square)}
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
        {arrow !== null && <Arrow move={arrow} orientation={orientation} />}
      </div>
    </div>
  )
}

/**
 * A controlled move-entry board. Click, keyboard, and drag all produce one UCI move; the server
 * remains responsible for legality and answer validation.
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
  onMove: (move: string) => void
}) {
  const placement = parsePlacement(fen)
  const [selected, setSelected] = useState<string | null>(null)
  const [dragged, setDragged] = useState<string | null>(null)
  if (placement === null) {
    return <p className={styles.unreadable} role="alert">This position could not be drawn.</p>
  }
  const side = fen.split(' ')[1] === 'b' ? 'black' : 'white'
  const ranks = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]
  const files = orientation === 'white' ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0]
  const marked = solution === null ? [] : [solution.slice(0, 2), solution.slice(2, 4)]

  const choose = (square: string, piece: Piece | null) => {
    if (disabled) return
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
    onMove(`${selected}${square}${promotion}`)
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
    onMove(`${dragged}${square}${promotion}`)
  }

  return (
    <div className={styles.frame} role="group" aria-label={label}>
      <div className={styles.board}>
        {ranks.map((rankIndex, row) =>
          files.map((fileIndex, column) => {
            const square = squareName(rankIndex, fileIndex)
            const piece = placement[rankIndex][fileIndex]
            return (
              <button
                key={square}
                className={styles.moveSquare}
                type="button"
                data-shade={(rankIndex + fileIndex) % 2 === 0 ? 'light' : 'dark'}
                data-marked={marked.includes(square)}
                data-selected={selected === square}
                disabled={disabled}
                aria-pressed={selected === square}
                aria-label={squareLabel(square, piece)}
                onClick={() => choose(square, piece)}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => drop(event, square)}
              >
                {piece && (
                  <span
                    className={styles.piece}
                    data-color={piece.color}
                    draggable={!disabled && piece.color === side}
                    onDragStart={() => setDragged(square)}
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
        {solution !== null && <Arrow move={solution} orientation={orientation} />}
      </div>
    </div>
  )
}

function pieceForSquare(placement: (Piece | null)[][], square: string) {
  const file = FILES.indexOf(square[0])
  const rank = 8 - Number(square[1])
  return placement[rank]?.[file] ?? null
}

function squareLabel(square: string, piece: Piece | null) {
  if (piece === null) return `${square}, empty`
  const names: Record<string, string> = {
    p: 'pawn', n: 'knight', b: 'bishop', r: 'rook', q: 'queen', k: 'king',
  }
  return `${square}, ${piece.color} ${names[piece.kind]}`
}

function Arrow({ move, orientation }: { move: string; orientation: BoardOrientation }) {
  const from = screenPosition(move.slice(0, 2), orientation)
  const to = screenPosition(move.slice(2, 4), orientation)
  const dx = to.x - from.x
  const dy = to.y - from.y
  const length = Math.hypot(dx, dy)
  if (length === 0) return null
  // Stop short of the destination centre so the head sits inside the square.
  const shorten = 0.3 / length
  return (
    <svg className={styles.arrow} viewBox="0 0 8 8" data-testid="best-move-arrow">
      <defs>
        <marker id="board-arrow-head" viewBox="0 0 4 4" refX="2" refY="2" markerWidth="3" markerHeight="3" orient="auto">
          <path d="M0 0 4 2 0 4z" />
        </marker>
      </defs>
      <line
        x1={from.x + 0.5}
        y1={from.y + 0.5}
        x2={to.x + 0.5 - dx * shorten}
        y2={to.y + 0.5 - dy * shorten}
        markerEnd="url(#board-arrow-head)"
      />
    </svg>
  )
}
