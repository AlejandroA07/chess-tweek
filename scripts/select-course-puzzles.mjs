#!/usr/bin/env node

import { spawn } from 'node:child_process'
import { createReadStream, lstatSync, realpathSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import readline from 'node:readline'
import { Transform } from 'node:stream'

const HEADER = [
  'PuzzleId',
  'FEN',
  'Moves',
  'Rating',
  'RatingDeviation',
  'Popularity',
  'NbPlays',
  'Themes',
  'GameUrl',
  'OpeningTags',
  'DailyDate',
]

const TIERS = [
  { key: 'foundation', minimum: 1500, maximum: 1700, lengthTheme: 'short', playerMoves: 2 },
  { key: 'development', minimum: 1750, maximum: 1950, lengthTheme: 'long', playerMoves: 3 },
  { key: 'stretch', minimum: 2000, maximum: 2200, lengthTheme: 'veryLong', playerMoves: 4 },
]

const LESSONS = [
  { key: 'read-the-last-move', themes: ['defensiveMove', 'equality', 'intermezzo'] },
  { key: 'generate-candidates', themes: ['quietMove', 'sacrifice', 'interference', 'attraction', 'clearance'] },
  { key: 'test-the-reply', themes: ['deflection', 'intermezzo', 'sacrifice', 'xRayAttack'] },
  { key: 'final-safety-check', themes: ['defensiveMove', 'equality', 'trappedPiece'] },
]

const UCI = /^[a-h][1-8][a-h][1-8][qrbn]?$/
const SNAPSHOT_DATE = /^\d{4}-\d{2}-\d{2}$/
const MAX_INPUT_BYTES = 10_000_000_000
const MAX_DECOMPRESSED_BYTES = 20_000_000_000
const MAX_LINE_BYTES = 4096

export function parsePuzzleRecord(line) {
  if (line.length === 0 || line.length > 4096) return null
  const fields = line.split(',')
  if (fields.length !== HEADER.length) return null
  const [puzzleId, fen, moveText, ratingText, deviationText, popularityText, playsText, themeText, gameUrl, openingText, dailyDate] = fields
  if (!/^[A-Za-z0-9]{5,8}$/.test(puzzleId)
      || fen.length < 20 || fen.length > 100
      || moveText.length === 0 || moveText.length > 500
      || themeText.length > 500 || openingText.length > 500
      || (dailyDate.length > 0 && !SNAPSHOT_DATE.test(dailyDate))
      || gameUrl.length > 200 || !gameUrl.startsWith('https://lichess.org/')) return null

  const moves = moveText.split(' ')
  if (moves.length < 3 || moves.some((move) => !UCI.test(move))) return null
  const rating = strictInteger(ratingText)
  const ratingDeviation = strictInteger(deviationText)
  const popularity = strictInteger(popularityText)
  const plays = strictInteger(playsText)
  if (rating === null || ratingDeviation === null || popularity === null || plays === null) return null

  return {
    puzzleId,
    initialFen: fen,
    moves,
    rating,
    ratingDeviation,
    popularity,
    plays,
    themes: themeText.split(' ').filter(Boolean),
    gameUrl,
    openingTags: openingText.split(' ').filter(Boolean),
    dailyDate: dailyDate || null,
  }
}

export function applyUciToFen(fen, move) {
  if (!UCI.test(move)) throw new Error('Setup move is not valid UCI')
  const fields = fen.split(' ')
  if (fields.length !== 6) throw new Error('Puzzle FEN must have six fields')
  const [placement, side, castling, , halfmoveText, fullmoveText] = fields
  if (side !== 'w' && side !== 'b') throw new Error('Puzzle FEN has an invalid active color')
  const board = parseFenBoard(placement)
  const fromFile = fileIndex(move[0])
  const fromRank = rankIndex(move[1])
  const toFile = fileIndex(move[2])
  const toRank = rankIndex(move[3])
  const source = board[fromRank][fromFile]
  if (source === null || (side === 'w') !== isWhite(source)) throw new Error('Setup move has no matching source piece')

  const destination = board[toRank][toFile]
  const pawnMove = source.toLowerCase() === 'p'
  let captured = destination !== null
  if (pawnMove && fromFile !== toFile && destination === null) {
    if (board[fromRank][toFile] === null) throw new Error('Setup move has no en-passant pawn')
    board[fromRank][toFile] = null
    captured = true
  }
  if (source.toLowerCase() === 'k' && Math.abs(toFile - fromFile) === 2) {
    const rookFile = toFile > fromFile ? 7 : 0
    const rookDestination = toFile > fromFile ? 5 : 3
    const rook = board[fromRank][rookFile]
    if (rook === null || rook.toLowerCase() !== 'r' || isWhite(rook) !== isWhite(source)) {
      throw new Error('Setup castle has no matching rook')
    }
    board[fromRank][rookDestination] = rook
    board[fromRank][rookFile] = null
  }

  board[fromRank][fromFile] = null
  board[toRank][toFile] = promote(source, move[4])
  const nextCastling = updateCastling(castling, source, move.slice(0, 2), move.slice(2, 4), destination)
  const nextEnPassant = pawnMove && Math.abs(toRank - fromRank) === 2
    ? `${move[0]}${(Number(move[1]) + Number(move[3])) / 2}`
    : '-'
  const halfmove = pawnMove || captured ? 0 : strictInteger(halfmoveText)
  const fullmove = strictInteger(fullmoveText)
  if (halfmove === null || fullmove === null || fullmove < 1) throw new Error('Puzzle FEN counters are invalid')
  return [
    serializeFenBoard(board),
    side === 'w' ? 'b' : 'w',
    nextCastling,
    nextEnPassant,
    String(pawnMove || captured ? 0 : halfmove + 1),
    String(side === 'b' ? fullmove + 1 : fullmove),
  ].join(' ')
}

export function selectCandidates(records, perBucket, snapshotDate) {
  const selector = createCandidateSelector(perBucket, snapshotDate)
  for (const record of records) selector.consider(record)
  return selector.result()
}

export function createCandidateSelector(perBucket, snapshotDate) {
  if (!Number.isInteger(perBucket) || perBucket < 1 || perBucket > 50) throw new Error('Candidate count must be from 1 to 50')
  if (!SNAPSHOT_DATE.test(snapshotDate)) throw new Error('Snapshot date must use YYYY-MM-DD')
  const buckets = Object.fromEntries(LESSONS.map((lesson) => [
    lesson.key,
    Object.fromEntries(TIERS.map((tier) => [tier.key, []])),
  ]))

  const consider = (record) => {
    if (record.ratingDeviation > 90 || record.popularity < 80 || record.plays < 200
        || record.themes.includes('oneMove')) return
    const tier = TIERS.find((candidate) => record.rating >= candidate.minimum
      && record.rating <= candidate.maximum
      && record.themes.includes(candidate.lengthTheme)
      && playerMoveCount(record.moves) >= candidate.playerMoves)
    if (tier === undefined) return

    for (const lesson of LESSONS) {
      const matchedThemes = lesson.themes.filter((theme) => record.themes.includes(theme))
      if (matchedThemes.length === 0) continue
      const candidate = toCandidate(record, tier.key, matchedThemes, snapshotDate)
      if (candidate === null) continue
      retainBest(buckets[lesson.key][tier.key], candidate, perBucket)
    }
  }
  return { consider, result: () => buckets }
}

export function createInputGuard(maximumBytes = MAX_DECOMPRESSED_BYTES, maximumLineBytes = MAX_LINE_BYTES) {
  if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 1
      || !Number.isSafeInteger(maximumLineBytes) || maximumLineBytes < 1) {
    throw new Error('Puzzle input safety limits are invalid')
  }
  let totalBytes = 0
  let lineBytes = 0
  return new Transform({
    transform(chunk, _encoding, callback) {
      totalBytes += chunk.length
      if (totalBytes > maximumBytes) {
        callback(new Error('Puzzle export expands beyond the configured safety limit'))
        return
      }
      for (const byte of chunk) {
        if (byte === 10) {
          lineBytes = 0
        } else {
          lineBytes += 1
          if (lineBytes > maximumLineBytes) {
            callback(new Error('Puzzle export contains an oversized record'))
            return
          }
        }
      }
      callback(null, chunk)
    },
  })
}

function toCandidate(record, difficultyTier, matchedThemes, snapshotDate) {
  try {
    const fen = applyUciToFen(record.initialFen, record.moves[0])
    return {
      difficultyTier,
      fen,
      orientation: fen.split(' ')[1] === 'w' ? 'white' : 'black',
      lastMove: record.moves[0],
      acceptedMove: record.moves[1],
      solutionMoves: record.moves.slice(1),
      matchedThemes,
      source: {
        provider: 'lichess',
        puzzleId: record.puzzleId,
        gameUrl: record.gameUrl,
        license: 'CC0-1.0',
        ratingAtSelection: record.rating,
        ratingDeviationAtSelection: record.ratingDeviation,
        popularityAtSelection: record.popularity,
        playsAtSelection: record.plays,
        themes: record.themes,
        openingTags: record.openingTags,
        dailyDate: record.dailyDate,
        snapshotDate,
      },
    }
  } catch {
    return null
  }
}

function retainBest(bucket, candidate, limit) {
  bucket.push(candidate)
  bucket.sort((left, right) => candidateScore(right) - candidateScore(left)
    || left.source.puzzleId.localeCompare(right.source.puzzleId))
  if (bucket.length > limit) bucket.pop()
}

function candidateScore(candidate) {
  return candidate.source.popularityAtSelection * 100_000
    + Math.min(candidate.source.playsAtSelection, 99_999)
    - candidate.source.ratingDeviationAtSelection * 100
}

function strictInteger(value) {
  if (!/^\d+$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

function playerMoveCount(moves) {
  return Math.ceil((moves.length - 1) / 2)
}

function fileIndex(file) {
  return file.charCodeAt(0) - 97
}

function rankIndex(rank) {
  return 8 - Number(rank)
}

function isWhite(piece) {
  return piece === piece.toUpperCase()
}

function promote(piece, promotion) {
  if (promotion === undefined) return piece
  if (piece.toLowerCase() !== 'p') throw new Error('Only a pawn can promote')
  return isWhite(piece) ? promotion.toUpperCase() : promotion
}

function parseFenBoard(placement) {
  const rows = placement.split('/')
  if (rows.length !== 8) throw new Error('Puzzle FEN needs eight ranks')
  return rows.map((row) => {
    const rank = []
    for (const character of row) {
      if (/^[1-8]$/.test(character)) {
        for (let count = 0; count < Number(character); count += 1) rank.push(null)
      } else if (/^[prnbqkPRNBQK]$/.test(character)) {
        rank.push(character)
      } else {
        throw new Error('Puzzle FEN has an invalid piece placement')
      }
    }
    if (rank.length !== 8) throw new Error('Puzzle FEN rank does not contain eight squares')
    return rank
  })
}

function serializeFenBoard(board) {
  return board.map((rank) => {
    let output = ''
    let empty = 0
    for (const square of rank) {
      if (square === null) {
        empty += 1
      } else {
        if (empty > 0) output += String(empty)
        output += square
        empty = 0
      }
    }
    return output + (empty > 0 ? String(empty) : '')
  }).join('/')
}

function updateCastling(castling, source, from, to, capturedPiece) {
  let rights = castling === '-' ? '' : castling
  const remove = (characters) => {
    for (const character of characters) rights = rights.replace(character, '')
  }
  if (source === 'K') remove('KQ')
  if (source === 'k') remove('kq')
  if (from === 'h1' || (to === 'h1' && capturedPiece === 'R')) remove('K')
  if (from === 'a1' || (to === 'a1' && capturedPiece === 'R')) remove('Q')
  if (from === 'h8' || (to === 'h8' && capturedPiece === 'r')) remove('k')
  if (from === 'a8' || (to === 'a8' && capturedPiece === 'r')) remove('q')
  return rights || '-'
}

function inputStream(inputPath) {
  const resolved = realpathSync(inputPath)
  const metadata = lstatSync(resolved)
  if (!metadata.isFile()) throw new Error('Input must be a regular file')
  if (metadata.size > MAX_INPUT_BYTES) throw new Error('Input is larger than 10 GB')
  const extension = path.extname(resolved)
  if (extension === '.csv') {
    return { stream: createReadStream(resolved).pipe(createInputGuard()), completion: null }
  }
  if (extension !== '.zst') throw new Error('Input must end in .csv or .zst')
  const child = spawn('zstd', ['-dc', resolved], { shell: false, stdio: ['ignore', 'pipe', 'pipe'] })
  child.stderr.resume()
  const completion = new Promise((resolve) => child.once('close', resolve))
  return { stream: child.stdout.pipe(createInputGuard()), completion }
}

async function main() {
  const [inputPath, ...options] = process.argv.slice(2)
  const snapshotOption = options.find((option) => option.startsWith('--snapshot-date='))
  const countOption = options.find((option) => option.startsWith('--per-bucket='))
  const outputOption = options.find((option) => option.startsWith('--output='))
  if (inputPath === undefined || snapshotOption === undefined || options.some((option) => !option.startsWith('--snapshot-date=') && !option.startsWith('--per-bucket=') && !option.startsWith('--output='))) {
    console.error('Usage: node scripts/select-course-puzzles.mjs <puzzles.csv|puzzles.csv.zst> --snapshot-date=YYYY-MM-DD [--per-bucket=8] [--output=candidates.json]')
    process.exitCode = 2
    return
  }
  const snapshotDate = snapshotOption.slice('--snapshot-date='.length)
  const perBucket = countOption === undefined ? 8 : strictInteger(countOption.slice('--per-bucket='.length))
  if (perBucket === null) throw new Error('Candidate count must be an integer')

  const source = inputStream(inputPath)
  const lines = readline.createInterface({ input: source.stream, crlfDelay: Number.POSITIVE_INFINITY })
  const selector = createCandidateSelector(perBucket, snapshotDate)
  let first = true
  for await (const line of lines) {
    if (first) {
      first = false
      if (line.split(',').join('|') !== HEADER.join('|')) throw new Error('Puzzle CSV header is not supported')
      continue
    }
    const record = parsePuzzleRecord(line)
    if (record !== null) selector.consider(record)
  }
  if (source.completion !== null) {
    const exitCode = await source.completion
    if (exitCode !== 0) throw new Error('zstd could not read the puzzle export')
  }
  const output = `${JSON.stringify(selector.result(), null, 2)}\n`
  if (outputOption === undefined) {
    console.log(output.trimEnd())
  } else {
    const outputPath = path.resolve(outputOption.slice('--output='.length))
    if (path.extname(outputPath) !== '.json') throw new Error('Output must end in .json')
    writeFileSync(outputPath, output, { encoding: 'utf8', flag: 'wx', mode: 0o600 })
  }
}

const isMain = process.argv[1] !== undefined
  && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))
if (isMain) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : 'Puzzle selection failed')
    process.exitCode = 1
  })
}
