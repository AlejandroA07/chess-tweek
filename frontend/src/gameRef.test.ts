import { describe, expect, it } from 'vitest'
import { gameApiPath, gameKey, isGameRef, parseGameKey } from './gameRef'

describe('game references', () => {
  it('accepts each site only with its own ID format', () => {
    expect(isGameRef('lichess', 'ye7Uy9js')).toBe(true)
    expect(isGameRef('chesscom', '128564733217')).toBe(true)
    expect(isGameRef('chesscom', 'ye7Uy9js')).toBe(false)
    expect(isGameRef('lichess', '1234')).toBe(false)
    expect(isGameRef('chess.com', '128564733217')).toBe(false)
    expect(isGameRef('lichess', 12345678)).toBe(false)
  })

  it('round-trips a key and refuses anything else', () => {
    expect(parseGameKey(gameKey({ source: 'chesscom', id: '128564733217' })))
      .toEqual({ source: 'chesscom', id: '128564733217' })
    expect(parseGameKey('ye7Uy9js')).toBeNull()
    expect(parseGameKey('lichess/ye7Uy9js/extra')).toBeNull()
    expect(parseGameKey('lichess/')).toBeNull()
  })

  it('builds the API path from the site and ID', () => {
    expect(gameApiPath({ source: 'lichess', id: 'ye7Uy9js' })).toBe('/api/games/lichess/ye7Uy9js')
  })
})
