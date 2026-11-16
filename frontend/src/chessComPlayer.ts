/** The chess.com username stored for the connected account. */

export type ChessComPlayerFailure = 'not_connected' | 'invalid_username' | 'unavailable'

export class ChessComPlayerError extends Error {
  constructor(readonly failure: ChessComPlayerFailure) {
    super(failure)
  }
}

export const CHESSCOM_USERNAME = /^[A-Za-z0-9_-]{3,25}$/

export async function requestChessComUsername(signal?: AbortSignal): Promise<string | null> {
  const response = await fetch('/api/chesscom/player', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  })
  return readPlayer(response)
}

export async function saveChessComUsername(username: string): Promise<string> {
  const response = await fetch('/api/chesscom/player', {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ username }),
  })
  const saved = await readPlayer(response)
  if (saved === null) throw new ChessComPlayerError('unavailable')
  return saved
}

export async function removeChessComUsername(): Promise<void> {
  const response = await fetch('/api/chesscom/player', {
    method: 'DELETE',
    credentials: 'same-origin',
  })
  if (response.status === 204) return
  throw await failure(response)
}

async function readPlayer(response: Response): Promise<string | null> {
  if (!response.ok) throw await failure(response)
  const body: unknown = await response.json()
  if (
    typeof body !== 'object' || body === null || !('username' in body) ||
    !(body.username === null || (typeof body.username === 'string' && CHESSCOM_USERNAME.test(body.username)))
  ) {
    throw new ChessComPlayerError('unavailable')
  }
  return body.username
}

async function failure(response: Response): Promise<ChessComPlayerError> {
  let code: unknown = null
  try {
    const body: unknown = await response.json()
    code = typeof body === 'object' && body !== null && 'error' in body ? body.error : null
  } catch {
    code = null
  }
  if (response.status === 401 || code === 'not_connected') return new ChessComPlayerError('not_connected')
  if (code === 'invalid_chesscom_username') return new ChessComPlayerError('invalid_username')
  return new ChessComPlayerError('unavailable')
}
