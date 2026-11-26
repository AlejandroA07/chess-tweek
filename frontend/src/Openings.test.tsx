import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Openings } from './Openings'
import { explorerHref, parseExplorerRoute, START_FEN } from './openingExplorer'

const AFTER_E4 = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1'
const AFTER_E4_C6 = 'rnbqkbnr/pp1ppppp/2p5/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2'

type Handler = (url: string, init?: RequestInit) => Response | undefined

function stub(handler: Handler) {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = input.toString()
    requests.push(`${init?.method ?? 'GET'} ${decodeURIComponent(url.replace(/\+/g, ' '))}`)
    const result = handler(url, init)
    if (result === undefined) throw new Error(`Unexpected request: ${url}`)
    return result
  }))
  return requests
}

function response(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

function line(moves: { uci: string; san: string; fen: string }[], fen = moves.at(-1)?.fen ?? START_FEN) {
  return { start: START_FEN, moves, fen }
}

function statistics(extra: Record<string, unknown> = {}) {
  return {
    opening: { eco: 'B10', name: 'Caro-Kann Defense' },
    results: { white: 50, draws: 30, black: 20 },
    moves: [
      { uci: 'd2d4', san: 'd4', averageRating: 2410, results: { white: 40, draws: 20, black: 10 }, opening: null },
      { uci: 'b1c3', san: 'Nc3', averageRating: 2390, results: { white: 10, draws: 10, black: 10 }, opening: null },
    ],
    topGames: [{
      id: 'abcdEFGH', uci: 'd2d4', winner: 'white', year: 2019,
      white: { name: 'A Very Long Grandmaster Name That Wraps', rating: 2700, title: 'GM' },
      black: { name: 'Other Master', rating: 2650, title: null },
    }],
    fetchedAt: '2026-11-27T10:00:00Z',
    stale: false,
    ...extra,
  }
}

const STARTER = {
  source: 'starter', opening: 'Caro-Kann Defense', title: 'The Caro-Kann idea', text: 'Black prepares ...d5.\nSecond line.',
  starter: { opening: 'Caro-Kann Defense', title: 'The Caro-Kann idea', moves: ['e4', 'c6'], text: 'Black prepares ...d5.\nSecond line.' },
  updatedAt: null,
}

describe('Openings', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('parses only well-formed explorer routes', () => {
    expect(parseExplorerRoute('#/openings/explore')).toEqual({ from: null, moves: [] })
    expect(parseExplorerRoute('#/openings/explore/line/e2e4,c7c6')).toEqual({ from: null, moves: ['e2e4', 'c7c6'] })
    const from = explorerHref({ from: AFTER_E4, moves: ['c7c6'] })
    expect(parseExplorerRoute(from)).toEqual({ from: AFTER_E4, moves: ['c7c6'] })
    expect(explorerHref({ from: START_FEN, moves: [] })).toBe('#/openings/explore')
    expect(parseExplorerRoute('#/openings')).toBeNull()
    expect(parseExplorerRoute('#/openings/explore/line/e2e9')).toBeNull()
    expect(parseExplorerRoute('#/openings/explore/line/e2e4,,c7c6')).toBeNull()
    expect(parseExplorerRoute('#/openings/explore/from/not%20a%20fen')).toBeNull()
    expect(parseExplorerRoute('#/openings/explore/from/%E0%A4%A')).toBeNull()
  })

  it('lists starter and owner notes on the home page and links to their positions', async () => {
    stub((url) => url === '/api/explorer/notes/index' ? response({ entries: [
      { source: 'starter', opening: 'London System', title: 'The London setup', moves: ['d4', 'd5', 'Bf4'],
        uciMoves: ['d2d4', 'd7d5', 'c1f4'], fen: START_FEN, excerpt: 'White builds the same structure.' },
      { source: 'owner', opening: 'Caro-Kann Defense', title: 'Advance Variation', moves: ['e4', 'c6', 'd4', 'd5', 'e5'],
        uciMoves: ['e2e4', 'c7c6', 'd2d4', 'd7d5', 'e4e5'], fen: START_FEN, excerpt: 'My plan.' },
      { source: 'owner', opening: null, title: null, moves: [], uciMoves: [], fen: AFTER_E4, excerpt: 'Sicilian thoughts.' },
    ] }) : undefined)
    render(<Openings explore={null} onSessionFailure={vi.fn()} />)

    expect(screen.getByRole('link', { name: 'Open the explorer' })).toHaveAttribute('href', '#/openings/explore')
    const london = await screen.findByRole('link', { name: 'The London setup' })
    expect(london).toHaveAttribute('href', '#/openings/explore/line/d2d4,d7d5,c1f4')
    expect(screen.getByText('1. d4 d5 2. Bf4')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Your other notes' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Your note' })).toHaveAttribute('href', explorerHref({ from: AFTER_E4, moves: [] }))
    expect(screen.getAllByText('Your note')).toHaveLength(3)
  })

  it('shows Masters statistics, the line, and the starter note, and plays a move from the table', async () => {
    const requests = stub((url) => {
      if (url.startsWith('/api/explorer/line')) return response(line([
        { uci: 'e2e4', san: 'e4', fen: AFTER_E4 }, { uci: 'c7c6', san: 'c6', fen: AFTER_E4_C6 },
      ]))
      if (url.startsWith('/api/explorer/masters')) return response(statistics())
      if (url.startsWith('/api/explorer/notes?')) return response({ note: STARTER })
      return undefined
    })
    window.location.hash = '#/openings/explore/line/e2e4,c7c6'
    render(<Openings explore={{ from: null, moves: ['e2e4', 'c7c6'] }} onSessionFailure={vi.fn()} />)

    expect(await screen.findByText('B10')).toBeInTheDocument()
    expect(screen.getByText(/Caro-Kann Defense/, { selector: 'p' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1. e4' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'c6' })).toHaveAttribute('aria-current', 'step')
    expect(screen.getByText((_, element) => element?.tagName === 'P' &&
      element.textContent === '100 games · White wins 50% · Draws 30% · Black wins 20%')).toBeInTheDocument()
    expect(screen.getByText('57% / 29% / 14%')).toBeInTheDocument()
    expect(screen.getByText(/GM A Very Long Grandmaster Name That Wraps \(2700\)/)).toBeInTheDocument()
    expect(await screen.findByText('Starter note')).toBeInTheDocument()
    expect(screen.getByText(/Black prepares \.\.\.d5\./)).toBeInTheDocument()
    expect(requests).toContain(`GET /api/explorer/masters?fen=${AFTER_E4_C6}`)

    fireEvent.click(screen.getByRole('button', { name: 'Play d4' }))
    expect(window.location.hash).toBe('#/openings/explore/line/e2e4,c7c6,d2d4')

    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    await waitFor(() => expect(requests).toContain(`GET /api/explorer/masters?fen=${AFTER_E4}`))
    expect(screen.getByRole('button', { name: '1. e4' })).toHaveAttribute('aria-current', 'step')
  })

  it('asks Lichess with the default rapid 1600–1999 filter and keeps My games usable when rate limited', async () => {
    const requests = stub((url) => {
      if (url.startsWith('/api/explorer/line')) return response(line([]))
      if (url.startsWith('/api/explorer/masters')) return response(statistics())
      if (url.startsWith('/api/explorer/lichess')) return response({ error: 'lichess_rate_limited' }, 429, { 'Retry-After': '75' })
      if (url.startsWith('/api/explorer/my-games')) return response({
        results: { white: 2, draws: 0, black: 1 },
        moves: [{ uci: 'e2e4', san: 'e4', results: { white: 2, draws: 0, black: 1 } }],
        recentGames: [{ source: 'lichess', id: 'game0001', playedAt: '2026-11-20T10:00:00Z', ownerColor: 'white',
          ownerResult: 'win', opponentName: 'Rival', opponentRating: 1500, speed: 'rapid', nextSan: 'e4' }],
      })
      if (url.startsWith('/api/explorer/notes?')) return response({ note: null })
      return undefined
    })
    render(<Openings explore={{ from: null, moves: [] }} onSessionFailure={vi.fn()} />)
    await screen.findByText(/games · White wins/)

    fireEvent.click(screen.getByRole('radio', { name: 'Lichess' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 75 seconds. My games still works.')
    expect(requests).toContain(`GET /api/explorer/lichess?fen=${START_FEN}&speeds=rapid&ratings=1600,1800`)
    expect(screen.getByRole('checkbox', { name: 'Rapid' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '1600–1799' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: '2500 and above' })).not.toBeChecked()

    fireEvent.click(screen.getByRole('radio', { name: 'My games' }))
    const link = await screen.findByRole('link', { name: 'Won as white against Rival (1500)' })
    expect(link).toHaveAttribute('href', '#/games/lichess/game0001')
    fireEvent.change(screen.getByLabelText('Played as'), { target: { value: 'black' } })
    await waitFor(() => expect(requests).toContain(`GET /api/explorer/my-games?fen=${START_FEN}&color=black`))
  })

  it('shows a stale answer with its saved date', async () => {
    stub((url) => {
      if (url.startsWith('/api/explorer/line')) return response(line([]))
      if (url.startsWith('/api/explorer/masters')) return response(statistics({ stale: true }))
      if (url.startsWith('/api/explorer/notes?')) return response({ note: null })
      return undefined
    })
    render(<Openings explore={{ from: null, moves: [] }} onSessionFailure={vi.fn()} />)

    expect(await screen.findByText(/Lichess could not be reached, so these are the results saved on/)).toBeInTheDocument()
  })

  it('edits, hides, and restores a starter note', async () => {
    let note: unknown = STARTER
    const requests = stub((url, init) => {
      if (url.startsWith('/api/explorer/line')) return response(line([]))
      if (url.startsWith('/api/explorer/masters')) return response(statistics())
      if (url.startsWith('/api/explorer/notes') && init?.method === 'PUT') {
        const body = JSON.parse(String(init.body)) as { text: string }
        // The server stores blank text as empty, which hides the starter note.
        note = { ...STARTER, source: 'owner', text: body.text.trim() === '' ? '' : body.text, updatedAt: '2026-11-27T11:00:00Z' }
        return new Response(null, { status: 204 })
      }
      if (url.startsWith('/api/explorer/notes') && init?.method === 'DELETE') {
        note = STARTER
        return new Response(null, { status: 204 })
      }
      if (url.startsWith('/api/explorer/notes?')) return response({ note })
      return undefined
    })
    render(<Openings explore={{ from: null, moves: [] }} onSessionFailure={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Edit note' }))
    const editor = screen.getByLabelText('Your note')
    expect(editor).toHaveFocus()
    expect(editor).toHaveValue('Black prepares ...d5.\nSecond line.')
    fireEvent.change(editor, { target: { value: 'My own plan.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    expect(await screen.findByText('My own plan.', { selector: 'p' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your note was saved.')
    expect(requests.some((request) => request.startsWith('PUT /api/explorer/notes'))).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: 'Edit note' }))
    fireEvent.change(screen.getByLabelText('Your note'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    expect(await screen.findByText(/You hid the starter note “The Caro-Kann idea” here./)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Restore starter note' }))
    expect(await screen.findByText('Starter note')).toBeInTheDocument()
    expect(requests.some((request) => request.startsWith(`DELETE /api/explorer/notes?fen=${START_FEN}`))).toBe(true)
  })

  it('confirms before deleting an own note and reports a failed save', async () => {
    let fail = false
    stub((url, init) => {
      if (url.startsWith('/api/explorer/line')) return response(line([]))
      if (url.startsWith('/api/explorer/masters')) return response(statistics())
      if (url.startsWith('/api/explorer/notes') && init?.method === 'PUT') {
        return fail ? response({ error: 'training_unavailable' }, 503) : new Response(null, { status: 204 })
      }
      if (url.startsWith('/api/explorer/notes?')) return response({ note: {
        source: 'owner', opening: null, title: null, text: 'Mine', starter: null, updatedAt: '2026-11-27T11:00:00Z',
      } })
      return undefined
    })
    render(<Openings explore={{ from: null, moves: [] }} onSessionFailure={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Delete note' }))
    const confirm = screen.getByRole('group', { name: 'Delete your note' })
    expect(within(confirm).getByRole('button', { name: 'Keep note' })).toHaveFocus()
    fireEvent.click(within(confirm).getByRole('button', { name: 'Keep note' }))
    expect(screen.queryByRole('group', { name: 'Delete your note' })).not.toBeInTheDocument()

    fail = true
    fireEvent.click(screen.getByRole('button', { name: 'Edit note' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save note' }))
    expect(await screen.findByText('The note was not saved. Nothing has changed.')).toBeInTheDocument()
    expect(screen.getByLabelText('Your note')).toHaveValue('Mine')
  })

  it('reports a lost session to the app', async () => {
    const onSessionFailure = vi.fn()
    stub(() => response({ error: 'not_connected' }, 401))
    render(<Openings explore={{ from: null, moves: [] }} onSessionFailure={onSessionFailure} />)

    await waitFor(() => expect(onSessionFailure).toHaveBeenCalledWith('not_connected'))
  })
})
