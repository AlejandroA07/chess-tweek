/** The site a stored game came from. */
export type GameSource = 'lichess' | 'chesscom'

/** One game in the catalog: two sites can use the same ID, so a game is always its site and ID. */
export type GameRef = { source: GameSource; id: string }

const ID_PATTERNS: Record<GameSource, RegExp> = {
  lichess: /^[A-Za-z0-9]{8,32}$/,
  chesscom: /^[0-9]{1,20}$/,
}

export function isGameRef(source: unknown, id: unknown): boolean {
  return (source === 'lichess' || source === 'chesscom') &&
    typeof id === 'string' &&
    ID_PATTERNS[source].test(id)
}

/** A stable text key for a game, used for selection, matching, and review links. */
export function gameKey(game: GameRef): string {
  return `${game.source}/${game.id}`
}

/** Reads a key made by {@link gameKey}; null when it is not a valid game. */
export function parseGameKey(key: string): GameRef | null {
  const separator = key.indexOf('/')
  if (separator < 0) return null
  const source = key.slice(0, separator)
  const id = key.slice(separator + 1)
  return isGameRef(source, id) ? { source: source as GameSource, id } : null
}

/** The API path of one stored game. */
export function gameApiPath(game: GameRef): string {
  return `/api/games/${game.source}/${encodeURIComponent(game.id)}`
}
