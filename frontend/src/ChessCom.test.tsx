import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { AdminChessCom } from './AdminChessCom'
import { DashboardChessComRatings } from './DashboardRatingContext'
import { GameRetrievalPanel } from './GameRetrievalPanel'
import { openingLabel, parseGame } from './gameCatalog'

type Call = { url: string; method: string; body: unknown }

function server(handler: (call: Call) => Partial<Response>) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = {
      url: String(input),
      method: init?.method ?? 'GET',
      body: typeof init?.body === 'string' ? JSON.parse(init.body) as unknown : null,
    }
    calls.push(call)
    return handler(call) as Response
  }))
  return calls
}

function json(status: number, body: unknown, headers: Record<string, string> = {}): Partial<Response> {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    headers: new Headers(headers),
  }
}

afterEach(() => vi.unstubAllGlobals())

describe('Chess.com account on Admin', () => {
  it('saves, replaces, and removes the username', async () => {
    let saved: string | null = null
    const calls = server((call) => {
      if (call.method === 'GET') return json(200, { username: saved })
      if (call.method === 'PUT') {
        saved = (call.body as { username: string }).username
        return json(200, { username: saved })
      }
      saved = null
      return { ok: true, status: 204 }
    })
    render(<AdminChessCom onSessionFailure={vi.fn()} />)

    const input = await screen.findByLabelText('Chess.com username')
    expect(screen.getByText(/Required to get chess.com games/)).toBeInTheDocument()
    fireEvent.change(input, { target: { value: '  Owner_Name-1  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save Username' }))

    expect(await screen.findByText('Saved. Get Chess.com Games on Games now uses Owner_Name-1.'))
      .toBeInTheDocument()
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ username: 'Owner_Name-1' })

    fireEvent.click(screen.getByRole('button', { name: 'Remove Username' }))
    expect(await screen.findByText('Username removed. Stored chess.com games were kept.'))
      .toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove Username' })).not.toBeInTheDocument()
    expect(calls.map((call) => call.method)).toEqual(['GET', 'PUT', 'DELETE'])
  })

  it('explains an invalid username without sending it', async () => {
    const calls = server(() => json(200, { username: null }))
    render(<AdminChessCom onSessionFailure={vi.fn()} />)

    fireEvent.change(await screen.findByLabelText('Chess.com username'), { target: { value: 'a b' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save Username' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Enter 3 to 25 letters, numbers, underscores, or hyphens.')
    expect(calls).toHaveLength(1)
  })

  it('offers Retry when the saved username cannot be loaded', async () => {
    let attempts = 0
    server(() => {
      attempts += 1
      return attempts === 1 ? json(503, { error: 'catalog_unavailable' }) : json(200, { username: 'owner' })
    })
    render(<AdminChessCom onSessionFailure={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }))

    expect(await screen.findByDisplayValue('owner')).toBeInTheDocument()
  })

  it('returns to the connection prompt when the session has ended', async () => {
    server(() => json(401, { error: 'not_connected' }))
    const onSessionFailure = vi.fn()
    render(<AdminChessCom onSessionFailure={onSessionFailure} />)

    await waitFor(() => expect(onSessionFailure).toHaveBeenCalledWith('not_connected'))
  })
})

describe('Get Chess.com Games', () => {
  const panel = (onComplete = vi.fn()) => render(
    <GameRetrievalPanel
      site="chesscom"
      lichessUsername="ChessOwner"
      onClose={vi.fn()}
      onComplete={onComplete}
      onSessionFailure={vi.fn()}
    />,
  )

  it('sends the owner to Admin when no username is saved', async () => {
    server(() => json(200, { username: null }))
    panel()

    expect(await screen.findByRole('link', { name: 'Add your chess.com username on Admin' }))
      .toHaveAttribute('href', '#/admin')
    expect(screen.queryByRole('button', { name: 'Get Games' })).not.toBeInTheDocument()
  })

  it('retrieves a batch with the saved username and reports skipped games', async () => {
    const calls = server((call) => call.method === 'GET'
      ? json(200, { username: 'OwnerName' })
      : json(200, {
          requested: 5, received: 5, rejected: 7, created: 3, updated: 1, unchanged: 1, before: '2026-11-01',
        }))
    const onComplete = vi.fn()
    panel(onComplete)

    expect(await screen.findByText('OwnerName')).toBeInTheDocument()
    expect(screen.getByText(/may not appear yet/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Number of games'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('Games before'), { target: { value: '2026-11-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Get Games' }))

    expect(await screen.findByText(/Received 5 games from Chess.com\. 3 new, 1 updated, 1 unchanged\. 7 games that are not standard chess or could not be read were skipped\./))
      .toBeInTheDocument()
    expect(calls[1]).toEqual({
      url: '/api/chesscom/games/retrievals',
      method: 'POST',
      body: { count: 5, before: '2026-11-01' },
    })
    expect(onComplete).toHaveBeenCalledOnce()
  })

  it.each([
    [json(404, { error: 'chesscom_player_not_found' }), 'Chess.com has no player with this username.'],
    [json(429, { error: 'chesscom_rate_limited' }, { 'Retry-After': '90' }), 'Chess.com asked us to wait 90 seconds'],
    [json(502, { error: 'invalid_chesscom_response' }), 'Your stored library was not changed.'],
  ])('explains a recoverable failure', async (response, message) => {
    server((call) => call.method === 'GET' ? json(200, { username: 'OwnerName' }) : response)
    panel()

    fireEvent.click(await screen.findByRole('button', { name: 'Get Games' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })
})

describe('Chess.com ratings on Dashboard', () => {
  const context = (blitz: Array<{ playedAt: string; rating: number }>) => ({
    ratings: ['rapid', 'blitz', 'bullet', 'classical'].map((speed) => ({
      speed,
      gamesCounted: speed === 'blitz' ? blitz.length : 0,
      latestChange: null,
      points: speed === 'blitz' ? blitz : [],
    })),
    botRecord: {
      wins: 0, draws: 0, losses: 0, games: 0, lowestRating: null, highestRating: null, groups: [],
    },
  })

  it('shows the latest stored rating and its history from chess.com games', async () => {
    const calls = server(() => json(200, context([
      { playedAt: '2026-11-01T00:00:00Z', rating: 1290 },
      { playedAt: '2026-11-02T00:00:00Z', rating: 1312 },
    ])))
    render(<DashboardChessComRatings onSessionFailure={vi.fn()} />)

    const blitz = await screen.findByRole('article', { name: 'Blitz rating' })
    expect(within(blitz).getByText('1,312')).toBeInTheDocument()
    expect(within(blitz).getByText('Latest rating in your stored games')).toBeInTheDocument()
    expect(within(blitz).getByText('2 rated games stored')).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Classical rating' })).not.toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Bot record' })).not.toBeInTheDocument()
    expect(calls[0].url).toBe('/api/dashboard/rating-context?source=chesscom')
  })

  it('explains how to add chess.com games when none are stored', async () => {
    server(() => json(200, context([])))
    render(<DashboardChessComRatings onSessionFailure={vi.fn()} />)

    expect(await screen.findByText(/No rated chess.com games are stored yet/)).toBeInTheDocument()
    expect(screen.queryByText('Not rated')).not.toBeInTheDocument()
  })
})

describe('Chess.com games in the catalog', () => {
  it('accepts an opening known only by its ECO code', () => {
    const game = parseGame({
      id: '145200000001',
      source: 'chesscom',
      playedAt: '2026-11-10T18:03:21Z',
      result: 'loss',
      ownerColor: 'white',
      ownerRating: 1302,
      ownerRatingChange: null,
      opponent: { type: 'human', name: 'Rival_Player', rating: 1310, title: null, aiLevel: null },
      speed: 'rapid',
      rated: true,
      variant: 'standard',
      timeControl: { initialSeconds: 600, incrementSeconds: 5, daysPerTurn: null, label: '10+5' },
      opening: { eco: 'B12', name: null },
      analysis: { eligible: true, status: 'not_analyzed', myMoves: null, opponentMoves: null },
    })

    expect(game.source).toBe('chesscom')
    expect(openingLabel(game.opening!)).toBe('B12')
    expect(openingLabel({ eco: 'B12', name: 'Caro Kann Defense' })).toBe('B12 · Caro Kann Defense')
  })
})
