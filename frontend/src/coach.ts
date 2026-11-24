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
  discovered_check: { allowed: 'Allowed a discovered check', missed: 'Missed a discovered check' },
  discovered_attack: { allowed: 'Allowed a discovered attack', missed: 'Missed a discovered attack' },
  pin: { allowed: 'Allowed a pin', missed: 'Missed a pin' },
  skewer: { allowed: 'Allowed a skewer', missed: 'Missed a skewer' },
  removing_defender: { allowed: 'Lost a defender', missed: 'Missed removing a defender' },
  trapped_piece: { allowed: 'Allowed a trapped piece', missed: 'Missed a trapped piece' },
  deflection: { allowed: 'Allowed a deflection', missed: 'Missed a deflection' },
  in_between_move: { allowed: 'Allowed an in-between move', missed: 'Missed an in-between move' },
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
    default:
      return motif(tactic, finding, afterFen, words)
  }
}

/**
 * The Stage 8B motifs. The evidence comes first: the move from the line after which the pattern
 * stands, then the pieces it involves, read from the position right after that move.
 */
function motif(
  tactic: Tactic,
  finding: JudgedMove,
  afterFen: string | null,
  words: { possessive: string; opponentPossessive: string },
) {
  const allowed = tactic.kind === 'allowed'
  const line = allowed ? finding.after[0] : finding.before[0]
  const start = allowed ? afterFen : finding.fenBefore
  const index = tactic.lineIndex ?? null
  const san = index === null ? undefined : line?.pvSan[index]
  const move = san === undefined || index === null ? 'the line' : label((allowed ? finding.ply + 1 : finding.ply) + index, san)
  const fen = index === null ? null : positionAfter(start, line?.pv ?? [], index)
  const gainer = allowed ? words.opponentPossessive : words.possessive
  const victim = allowed ? words.possessive : words.opponentPossessive
  const lead = allowed ? `It allows ${move}` : `The best line has ${move}`
  const key = `${tactic.piece ?? 'piece'} on ${tactic.square ?? 'its square'}`
  const [first, second] = tactic.targets.map((square) => named(fen, square))
  switch (tactic.theme) {
    case 'discovered_check':
      return `${lead}, uncovering a check from ${gainer} ${key}, and the line wins material.`
    case 'discovered_attack':
      return `${lead}, uncovering an attack by ${gainer} ${key} on ${list(tactic.targets.map((square) => named(fen, square)))}, and the line wins material.`
    case 'pin': {
      const pinned = `${lead}: ${gainer} ${key} pins ${first ?? 'a piece'} to ${second ?? 'the piece behind it'}`
      const guarded = tactic.targets[2]
      return guarded === undefined
        ? `${pinned}, and the line wins the pinned piece.`
        : `${pinned}, so it cannot take back on ${guarded}, and the line wins material there.`
    }
    case 'skewer':
      return `${lead}: ${gainer} ${key} attacks ${first ?? 'a piece'}; once it moves, ${second ?? 'the piece behind it'} is taken.`
    case 'removing_defender':
      return `${lead}, taking ${victim} ${key}, the only defender of ${first ?? 'a piece'}, which is taken next.`
    case 'trapped_piece':
      return `${lead}, after which ${victim} ${key} has no safe square, and the line wins it.`
    case 'deflection':
      return `${lead}, which forces ${victim} ${key} away from guarding ${first ?? 'a piece'}, and that piece is taken next.`
    case 'in_between_move':
      return `${lead} before recapturing on ${tactic.targets[0] ?? 'the square'}; the recapture still follows and the line ends further ahead than recapturing at once.`
    default:
      return ''
  }
}

/** The position after line move `index`, or null when it cannot be replayed. */
function positionAfter(start: string | null, pv: string[], index: number) {
  let fen = start
  for (const uci of pv.slice(0, index + 1)) {
    fen = fen === null ? null : playUci(fen, uci)
  }
  return fen
}

function named(fen: string | null, square: string) {
  const piece = fen === null ? null : pieceAt(fen, square)
  return piece ? `the ${piece.piece} on ${square}` : square
}

function list(names: string[]) {
  return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
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
      case 'discovered_check':
      case 'discovered_attack':
        add(tactic.theme === 'discovered_check'
          ? 'Discovered check puzzles (pattern theme: discoveredCheck).'
          : 'Discovered attack puzzles (pattern theme: discoveredAttack).')
        add(tactic.kind === 'allowed'
          ? 'Look for enemy pieces lined up behind one of their own pieces: moving the front one can unleash the one behind.'
          : 'Look for your own pieces lined up behind another of your pieces: moving the front one with a threat can win material.')
        break
      case 'pin':
        add('Pin puzzles (pattern theme: pin).')
        if (tactic.kind === 'allowed') add('Before each move, check which of your pieces stand on one line with your king or queen.')
        break
      case 'skewer':
        add('Skewer puzzles (pattern theme: skewer).')
        if (tactic.kind === 'allowed') add('Keep your king and valuable pieces off the same open line.')
        break
      case 'removing_defender':
      case 'deflection':
        add(tactic.theme === 'removing_defender'
          ? 'Capture-the-defender puzzles (pattern theme: capturingDefender).'
          : 'Deflection puzzles (pattern theme: deflection).')
        add('Count the defenders of each piece: a piece with one defender falls once that defender is taken or driven away.')
        break
      case 'trapped_piece':
        add('Trapped piece puzzles (pattern theme: trappedPiece).')
        if (tactic.kind === 'allowed') add('Before sending a piece deep into enemy territory, make sure it has a safe way back.')
        break
      case 'in_between_move':
        add('In-between move puzzles (pattern theme: intermezzo).')
        add('After a capture, look for checks and bigger captures before recapturing automatically.')
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
