import { describe, expect, it } from 'vitest'
import { coachCard, tacticLabel } from './coach'
import type { JudgedMove, Ply } from './reviewData'

const line = (move: string, moveSan: string, pv: string[], pvSan: string[]) => ({
  move, moveSan, cp: -28, mate: null, win: 3, draw: 958, loss: 39, pv, pvSan,
})

// The owner's Caro-Kann: 6...e6 instead of 6...Bf5 7.Bxf5 Qa5+ 8.Bd2 Qxf5.
const caroKann: JudgedMove = {
  ply: 12,
  san: 'e6',
  uci: 'e7e6',
  fenBefore: 'rnbqkb1r/pp2pppp/2p5/8/3PB3/8/PPP2PPP/R1BQK1NR b KQkq - 0 6',
  phase: 'opening',
  pass: 'deep',
  label: 'mistake',
  mateEvent: 'none',
  playedBest: false,
  bestMove: 'c8f5',
  bestMoveSan: 'Bf5',
  expectedBefore: 964,
  expectedAfter: 581,
  loss: 383,
  afterTerminal: 'none',
  before: [line('c8f5', 'Bf5', ['c8f5', 'e4f5', 'd8a5', 'c1d2', 'a5f5', 'g1f3'], ['Bf5', 'Bxf5', 'Qa5+', 'Bd2', 'Qxf5', 'Nf3'])],
  after: [line('g1f3', 'Nf3', ['g1f3', 'b8d7', 'd1e2'], ['Nf3', 'Nd7', 'Qe2'])],
  tactics: [{ theme: 'fork', kind: 'missed', piece: 'queen', square: 'a5', targets: ['e1', 'f5'] }],
}
const plies: Ply[] = Array.from({ length: 12 }, (_, index) => ({
  ply: index + 1,
  color: index % 2 === 0 ? 'white' : 'black',
  san: 'x',
  uci: 'a2a3',
  fen: index === 11 ? 'rnbqkb1r/pp3ppp/2p1p3/8/3PB3/8/PPP2PPP/R1BQK1NR w KQkq - 0 7' : '8/8/8/8/8/8/8/8 w - - 0 1',
}))

describe('coach cards', () => {
  it('explains the missed queen fork in the owner’s Caro-Kann', () => {
    const card = coachCard(caroKann, plies, true)
    expect(card.why).toBe(
      '6… e6 lowered your expected score from 48% to 29% (19.1 points). The engine preferred 6… Bf5, which keeps it near 48%. '
      + 'The best line has your queen reach a5, where it attacks the king on e1 and the bishop on f5 at once and wins material.',
    )
    expect(card.threat).toBe(
      'After 6… e6, your opponent has no capture or check that wins at once; the best reply is the quiet 7. Nf3, continuing 7. Nf3 Nd7 8. Qe2. '
      + 'The real problem was the chance you missed, not a threat against you. Best line instead: 6… Bf5 7. Bxf5 Qa5+ 8. Bd2 Qxf5 9. Nf3.',
    )
    expect(card.train).toEqual(['Fork puzzles (pattern theme: fork).'])
  })

  it('names the side by color for the opponent’s moves', () => {
    expect(coachCard(caroKann, plies, false).why).toContain('lowered Black’s expected score')
    expect(coachCard(caroKann, plies, false).why).toContain('has Black’s queen reach a5')
  })

  it('explains positional mistakes without inventing a tactic and trains by phase', () => {
    const quiet = { ...caroKann, tactics: [] }
    const card = coachCard(quiet, plies, true)
    expect(card.why).toContain('No forcing tactic was found: the difference is positional.')
    expect(card.train).toEqual(['Opening study: compare this line with the plans of your opening.'])
    expect(coachCard({ ...quiet, phase: 'endgame' }, plies, true).train)
      .toEqual(['Endgame technique (pattern themes: pawnEndgame, rookEndgame).'])
  })

  it('explains a hung piece and a capture reply', () => {
    const hung = {
      ...caroKann,
      after: [line('e4h7', 'Bxh7', ['e4h7'], ['Bxh7'])],
      tactics: [{ theme: 'hanging_piece' as const, kind: 'allowed' as const, piece: 'knight' as const, square: 'h7', targets: [] }],
    }
    const card = coachCard(hung, plies, true)
    expect(card.why).toContain('It left your knight on h7 undefended, and Bxh7 takes it.')
    expect(card.threat).toContain('your opponent’s best reply is 7. Bxh7, a capture.')
    expect(card.train).toEqual([
      'Hanging piece puzzles (pattern theme: hangingPiece).',
      'Blunder check before every move: after this move, which of my pieces is undefended?',
    ])
  })

  it('labels every theme for both kinds', () => {
    expect(tacticLabel({ theme: 'fork', kind: 'allowed', piece: null, square: null, targets: [] })).toBe('Allowed a fork')
    expect(tacticLabel({ theme: 'hanging_piece', kind: 'missed', piece: null, square: null, targets: [] })).toBe('Missed a free piece')
    expect(tacticLabel({ theme: 'back_rank', kind: 'missed', piece: null, square: null, targets: [] })).toBe('Back-rank mate')
  })
})
