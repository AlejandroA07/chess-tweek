import test from 'node:test'
import assert from 'node:assert/strict'
import { Readable } from 'node:stream'

import {
  applyUciToFen,
  createInputGuard,
  parsePuzzleRecord,
  selectCandidates,
} from './select-course-puzzles.mjs'

const startingFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'

test('applies setup moves while preserving complete FEN state', () => {
  assert.equal(
    applyUciToFen(startingFen, 'e2e4'),
    'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
  )
  assert.equal(
    applyUciToFen('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1', 'e1g1'),
    'r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1',
  )
  assert.equal(
    applyUciToFen('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2', 'e5d6'),
    '4k3/8/3P4/8/8/8/8/4K3 b - - 0 2',
  )
})

test('rejects malformed external records and non-Lichess source URLs', () => {
  assert.equal(parsePuzzleRecord('not,a,complete,record'), null)
  assert.equal(parsePuzzleRecord([
    'Ab123', startingFen, 'e2e4 e7e5 g1f3 b8c6', '1600', '60', '90', '1000',
    'defensiveMove short', 'https://example.com/game', '', '',
  ].join(',')), null)
  assert.equal(parsePuzzleRecord('x'.repeat(4097)), null)
})

test('stops oversized records before readline can buffer them', async () => {
  const guarded = Readable.from(['123456789\n']).pipe(createInputGuard(100, 8))
  await assert.rejects(async () => {
    for await (const chunk of guarded) void chunk
  }, /oversized record/)
})

test('selects a bounded candidate with auditable source metadata', () => {
  const record = parsePuzzleRecord([
    'Ab123', startingFen, 'e2e4 e7e5 g1f3 b8c6', '1600', '60', '90', '1000',
    'defensiveMove short', 'https://lichess.org/abc123#4', 'Kings_Pawn_Game', '',
  ].join(','))
  assert.notEqual(record, null)

  const selected = selectCandidates([record], 1, '2026-11-01')
  assert.equal(selected['read-the-last-move'].foundation.length, 1)
  assert.equal(selected['read-the-last-move'].foundation[0].acceptedMove, 'e7e5')
  assert.equal(selected['read-the-last-move'].foundation[0].source.puzzleId, 'Ab123')
  assert.equal(selected['read-the-last-move'].foundation[0].source.license, 'CC0-1.0')
})
