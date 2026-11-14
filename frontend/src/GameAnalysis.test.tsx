import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from './App'

type Job = {
  gameId: string
  perspective: 'my_moves' | 'opponent_moves' | 'both'
  state: 'running' | 'completed' | 'failed' | 'canceled'
  pass: 'starting' | 'quick' | 'deep' | 'saving'
  judged: number
  total: number
  failure: string | null
  startedAt: string
  finishedAt: string | null
  versionIds: number[]
}

const GAME_ID = 'game0001'
const STARTED_AT = '2026-11-14T12:00:00Z'

function running(overrides: Partial<Job> = {}): Job {
  return {
    gameId: GAME_ID,
    perspective: 'my_moves',
    state: 'running',
    pass: 'quick',
    judged: 3,
    total: 40,
    failure: null,
    startedAt: STARTED_AT,
    finishedAt: null,
    versionIds: [],
    ...overrides,
  }
}

function finished(state: Job['state'], overrides: Partial<Job> = {}): Job {
  return running({ state, finishedAt: '2026-11-14T12:02:00Z', ...overrides })
}

describe('Game analysis on Games', () => {
  let currentJob: Job | null
  let polls: Job[]
  let analyzed: boolean
  let startResponse: { status: number; body: unknown }
  let requests: { url: string; init?: RequestInit }[]
  let catalog: (url: string) => unknown

  beforeEach(() => {
    window.history.replaceState(null, '', '/')
    currentJob = null
    polls = []
    analyzed = false
    startResponse = { status: 202, body: { job: running({ pass: 'starting', judged: 0, total: 0 }) } }
    requests = []
    catalog = (url) => url === '/api/games?offset=0&limit=50'
      ? {
          total: 2,
          offset: 0,
          limit: 50,
          games: [card(GAME_ID, 'Rival', analyzed), catalogOnlyCard()],
        }
      : undefined
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push({ url, init })
      if (url === '/api/health') return json(200, { status: 'ok' })
      if (url === '/api/lichess/session') return json(200, connectedSession())
      if (url.startsWith('/api/games?')) {
        const page = catalog(url)
        if (page !== undefined) return json(200, page)
      }
      if (url === '/api/analysis') {
        if (polls.length > 0) currentJob = polls.shift() ?? null
        return json(200, { job: currentJob })
      }
      if (url === `/api/games/${GAME_ID}/analyses`) {
        if (startResponse.status === 202) {
          currentJob = (startResponse.body as { job: Job }).job
        }
        return json(startResponse.status, startResponse.body)
      }
      if (url === '/api/analysis/cancel') {
        currentJob = running({ judged: 5 })
        return json(200, { job: currentJob })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
  })

  afterEach(() => vi.unstubAllGlobals())

  it('analyzes my moves, shows progress, and refreshes the card when saved', async () => {
    await openGames()
    const gameCard = screen.getByRole('article', { name: 'Win against Rival' })
    expect(within(gameCard).getByText('My moves · Not analyzed')).toBeInTheDocument()

    polls = [running(), finished('completed', { versionIds: [12] })]
    fireEvent.click(within(gameCard).getByRole('button', { name: 'Analyze my moves against Rival' }))
    analyzed = true

    const start = requests.find((request) => request.url === `/api/games/${GAME_ID}/analyses`)
    expect(start?.init).toMatchObject({
      method: 'POST',
      credentials: 'same-origin',
      body: JSON.stringify({ perspective: 'my_moves' }),
    })
    const panel = await screen.findByRole('region', { name: 'Game analysis' })
    expect(within(panel).getByRole('heading', { name: 'Analyzing your moves against Rival' }))
      .toBeInTheDocument()
    expect(await within(panel).findByText('First pass · 3 of 40 moves', {}, { timeout: 3_000 }))
      .toBeInTheDocument()
    expect(within(panel).getByRole('progressbar', { name: 'First pass' }))
      .toHaveAttribute('value', String(3 / 40))
    expect(within(panel).getByRole('progressbar', { name: 'Second pass' }))
      .toHaveAttribute('value', '0')
    expect(within(gameCard).getByText('My moves · Analyzing…')).toBeInTheDocument()
    expect(within(gameCard).getByRole('button', { name: 'Analyze my moves against Rival' }))
      .toBeDisabled()
    expect(within(gameCard).getByText('Available when the current analysis finishes.'))
      .toBeInTheDocument()

    expect(await screen.findByRole('heading', { name: 'Analysis saved' }, { timeout: 3_000 }))
      .toBeInTheDocument()
    const refreshed = await screen.findByText('2 Blunders · 1 Mistake · 3 Inaccuracies')
    expect(refreshed.closest('p')).toHaveTextContent('My moves · Analyzed')
    expect(screen.getByRole('button', { name: 'Reanalyze my moves against Rival' })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByRole('region', { name: 'Game analysis' })).not.toBeInTheDocument()
  })

  it('offers opponent and both-sides analysis behind a keyboard-closable More control', async () => {
    await openGames()
    const gameCard = screen.getByRole('article', { name: 'Win against Rival' })
    const more = within(gameCard).getByRole('button', {
      name: 'More analysis options for the game against Rival',
    })
    expect(more).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(more)
    expect(more).toHaveAttribute('aria-expanded', 'true')
    fireEvent.keyDown(within(gameCard).getByRole('button', { name: 'Analyze Opponent Moves' }), {
      key: 'Escape',
    })
    expect(more).toHaveAttribute('aria-expanded', 'false')
    expect(more).toHaveFocus()

    startResponse = { status: 202, body: { job: running({ perspective: 'both' }) } }
    fireEvent.click(more)
    fireEvent.click(within(gameCard).getByRole('button', { name: 'Analyze Both Sides' }))

    expect(await screen.findByRole('heading', { name: 'Analyzing both sides against Rival' }))
      .toBeInTheDocument()
    const start = requests.find((request) => request.url === `/api/games/${GAME_ID}/analyses`)
    expect(start?.init?.body).toBe(JSON.stringify({ perspective: 'both' }))
    expect(within(gameCard).getByText('Opponent moves · Analyzing…')).toBeInTheDocument()
  })

  it('explains a recoverable engine failure and retries the same analysis', async () => {
    await openGames()
    polls = [finished('failed', { failure: 'engine_unavailable' })]
    fireEvent.click(screen.getByRole('button', { name: 'Analyze my moves against Rival' }))

    const alert = await screen.findByRole('alert', {}, { timeout: 3_000 })
    expect(alert).toHaveTextContent('Analysis failed')
    expect(alert).toHaveTextContent('Stockfish could not be started. Nothing was saved.')
    expect(screen.getByRole('link', { name: 'Open Admin' })).toHaveAttribute('href', '#/admin')

    fireEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await waitFor(() => expect(requests.filter(
      (request) => request.url === `/api/games/${GAME_ID}/analyses`,
    )).toHaveLength(2))
    expect(await screen.findByRole('heading', { name: 'Analyzing your moves against Rival' }))
      .toBeInTheDocument()
  })

  it('explains a refused start with the next step', async () => {
    await openGames()
    startResponse = { status: 409, body: { error: 'engine_profile_missing' } }
    fireEvent.click(screen.getByRole('button', { name: 'Analyze my moves against Rival' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Analysis did not start')
    expect(alert).toHaveTextContent('Analysis needs an engine benchmark first.')
    expect(screen.getByRole('link', { name: 'Open Admin' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Analyze my moves against Rival' })).toBeEnabled()
  })

  it('cancels the running analysis and says nothing was saved', async () => {
    currentJob = running()
    await openGames()
    expect(await screen.findByRole('heading', { name: 'Analyzing your moves against Rival' }))
      .toBeInTheDocument()

    polls = [finished('canceled')]
    fireEvent.click(screen.getByRole('button', { name: 'Cancel Analysis' }))
    expect(await screen.findByRole('button', { name: 'Stopping…' })).toBeDisabled()
    expect(requests.find((request) => request.url === '/api/analysis/cancel')?.init)
      .toMatchObject({ method: 'POST', credentials: 'same-origin' })

    expect(await screen.findByRole('heading', { name: 'Analysis canceled' }, { timeout: 3_000 }))
      .toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Game analysis' }))
      .getByText(/Nothing was saved\./)).toBeInTheDocument()
  })

  it('can analyze again after a cancel without a stuck Stopping button', async () => {
    currentJob = running()
    await openGames()
    await screen.findByRole('heading', { name: 'Analyzing your moves against Rival' })

    polls = [finished('canceled')]
    fireEvent.click(screen.getByRole('button', { name: 'Cancel Analysis' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Analyze Again' }, { timeout: 3_000 }))

    expect(await screen.findByRole('button', { name: 'Cancel Analysis' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Stopping…' })).not.toBeInTheDocument()
  })

  it('shows the running analysis when another start is refused as busy', async () => {
    await openGames()
    startResponse = { status: 409, body: { error: 'analysis_busy' } }
    currentJob = running({ perspective: 'both' })

    fireEvent.click(screen.getByRole('button', { name: 'Analyze my moves against Rival' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Another analysis is already running.')
    expect(await screen.findByRole('heading', { name: 'Analyzing both sides against Rival' }))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel Analysis' })).toBeEnabled()
  })

  it('marks a failed analysis on the card it belongs to', async () => {
    await openGames()
    polls = [finished('failed', { failure: 'engine_failed' })]
    fireEvent.click(screen.getByRole('button', { name: 'Analyze my moves against Rival' }))

    const gameCard = screen.getByRole('article', { name: 'Win against Rival' })
    expect(await within(gameCard).findByText(
      'Last analysis failed. Nothing was saved.', {}, { timeout: 3_000 },
    )).toBeInTheDocument()
    expect(within(gameCard).getByText(/Opponent moves · Not analyzed/)).not.toHaveTextContent('failed')
  })

  it('updates a card loaded from an older page in place when its analysis is saved', async () => {
    const newer = Array.from({ length: 50 }, (_, index) =>
      card(`newer${String(index).padStart(3, '0')}`, `Newer${index}`, false))
    let olderAnalyzed = false
    catalog = (url) => {
      if (url === '/api/games?offset=0&limit=50') {
        return { total: 51, offset: 0, limit: 50, games: newer }
      }
      if (url === '/api/games?offset=50&limit=50') {
        return { total: 51, offset: 50, limit: 50, games: [card(GAME_ID, 'OlderRival', olderAnalyzed)] }
      }
      return undefined
    }
    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Load older games' }))
    const olderCard = await screen.findByRole('article', { name: 'Win against OlderRival' })

    startResponse = { status: 202, body: { job: running() } }
    polls = [finished('completed', { versionIds: [12] })]
    fireEvent.click(within(olderCard).getByRole('button', {
      name: 'Analyze my moves against OlderRival',
    }))
    olderAnalyzed = true

    expect(await within(olderCard).findByText(
      '2 Blunders · 1 Mistake · 3 Inaccuracies', {}, { timeout: 3_000 },
    )).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(51)
  })

  it('marks results from an earlier engine profile', async () => {
    catalog = () => ({
      total: 1,
      offset: 0,
      limit: 50,
      games: [{
        ...card(GAME_ID, 'Rival', true),
        analysis: {
          ...card(GAME_ID, 'Rival', true).analysis,
          myMoves: { ...card(GAME_ID, 'Rival', true).analysis.myMoves!, currentProfile: false },
        },
      }],
    })
    await openGames()

    expect(screen.getByText('Earlier engine profile · reanalyze to update')).toBeInTheDocument()
  })

  it('keeps following the analysis when one progress check is temporarily unavailable', async () => {
    currentJob = running()
    await openGames()
    await screen.findByRole('heading', { name: 'Analyzing your moves against Rival' })

    let unavailableOnce = true
    const realFetch = vi.mocked(fetch).getMockImplementation()!
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input.toString() === '/api/analysis' && unavailableOnce) {
        unavailableOnce = false
        return json(503, { error: 'session_unavailable' })
      }
      return realFetch(input, init)
    })
    polls = [running({ judged: 9 })]

    expect(await screen.findByText('First pass · 9 of 40 moves', {}, { timeout: 4_000 }))
      .toBeInTheDocument()
    expect(screen.queryByText('Lichess connection is unavailable')).not.toBeInTheDocument()
  })

  it('does not show a finished analysis from before the view opened', async () => {
    currentJob = finished('completed', { versionIds: [3] })
    await openGames()

    await waitFor(() => expect(requests.some((request) => request.url === '/api/analysis')).toBe(true))
    expect(screen.queryByRole('region', { name: 'Game analysis' })).not.toBeInTheDocument()
  })

  it('never offers analysis for catalog-only games', async () => {
    await openGames()
    const catalogOnly = screen.getByRole('article', { name: 'Loss against VariantFan' })

    expect(within(catalogOnly).getByText('Catalog only — analysis unavailable')).toBeInTheDocument()
    expect(within(catalogOnly).queryByRole('button', { name: /Analyze/ })).not.toBeInTheDocument()
  })
})

async function openGames() {
  render(<App />)
  fireEvent.click(screen.getByRole('link', { name: 'Games' }))
  await screen.findByRole('article', { name: 'Win against Rival' })
}

function json(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

function connectedSession() {
  return {
    connected: true,
    account: {
      id: 'owner-id',
      username: 'ChessOwner',
      ratings: { bullet: 1801, blitz: 1765, rapid: 1902, classical: null, correspondence: 2020 },
    },
  }
}

function card(id: string, opponentName: string, analyzed: boolean) {
  return {
    id,
    playedAt: '2026-11-13T10:00:00Z',
    result: 'win',
    ownerColor: 'white',
    ownerRating: 1800,
    ownerRatingChange: null,
    opponent: { type: 'human', name: opponentName, rating: 1750, title: null, aiLevel: null },
    speed: 'blitz',
    rated: true,
    variant: 'standard',
    timeControl: { initialSeconds: 300, incrementSeconds: 3, daysPerTurn: null, label: '5+3' },
    opening: { eco: 'C20', name: "King's Pawn Game" },
    analysis: analyzed
      ? {
          eligible: true,
          status: 'analyzed',
          myMoves: {
            id: 12,
            completedAt: '2026-11-14T12:02:00Z',
            currentProfile: true,
            moves: 40,
            inaccuracies: 3,
            mistakes: 1,
            blunders: 2,
          },
          opponentMoves: null,
        }
      : { eligible: true, status: 'not_analyzed', myMoves: null, opponentMoves: null },
  }
}

function catalogOnlyCard() {
  return {
    ...card('game0002', 'VariantFan', false),
    result: 'loss',
    variant: 'chess960',
    analysis: { eligible: false, status: 'unsupported_variant', myMoves: null, opponentMoves: null },
  }
}
