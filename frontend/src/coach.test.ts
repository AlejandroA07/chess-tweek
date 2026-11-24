import { describe, expect, it } from 'vitest'
import { coachCard, tacticLabel } from './coach'
import { TACTIC_THEMES, type JudgedMove, type Ply, type Tactic } from './reviewData'

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

  it('explains a missed pin with the pieces read after the pinning move', () => {
    const pin: JudgedMove = {
      ...caroKann,
      ply: 31,
      san: 'Kh1',
      uci: 'g1h1',
      fenBefore: '4k3/p3n3/8/3P4/8/8/8/R5K1 w - - 0 1',
      phase: 'endgame',
      before: [line('a1e1', 'Re1', ['a1e1', 'a7a6', 'd5d6', 'a6a5', 'd6e7', 'e8d7'], ['Re1', 'a6', 'd6', 'a5', 'dxe7', 'Kd7'])],
      after: [line('a7a6', 'a6', ['a7a6'], ['a6'])],
      tactics: [{ theme: 'pin', kind: 'missed', piece: 'rook', square: 'e1', targets: ['e7', 'e8'], lineIndex: 0 }],
    }
    const card = coachCard(pin, plies, true)
    expect(card.why).toContain(
      'The best line has 16. Re1: your rook on e1 pins the knight on e7 to the king on e8, and the line wins the pinned piece.',
    )
    expect(card.train).toEqual([
      'Pin puzzles (pattern theme: pin).',
    ])
  })

  it('explains a pin that wins a piece the pinned piece guards', () => {
    const pin: JudgedMove = {
      ...caroKann,
      ply: 31,
      san: 'Kh1',
      uci: 'g1h1',
      fenBefore: '4k3/p7/2n5/4b3/8/8/8/4RBK1 w - - 0 1',
      phase: 'endgame',
      before: [line('f1b5', 'Bb5', ['f1b5', 'a7a6', 'e1e5', 'e8f8'], ['Bb5', 'a6', 'Rxe5+', 'Kf8'])],
      after: [line('a7a6', 'a6', ['a7a6'], ['a6'])],
      tactics: [{ theme: 'pin', kind: 'missed', piece: 'bishop', square: 'b5', targets: ['c6', 'e8', 'e5'], lineIndex: 0 }],
    }
    expect(coachCard(pin, plies, true).why).toContain(
      'The best line has 16. Bb5: your bishop on b5 pins the knight on c6 to the king on e8, so it cannot take back on e5, and the line wins material there.',
    )
  })

  it('explains an allowed in-between move from the reply line', () => {
    const before = '3q2k1/8/8/8/6b1/5N2/6P1/3Q2K1 b - - 0 1'
    const inBetween: JudgedMove = {
      ...caroKann,
      ply: 6,
      san: 'Bxf3',
      uci: 'g4f3',
      fenBefore: before,
      after: [line('d1d8', 'Qxd8+', ['d1d8', 'g8h7', 'g2f3'], ['Qxd8+', 'Kh7', 'gxf3'])],
      tactics: [{ theme: 'in_between_move', kind: 'allowed', piece: 'queen', square: 'd8', targets: ['f3'], lineIndex: 0 }],
    }
    const afterCapture = plies.map((ply) => (ply.ply === 6 ? { ...ply, fen: '3q2k1/8/8/8/8/5b2/6P1/3Q2K1 w - - 0 2' } : ply))
    const card = coachCard(inBetween, afterCapture, true)
    expect(card.why).toContain(
      'It allows 4. Qxd8+ before recapturing on f3; the recapture still follows and the line ends further ahead than recapturing at once.',
    )
    expect(card.train).toEqual([
      'In-between move puzzles (pattern theme: intermezzo).',
      'After a capture, look for checks and bigger captures before recapturing automatically.',
    ])
  })

  it('names, explains, and trains every theme for both kinds', () => {
    for (const theme of TACTIC_THEMES) {
      for (const kind of ['allowed', 'missed'] as const) {
        const tactic: Tactic = { theme, kind, piece: 'rook', square: 'e1', targets: ['e7', 'e8'], lineIndex: 0 }
        expect(tacticLabel(tactic)).toMatch(/^[A-Z]/)
        const card = coachCard({ ...caroKann, tactics: [tactic] }, plies, true)
        expect(card.why).not.toMatch(/undefined|null/)
        expect(card.why).not.toContain('No forcing tactic was found')
        expect(card.train.length).toBeGreaterThan(0)
      }
    }
  })
})
