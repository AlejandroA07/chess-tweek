/**
 * Plays one UCI move on a FEN and returns a FEN whose piece placement and side to move are
 * updated. It is only for drawing engine lines whose moves the server already checked as legal
 * (`pvSan` stops at the first illegal move), so it handles moving, capturing, castling, en
 * passant, and promotion without validating legality. Castling rights, the en-passant square, and
 * the move counters are not tracked. Returns null when the move does not start on a piece.
 */
export function playUci(fen: string, uci: string): string | null {
  const [placement, side] = fen.split(' ')
  const grid = expand(placement)
  if (grid === null || !/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(uci)) return null
  const from = square(uci.slice(0, 2))
  const to = square(uci.slice(2, 4))
  const piece = grid[from.row][from.column]
  if (piece === '') return null
  const white = piece === piece.toUpperCase()

  // En passant: a pawn moving diagonally to an empty square captures the pawn beside it.
  if (piece.toLowerCase() === 'p' && from.column !== to.column && grid[to.row][to.column] === '') {
    grid[from.row][to.column] = ''
  }
  // Castling: the king moves two files, and the rook jumps to the square it crossed.
  if (piece.toLowerCase() === 'k' && Math.abs(to.column - from.column) === 2) {
    const kingside = to.column > from.column
    const rookFrom = kingside ? 7 : 0
    const rookTo = kingside ? 5 : 3
    grid[from.row][rookTo] = grid[from.row][rookFrom]
    grid[from.row][rookFrom] = ''
  }
  grid[from.row][from.column] = ''
  const promotion = uci[4]
  grid[to.row][to.column] = promotion ? (white ? promotion.toUpperCase() : promotion) : piece

  const nextSide = side === 'b' ? 'w' : 'b'
  return `${collapse(grid)} ${nextSide} - - 0 1`
}

function square(name: string) {
  return { row: 8 - Number(name[1]), column: name.charCodeAt(0) - 'a'.charCodeAt(0) }
}

function expand(placement: string | undefined): string[][] | null {
  const rows = placement?.split('/') ?? []
  if (rows.length !== 8) return null
  const grid: string[][] = []
  for (const row of rows) {
    const squares: string[] = []
    for (const character of row) {
      if (/[1-8]/.test(character)) squares.push(...Array<string>(Number(character)).fill(''))
      else if (/[pnbrqkPNBRQK]/.test(character)) squares.push(character)
      else return null
    }
    if (squares.length !== 8) return null
    grid.push(squares)
  }
  return grid
}

function collapse(grid: string[][]) {
  return grid
    .map((row) => {
      let text = ''
      let empty = 0
      for (const piece of row) {
        if (piece === '') {
          empty += 1
        } else {
          if (empty > 0) text += String(empty)
          empty = 0
          text += piece
        }
      }
      return empty > 0 ? text + String(empty) : text
    })
    .join('/')
}
