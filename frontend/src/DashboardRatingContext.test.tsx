import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { DashboardRatingContext } from './DashboardRatingContext'

const currentRatings = {
  bullet: 1801,
  blitz: 1765,
  rapid: 1902,
  classical: null,
  correspondence: 2020,
}

function ratingContext() {
  return {
    ratings: [
      {
        speed: 'rapid',
        gamesCounted: 1,
        latestChange: 0,
        points: [{ playedAt: '2026-11-04T00:00:00Z', rating: 1902 }],
      },
      {
        speed: 'blitz',
        gamesCounted: 14,
        latestChange: -6,
        points: [
          { playedAt: '2026-11-01T00:00:00Z', rating: 1720 },
          { playedAt: '2026-11-02T00:00:00Z', rating: 1771 },
          { playedAt: '2026-11-03T00:00:00Z', rating: 1765 },
        ],
      },
      { speed: 'bullet', gamesCounted: 0, latestChange: null, points: [] },
      { speed: 'classical', gamesCounted: 0, latestChange: null, points: [] },
    ],
    botRecord: {
      wins: 3,
      draws: 1,
      losses: 2,
      games: 6,
      lowestRating: 1450,
      highestRating: 1610,
      groups: [
        { type: 'lichess_ai', ratingFrom: null, ratingTo: null, aiLevel: 3, wins: 1, draws: 0, losses: 1, games: 2 },
        { type: 'bot_rating_band', ratingFrom: 1400, ratingTo: 1599, aiLevel: null, wins: 1, draws: 1, losses: 0, games: 2 },
        { type: 'bot_rating_band', ratingFrom: 1600, ratingTo: 1799, aiLevel: null, wins: 0, draws: 0, losses: 1, games: 1 },
        { type: 'bot_unrated', ratingFrom: null, ratingTo: null, aiLevel: null, wins: 1, draws: 0, losses: 0, games: 1 },
      ],
    },
  }
}

function emptyContext() {
  return {
    ratings: ['rapid', 'blitz', 'bullet', 'classical'].map((speed) => ({
      speed,
      gamesCounted: 0,
      latestChange: null,
      points: [],
    })),
    botRecord: {
      wins: 0,
      draws: 0,
      losses: 0,
      games: 0,
      lowestRating: null,
      highestRating: null,
      groups: [],
    },
  }
}

function respondWith(...responses: Array<Partial<Response>>) {
  const fetchMock = vi.fn()
  for (const response of responses) fetchMock.mockResolvedValueOnce(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function ok(body: unknown): Partial<Response> {
  return { ok: true, status: 200, json: async () => body }
}

describe('DashboardRatingContext', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows current Lichess ratings with stored history and the bot record', async () => {
    const fetchMock = respondWith(ok(ratingContext()))

    render(<DashboardRatingContext currentRatings={currentRatings} onSessionFailure={vi.fn()} />)

    const blitz = await screen.findByRole('article', { name: 'Blitz rating' })
    expect(screen.getAllByRole('article').map((card) => card.getAttribute('aria-labelledby')))
      .toEqual(['rating-rapid', 'rating-blitz', 'rating-bullet', 'rating-classical', 'bot-record-title'])
    expect(within(blitz).getByText('1,765')).toBeInTheDocument()
    expect(within(blitz).getByText('Last game −6')).toBeInTheDocument()
    expect(within(blitz).getByText('1,720 to 1,765 over the last 3 stored rated games'))
      .toBeInTheDocument()
    expect(within(blitz).getByText('Low 1,720 · High 1,771')).toBeInTheDocument()
    expect(within(blitz).getByText('14 rated games stored')).toBeInTheDocument()

    const bullet = screen.getByRole('article', { name: 'Bullet rating' })
    expect(within(bullet).getByText('1,801')).toBeInTheDocument()
    expect(within(bullet).getByText('No rated Bullet games stored yet.')).toBeInTheDocument()

    const rapid = screen.getByRole('article', { name: 'Rapid rating' })
    expect(within(rapid).getByText('Last game ±0')).toBeInTheDocument()
    expect(within(rapid).getByText('A history line needs at least two stored rated games.'))
      .toBeInTheDocument()
    expect(within(rapid).getByText('1 rated game stored')).toBeInTheDocument()

    const classical = screen.getByRole('article', { name: 'Classical rating' })
    expect(within(classical).getByText('Not rated')).toBeInTheDocument()
    expect(within(classical).getByText('No rated Classical games stored yet.')).toBeInTheDocument()
    expect(within(classical).queryByText(/Last game/)).not.toBeInTheDocument()

    const bots = screen.getByRole('article', { name: 'Bot record' })
    expect(within(bots).getByText('6 games counted')).toBeInTheDocument()
    expect(within(bots).getByText('BOT ratings 1,450–1,610')).toBeInTheDocument()
    const table = within(bots).getByRole('table', { name: 'Results by opponent group' })
    const rows = within(table).getAllByRole('row')
    expect(rows.map((row) => row.textContent)).toEqual([
      'OpponentWinsDrawsLosses',
      'Lichess AI level 3101',
      'BOT 1,400–1,599110',
      'BOT 1,600–1,799001',
      'BOT without rating100',
    ])
    expect(screen.getByText(/built from games stored on this Mac/i)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/dashboard/rating-context',
      expect.objectContaining({ credentials: 'same-origin' }),
    )
  })

  it('explains empty history and bot record with a route to the games library', async () => {
    respondWith(ok(emptyContext()))

    render(<DashboardRatingContext currentRatings={currentRatings} onSessionFailure={vi.fn()} />)

    const bots = await screen.findByRole('article', { name: 'Bot record' })
    expect(within(bots).getByText('No games against Lichess AI or BOT accounts are stored yet.'))
      .toBeInTheDocument()
    expect(within(bots).queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByText('No rated Blitz games stored yet.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open Games' })).toHaveAttribute('href', '#/games')
  })

  it('shows a safe failure and retries', async () => {
    respondWith(
      { ok: false, status: 503, json: async () => ({ error: 'rating_context_unavailable' }) },
      ok(ratingContext()),
    )

    render(<DashboardRatingContext currentRatings={currentRatings} onSessionFailure={vi.fn()} />)

    expect(await screen.findByRole('heading', { name: 'Rating context could not be loaded' }))
      .toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('article', { name: 'Blitz rating' })).toBeInTheDocument()
  })

  it('rejects an inconsistent response instead of rendering it', async () => {
    const invalid = ratingContext()
    invalid.botRecord.games = 7
    respondWith(ok(invalid))

    render(<DashboardRatingContext currentRatings={currentRatings} onSessionFailure={vi.fn()} />)

    expect(await screen.findByRole('heading', { name: 'Rating context could not be loaded' }))
      .toBeInTheDocument()
    const bots = screen.getByRole('article', { name: 'Bot record' })
    expect(within(bots).getByText('Bot record unavailable.')).toBeInTheDocument()
    expect(within(bots).queryByRole('table')).not.toBeInTheDocument()
    const blitz = screen.getByRole('article', { name: 'Blitz rating' })
    expect(within(blitz).getByText('1,765')).toBeInTheDocument()
    expect(within(blitz).getByText('Stored history unavailable.')).toBeInTheDocument()
  })

  it('keeps current Lichess ratings visible while stored history loads', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))

    render(<DashboardRatingContext currentRatings={currentRatings} onSessionFailure={vi.fn()} />)

    const blitz = screen.getByRole('article', { name: 'Blitz rating' })
    expect(within(blitz).getByText('1,765')).toBeInTheDocument()
    expect(within(blitz).getByText('Loading stored history…')).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Bot record' }).parentElement)
      .toHaveAttribute('aria-busy', 'true')
    const other = screen.getByLabelText('Other Lichess ratings')
    expect(within(other).getByText('Correspondence')).toBeInTheDocument()
    expect(within(other).getByText('2,020')).toBeInTheDocument()
    expect(within(other).queryByText('Bullet')).not.toBeInTheDocument()
  })

  it('reports revoked and unavailable sessions to the shell', async () => {
    const onSessionFailure = vi.fn()
    respondWith({ ok: false, status: 401, json: async () => ({ error: 'not_connected' }) })

    render(<DashboardRatingContext currentRatings={currentRatings} onSessionFailure={onSessionFailure} />)

    await vi.waitFor(() => expect(onSessionFailure).toHaveBeenCalledWith('not_connected'))
  })
})
