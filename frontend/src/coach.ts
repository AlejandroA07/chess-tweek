import { pieceAt, playUci } from './boardPosition'
import type { JudgedMove, Ply, Tactic } from './reviewData'

/**
 * Deterministic coach cards for one finding. Every sentence is filled from the stored analysis:
 * the expected scores, the best line, the opponent's reply line, and the server's proven tactic
 * labels. Nothing is generated or guessed.
 */
export type CoachCard = { why: string; threat: string; train: string[] }

export const TACTIC_TEXT: Record<Tactic['theme'], Record<Tactic['kind'], string>> = {
  allowed_mate: { allowed: 'Allowed mate', missed: 'Allowed mate' },
  missed_mate: { allowed: 'Missed mate', missed: 'Missed mate' },
  back_rank: { allowed: 'Back-rank mate', missed: 'Back-rank mate' },
  hanging_piece: { allowed: 'Hung a piece', missed: 'Missed a free piece' },
  fork: { allowed: 'Allowed a fork', missed: 'Missed a fork' },
  general_tactics: { allowed: 'Allowed a tactic', missed: 'Missed a tactic' },
}

export function tacticLabel(tactic: Tactic) {
  return TACTIC_TEXT[tactic.theme][tactic.kind]
}

/**
 * @param owner whether the finding is one of the owner's moves (second person) or the
 *     opponent's (named by color)
 */
export function coachCard(finding: JudgedMove, plies: Ply[], owner: boolean): CoachCard {
  const mover = finding.ply % 2 === 1 ? 'White' : 'Black'
  const other = mover === 'White' ? 'Black' : 'White'
  const words = owner
    ? { subject: 'you', possessive: 'your', opponent: 'your opponent', opponentPossessive: 'your opponent’s' }
    : { subject: mover, possessive: `${mover}’s`, opponent: other, opponentPossessive: `${other}’s` }
  const played = label(finding.ply, finding.san)
  const best = label(finding.ply, finding.bestMoveSan ?? finding.bestMove)
  const before = Math.round(finding.expectedBefore / 20)
  const after = Math.round(finding.expectedAfter / 20)
  const bestLine = finding.before[0]?.pvSan ?? []
  const replyLine = finding.after[0]?.pvSan ?? []
  const afterFen = plies[finding.ply - 1]?.fen ?? null

  const why = [
    `${played} lowered ${words.possessive} expected score from ${before}% to ${after}% (${(finding.loss / 20).toFixed(1)} points). The engine preferred ${best}, which keeps it near ${before}%.`,
    ...finding.tactics.map((tactic) => explain(tactic, finding, afterFen, words)),
  ]
  if (finding.tactics.length === 0) {
    why.push('No forcing tactic was found: the difference is positional. Step through the best line to see which pieces it improves.')
  }

  let threat: string
  if (finding.afterTerminal === 'checkmate') {
    threat = `${played} delivered checkmate; nothing followed.`
  } else if (finding.afterTerminal === 'stalemate') {
    threat = `${played} stalemated the opponent, so the game was drawn on the spot.`
  } else if (replyLine.length === 0) {
    threat = 'No reply line was stored for this move.'
  } else {
    const reply = label(finding.ply + 1, replyLine[0])
    const forcing = /[x+#]/.test(replyLine[0])
    threat = forcing
      ? `After ${played}, ${words.opponentPossessive} best reply is ${reply}, a ${replyKind(replyLine[0])}. The line continues ${variation(finding.ply + 1, replyLine.slice(0, 6))}.`
      : `After ${played}, ${words.opponent} has no capture or check that wins at once; the best reply is the quiet ${reply}, continuing ${variation(finding.ply + 1, replyLine.slice(0, 6))}.`
    if (finding.tactics.length > 0 && finding.tactics.every((tactic) => tactic.kind === 'missed')) {
      threat += ` The real problem was the chance ${words.subject} missed, not a threat against ${owner ? 'you' : mover}.`
    }
  }
  if (bestLine.length > 0) {
    threat += ` Best line instead: ${variation(finding.ply, bestLine.slice(0, 6))}.`
  }

  return { why: why.join(' '), threat, train: training(finding) }
}

function explain(
  tactic: Tactic,
  finding: JudgedMove,
  afterFen: string | null,
  words: { possessive: string; opponent: string; opponentPossessive: string },
) {
  const replyFirst = finding.after[0]?.pvSan[0]
  const bestFirst = finding.bestMoveSan ?? finding.bestMove
  switch (tactic.theme) {
    case 'allowed_mate':
      return `It allows a forced mate: ${variation(finding.ply + 1, finding.after[0]?.pvSan ?? [])}.`
    case 'missed_mate':
      return `There was a forced mate: ${variation(finding.ply, finding.before[0]?.pvSan ?? [])}.`
    case 'back_rank':
      return `The mate comes on the back rank with a ${tactic.piece ?? 'heavy piece'} on ${tactic.square ?? 'the last rank'}: the king is trapped behind its own pieces.`
    case 'hanging_piece':
      return tactic.kind === 'allowed'
        ? `It left ${words.possessive} ${tactic.piece} on ${tactic.square} undefended, and ${replyFirst ?? 'the reply'} takes it.`
        : `${words.opponentPossessive.charAt(0).toUpperCase()}${words.opponentPossessive.slice(1)} ${tactic.piece} on ${tactic.square} was undefended: ${bestFirst} wins it for free.`
    case 'fork': {
      const start = tactic.kind === 'allowed' ? afterFen : finding.fenBefore
      const line = tactic.kind === 'allowed' ? finding.after[0] : finding.before[0]
      const targets = forkTargets(start, line?.pv ?? [], tactic)
      const owner = tactic.kind === 'allowed' ? words.opponentPossessive : words.possessive
      return `${tactic.kind === 'allowed' ? 'It lets' : 'The best line has'} ${owner} ${tactic.piece} reach ${tactic.square}, where it attacks ${targets} at once and wins material.`
    }
    case 'general_tactics':
      return tactic.kind === 'allowed'
        ? `${capitalize(words.opponentPossessive)} best reply wins material: ${variation(finding.ply + 1, (finding.after[0]?.pvSan ?? []).slice(0, 6))}.`
        : `The best line wins material: ${variation(finding.ply, (finding.before[0]?.pvSan ?? []).slice(0, 6))}.`
  }
}

/** Names the forked pieces, read from the position right after the forking move. */
function forkTargets(start: string | null, pv: string[], tactic: Tactic) {
  if (start === null || tactic.square === null) return 'two pieces'
  let fen: string | null = start
  for (const uci of pv) {
    fen = fen === null ? null : playUci(fen, uci)
    if (fen === null) return 'two pieces'
    if (uci.slice(2, 4) === tactic.square) break
  }
  const names = tactic.targets.map((square) => {
    const piece = fen === null ? null : pieceAt(fen, square)
    return piece ? `the ${piece.piece} on ${square}` : square
  })
  return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** What to practice, in pattern-library theme names (Stage 8) and habits. */
function training(finding: JudgedMove): string[] {
  const items: string[] = []
  const add = (item: string) => {
    if (!items.includes(item)) items.push(item)
  }
  for (const tactic of finding.tactics) {
    switch (tactic.theme) {
      case 'fork':
        add('Fork puzzles (pattern theme: fork).')
        if (tactic.kind === 'allowed') add('Before each move, check which squares the opponent’s knights, queen, and pawns can reach next.')
        break
      case 'hanging_piece':
        add('Hanging piece puzzles (pattern theme: hangingPiece).')
        add(tactic.kind === 'allowed'
          ? 'Blunder check before every move: after this move, which of my pieces is undefended?'
          : 'Look at every capture first: is any enemy piece undefended?')
        break
      case 'allowed_mate':
        add('Defensive checks: before moving, list every check the opponent has.')
        add('Mate-in-two puzzles (pattern theme: mateIn2).')
        break
      case 'missed_mate':
        add('Mate-in-two puzzles (pattern theme: mateIn2).')
        add('Look at every check you have before any other move.')
        break
      case 'back_rank':
        add('Back-rank mate puzzles (pattern theme: backRankMate).')
        add('When the back rank is weak, make an escape square for the king (h3/h6 or g3/g6).')
        break
      case 'general_tactics':
        add('Mixed tactics: work out the whole line before moving (checks, captures, threats).')
        break
    }
  }
  if (items.length === 0) {
    add({
      opening: 'Opening study: compare this line with the plans of your opening.',
      middlegame: 'Middlegame plans: replay the best line and ask which piece it improves and why.',
      endgame: 'Endgame technique (pattern themes: pawnEndgame, rookEndgame).',
    }[finding.phase])
  }
  return items
}

function replyKind(san: string) {
  if (san.includes('#')) return 'checkmate'
  if (san.includes('x') && san.includes('+')) return 'capture with check'
  if (san.includes('x')) return 'capture'
  return 'check'
}

function label(ply: number, san: string) {
  const number = Math.ceil(ply / 2)
  return ply % 2 === 1 ? `${number}. ${san}` : `${number}… ${san}`
}

function variation(firstPly: number, sans: string[]) {
  return sans
    .map((san, offset) => {
      const ply = firstPly + offset
      if (ply % 2 === 1) return `${Math.ceil(ply / 2)}. ${san}`
      return offset === 0 ? `${Math.ceil(ply / 2)}… ${san}` : san
    })
    .join(' ')
}

function capitalize(text: string) {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
