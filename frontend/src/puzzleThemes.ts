/**
 * How the Themes page presents the Lichess theme catalog: the sections and order follow the
 * Lichess Puzzle Themes page (without "Healthy mix"), and the descriptions are Chess Tweek's own
 * wording. Names and counts come from the backend; a Theme the catalog does not list yet is
 * shown at the end of its backend group.
 */
export type ThemeIcon =
  | 'book' | 'signpost' | 'hourglass' | 'pawnUp' | 'target' | 'shieldBreak' | 'reveal'
  | 'doubleArrow' | 'crownOpen' | 'fork' | 'hanging' | 'kingside' | 'pin' | 'queenside'
  | 'sacrifice' | 'skewer' | 'cage' | 'magnet' | 'broom' | 'line' | 'shield' | 'deflect'
  | 'block' | 'pause' | 'quiet' | 'xray' | 'stopwatch' | 'crown' | 'castle' | 'enPassant'
  | 'promotion' | 'balance' | 'advantage' | 'crushing' | 'length' | 'trophy'
  | `piece:${string}`

export type ThemeEntry = { key: string; icon: ThemeIcon; description: string }
export type ThemeSection = { title: string; themes: ThemeEntry[] }

/** Where the "By openings" section sits among the theme sections, as on Lichess. */
export const OPENINGS_SECTION_AFTER = 'Phases'
export const OPENINGS_SECTION_SIZE = 12

export const THEME_SECTIONS: ThemeSection[] = [
  {
    title: 'Phases',
    themes: [
      { key: 'opening', icon: 'book', description: 'A tactic that arises in the first moves of the game.' },
      { key: 'middlegame', icon: 'signpost', description: 'A tactic from the middle of the game, with most pieces still on.' },
      { key: 'endgame', icon: 'hourglass', description: 'A tactic from the final phase, with few pieces left.' },
      { key: 'rookEndgame', icon: 'piece:♜', description: 'An ending with only rooks and pawns.' },
      { key: 'bishopEndgame', icon: 'piece:♝', description: 'An ending with only bishops and pawns.' },
      { key: 'pawnEndgame', icon: 'piece:♟', description: 'An ending with only kings and pawns.' },
      { key: 'knightEndgame', icon: 'piece:♞', description: 'An ending with only knights and pawns.' },
      { key: 'queenEndgame', icon: 'piece:♛', description: 'An ending with only queens and pawns.' },
      { key: 'queenRookEndgame', icon: 'piece:♛♜', description: 'An ending with queens, rooks, and pawns only.' },
    ],
  },
  {
    title: 'Motifs',
    themes: [
      { key: 'advancedPawn', icon: 'pawnUp', description: 'A pawn close to promotion drives the tactic.' },
      { key: 'attackingF2F7', icon: 'target', description: 'The attack lands on the weak f2 or f7 square next to the king.' },
      { key: 'capturingDefender', icon: 'shieldBreak', description: 'Take the piece that guards something, then win what it was protecting.' },
      { key: 'discoveredAttack', icon: 'reveal', description: 'Moving one piece uncovers an attack by the piece behind it.' },
      { key: 'doubleCheck', icon: 'doubleArrow', description: 'Two pieces give check at once, so the king must move.' },
      { key: 'exposedKing', icon: 'crownOpen', description: 'A king with little shelter becomes the target.' },
      { key: 'fork', icon: 'fork', description: 'One piece attacks two targets at the same time.' },
      { key: 'hangingPiece', icon: 'hanging', description: 'Win a piece that is undefended or not defended enough.' },
      { key: 'kingsideAttack', icon: 'kingside', description: 'An attack on the king after it castled short.' },
      { key: 'pin', icon: 'pin', description: 'A piece cannot move without exposing a more valuable piece behind it.' },
      { key: 'queensideAttack', icon: 'queenside', description: 'An attack on the king after it castled long.' },
      { key: 'sacrifice', icon: 'sacrifice', description: 'Give up material now to gain more later.' },
      { key: 'skewer', icon: 'skewer', description: 'Attack a valuable piece so that it moves and exposes a piece behind it.' },
      { key: 'trappedPiece', icon: 'cage', description: 'A piece has no safe square and is lost.' },
    ],
  },
  {
    title: 'Advanced',
    themes: [
      { key: 'attraction', icon: 'magnet', description: 'Lure an enemy piece onto a square where it can be exploited.' },
      { key: 'clearance', icon: 'broom', description: 'Move a piece out of the way to open a square, file, or diagonal.' },
      { key: 'collinearMove', icon: 'line', description: 'A key move along a line already occupied by your own piece.' },
      { key: 'discoveredCheck', icon: 'reveal', description: 'Moving one piece uncovers a check from the piece behind it.' },
      { key: 'defensiveMove', icon: 'shield', description: 'The only move that holds the position or avoids loss.' },
      { key: 'deflection', icon: 'deflect', description: 'Pull a defender away from the square or piece it guards.' },
      { key: 'interference', icon: 'block', description: 'Put a piece between an enemy piece and what it defends.' },
      { key: 'intermezzo', icon: 'pause', description: 'Play an unexpected in-between move before the obvious one.' },
      { key: 'quietMove', icon: 'quiet', description: 'A move without check or capture that prepares an unstoppable threat.' },
      { key: 'xRayAttack', icon: 'xray', description: 'A piece attacks or defends a square through an enemy piece.' },
      { key: 'zugzwang', icon: 'stopwatch', description: 'The opponent must move, and every move makes things worse.' },
    ],
  },
  {
    title: 'Mates',
    themes: [
      { key: 'mate', icon: 'crown', description: 'Finish the game with checkmate.' },
      { key: 'mateIn1', icon: 'crown', description: 'Deliver checkmate in one move.' },
      { key: 'mateIn2', icon: 'crown', description: 'Deliver checkmate in two moves.' },
      { key: 'mateIn3', icon: 'crown', description: 'Deliver checkmate in three moves.' },
      { key: 'mateIn4', icon: 'crown', description: 'Deliver checkmate in four moves.' },
      { key: 'mateIn5', icon: 'crown', description: 'Find a checkmate five or more moves deep.' },
    ],
  },
  {
    title: 'Mate themes',
    themes: [
      { key: 'anastasiaMate', icon: 'piece:♞', description: 'A knight and a rook or queen trap the king against the edge.' },
      { key: 'arabianMate', icon: 'piece:♞', description: 'A knight and a rook mate the king in a corner.' },
      { key: 'backRankMate', icon: 'piece:♜', description: 'Mate on the back rank, where the king is boxed in by its own pawns.' },
      { key: 'balestraMate', icon: 'piece:♝', description: 'A bishop gives mate while a queen covers the escape squares.' },
      { key: 'blindSwineMate', icon: 'piece:♜', description: 'Two rooks on the seventh rank mate the king.' },
      { key: 'bodenMate', icon: 'piece:♝', description: 'Two bishops on crossing diagonals mate a king blocked by its own pieces.' },
      { key: 'cornerMate', icon: 'crown', description: 'The king is mated in a corner by a rook or queen with a knight covering.' },
      { key: 'doubleBishopMate', icon: 'piece:♝', description: 'Two bishops on adjacent diagonals mate the king.' },
      { key: 'dovetailMate', icon: 'piece:♛', description: 'A queen mates a king whose diagonal escapes are blocked by its own pieces.' },
      { key: 'epauletteMate', icon: 'piece:♛', description: 'The king is mated with its own pieces on both sides of it.' },
      { key: 'hookMate', icon: 'piece:♜', description: 'A rook, knight, and pawn combine to mate the king.' },
      { key: 'killBoxMate', icon: 'piece:♜', description: 'A rook next to the king, supported by a queen, seals a box around it.' },
      { key: 'pillsburysMate', icon: 'piece:♜', description: 'A rook mates on the edge while a bishop covers the last flight square.' },
      { key: 'morphysMate', icon: 'piece:♝', description: 'A bishop mates on the edge while a rook covers the last flight square.' },
      { key: 'operaMate', icon: 'piece:♜', description: 'A rook mates on the back rank, protected by a bishop.' },
      { key: 'swallowstailMate', icon: 'piece:♛', description: 'A queen mates a king whose side escapes are blocked by its own pieces.' },
      { key: 'triangleMate', icon: 'piece:♛', description: 'A queen and a rook form a triangle around the king.' },
      { key: 'vukovicMate', icon: 'piece:♞', description: 'A rook and a knight mate the king on the edge.' },
      { key: 'smotheredMate', icon: 'piece:♞', description: 'A knight mates a king surrounded by its own pieces.' },
    ],
  },
  {
    title: 'Special moves',
    themes: [
      { key: 'castling', icon: 'castle', description: 'Castling is part of the solution.' },
      { key: 'enPassant', icon: 'enPassant', description: 'The solution uses an en passant capture.' },
      { key: 'promotion', icon: 'promotion', description: 'A pawn promotes during the solution.' },
      { key: 'underPromotion', icon: 'promotion', description: 'A pawn promotes to a knight, bishop, or rook instead of a queen.' },
    ],
  },
  {
    title: 'Goals',
    themes: [
      { key: 'equality', icon: 'balance', description: 'Recover from a worse position and reach a draw or balance.' },
      { key: 'advantage', icon: 'advantage', description: 'Gain a clear advantage.' },
      { key: 'crushing', icon: 'crushing', description: 'Find the blow that wins decisively.' },
      { key: 'mate', icon: 'crown', description: 'Finish the game with checkmate.' },
    ],
  },
  {
    title: 'Lengths',
    themes: [
      { key: 'oneMove', icon: 'length', description: 'A puzzle solved with a single move.' },
      { key: 'short', icon: 'length', description: 'A puzzle solved in two moves.' },
      { key: 'long', icon: 'length', description: 'A puzzle solved in three moves.' },
      { key: 'veryLong', icon: 'length', description: 'A puzzle that takes four moves or more.' },
    ],
  },
  {
    title: 'Origin',
    themes: [
      { key: 'master', icon: 'trophy', description: 'From a game played by a titled player.' },
      { key: 'masterVsMaster', icon: 'trophy', description: 'From a game between two titled players.' },
      { key: 'superGM', icon: 'trophy', description: 'From a game between world-class players.' },
    ],
  },
]

/** Themes never offered as a category, such as Lichess's "Healthy mix". */
export const HIDDEN_THEMES = new Set(['mix'])
