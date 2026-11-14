import { describe, expect, it } from 'vitest'
import { playUci } from './boardPosition'

const placement = (fen: string | null) => fen?.split(' ').slice(0, 2).join(' ')

describe('playUci', () => {
  it('moves and captures, switching the side to move', () => {
    const start = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'
    expect(placement(playUci(start, 'e2e4'))).toBe('rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b')
    // The Caro-Kann trick from the owner's game: 6...Bf5 7.Bxf5 Qa5+.
    const before = 'rnbqkb1r/pp2pppp/2p5/8/3PB3/8/PPP2PPP/R1BQK1NR b KQkq - 0 6'
    const afterBf5 = playUci(before, 'c8f5')
    const afterBxf5 = playUci(afterBf5 ?? '', 'e4f5')
    expect(placement(playUci(afterBxf5 ?? '', 'd8a5'))).toBe('rn2kb1r/pp2pppp/2p5/q4B2/3P4/8/PPP2PPP/R1BQK1NR w')
  })

  it('castles on both sides', () => {
    const fen = 'r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1'
    expect(placement(playUci(fen, 'e1g1'))).toBe('r3k2r/8/8/8/8/8/8/R4RK1 b')
    expect(placement(playUci(fen, 'e1c1'))).toBe('r3k2r/8/8/8/8/8/8/2KR3R b')
    expect(placement(playUci(fen.replace(' w ', ' b '), 'e8c8'))).toBe('2kr3r/8/8/8/8/8/8/R3K2R w')
  })

  it('captures en passant and promotes', () => {
    expect(placement(playUci('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1', 'e5d6'))).toBe('4k3/8/3P4/8/8/8/8/4K3 b')
    expect(placement(playUci('4k3/P7/8/8/8/8/8/4K3 w - - 0 1', 'a7a8n'))).toBe('N3k3/8/8/8/8/8/8/4K3 b')
    expect(placement(playUci('4k3/8/8/8/8/8/p7/4K3 b - - 0 1', 'a2a1q'))).toBe('4k3/8/8/8/8/8/8/q3K3 w')
  })

  it('refuses a move that does not start on a piece or a malformed position', () => {
    expect(playUci('4k3/8/8/8/8/8/8/4K3 w - - 0 1', 'a2a3')).toBeNull()
    expect(playUci('not a fen', 'e2e4')).toBeNull()
    expect(playUci('4k3/8/8/8/8/8/8/4K3 w - - 0 1', 'e1e9')).toBeNull()
  })
})
