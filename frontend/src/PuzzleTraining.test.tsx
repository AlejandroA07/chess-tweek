import { StrictMode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PuzzleTraining, puzzleRoute } from './PuzzleTraining'

const FEN = '5rk1/5pb1/B1p4p/2p5/8/5q1P/PQ4P1/R5K1 w - - 0 27'
const LONG_OPENING = 'Sicilian Defense: Accelerated Dragon, Maróczy Bind, Gurgenidze Variation'

describe('PuzzleTraining', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('parses only allowlisted Puzzles routes', () => {
    expect(puzzleRoute('#/puzzles')).toEqual({ kind: 'dashboard' })
    expect(puzzleRoute('#/puzzles/random')).toEqual({ kind: 'random' })
    expect(puzzleRoute('#/puzzles/category/opening-family/Caro-Kann_Defense')).toEqual({
      kind: 'category', category: 'opening-family', key: 'Caro-Kann_Defense',
    })
    expect(puzzleRoute('#/puzzles/category/rating/fork')).toBeNull()
    expect(puzzleRoute('#/puzzles/category/theme/fork%20x')).toBeNull()
    expect(puzzleRoute('#/puzzles/other')).toBeNull()
  })

  it('asks for a Lichess connection before training', () => {
    vi.stubGlobal('fetch', vi.fn())
    render(<PuzzleTraining connectionState="disconnected" route={{ kind: 'dashboard' }} onSessionFailure={vi.fn()} />)
    expect(screen.getByRole('heading', { name: 'Connect Lichess to train puzzles' })).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('shows Random, planned modules, grouped themes, and an opening search on the dashboard', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      expect(input.toString()).toBe('/api/training/puzzles/categories')
      return response({ categories: categories() })
    }))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'dashboard' }} onSessionFailure={vi.fn()} />)

    expect(screen.getByRole('link', { name: 'Start Random' })).toHaveAttribute('href', '#/puzzles/random')
    expect(screen.getByRole('region', { name: 'Choose a theme or opening' })).toBeInTheDocument()
    expect(screen.getAllByText('Planned')).toHaveLength(2)
    const fork = await screen.findByRole('link', { name: 'Fork' })
    expect(fork).toHaveAttribute('href', '#/puzzles/category/theme/fork')
    expect(screen.getByRole('region', { name: 'Motifs themes' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Mates themes' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Caro-Kann Defense' })).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Find an opening'), { target: { value: 'caro' } })
    expect(screen.getByRole('link', { name: 'Caro-Kann Defense' })).toHaveAttribute(
      'href', '#/puzzles/category/opening-family/Caro-Kann_Defense',
    )
    expect(screen.getByText('1 match.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Find an opening'), { target: { value: 'gurgenidze' } })
    expect(screen.getByRole('link', { name: LONG_OPENING })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Find an opening'), { target: { value: 'zz' } })
    expect(screen.getByText('No openings match.')).toBeInTheDocument()
  })

  it('offers a retry when categories cannot load and reports a revoked session', async () => {
    const onSessionFailure = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 401 })))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'dashboard' }} onSessionFailure={onSessionFailure} />)
    expect(await screen.findByText(/categories could not be loaded/)).toBeInTheDocument()
    expect(onSessionFailure).toHaveBeenCalledWith('not_connected')
  })

  it('plays a Random puzzle with a Wrong Move, an opponent reply, and a classified completion', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push(`${init?.method ?? 'GET'} ${url} ${init?.body ?? ''}`.trim())
      if (url.endsWith('/categories')) return response({ categories: categories() })
      if (url.endsWith('/random/attempts')) return response(attempt(), 201)
      if (url.endsWith('/attempts/7/moves')) {
        const move = JSON.parse(String(init?.body)).move
        if (move === 'a6b7') return response({ outcome: 'wrong', opponentReply: null, wrongMoves: 1, completion: null })
        if (move === 'b2g7') return response({ outcome: 'correct', opponentReply: 'g8g7', wrongMoves: 1, completion: null })
        if (move === 'g2f3') {
          return response({ outcome: 'solved', opponentReply: null, wrongMoves: 1, completion: completion() })
        }
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'random' }} onSessionFailure={vi.fn()} />)

    expect(screen.getByRole('heading', { name: 'Random puzzle' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Loading a puzzle…' })).toBeInTheDocument()

    expect(await screen.findByRole('heading', { name: 'White to move' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'White to move' })).toHaveFocus())
    expect(screen.getByText('New puzzle')).toBeInTheDocument()
    expect(screen.getByText('11 s')).toBeInTheDocument()

    play('a6', 'b7')
    expect(await screen.findByText(/Not the solution/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'a6, white bishop' })).toBeInTheDocument()

    play('b2', 'g7')
    expect(await screen.findByRole('button', { name: 'g7, black king' })).toBeInTheDocument()
    expect(screen.getByText(/Your opponent replied/)).toBeInTheDocument()

    play('g2', 'f3')
    expect(await screen.findByText('Placed in Wrong Moves.')).toBeInTheDocument()
    expect(screen.getByText('4.2 s of 11 s')).toBeInTheDocument()
    expect(screen.getByText('Placed in Wrong Moves.').closest('[tabindex="-1"]')).toHaveFocus()
    expect(screen.getByText('Themes: Fork, short')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the source game on Lichess' })).toHaveAttribute(
      'rel', 'noreferrer',
    )
    expect(screen.getByRole('button', { name: 'Next puzzle' })).toBeEnabled()
    expect(requests).toContain('POST /api/training/puzzles/attempts/7/moves {"move":"b2g7"}')
    expect(requests.filter((request) => request.includes('/random/attempts'))).toHaveLength(1)

    // The solved board stays open for local exploration; nothing more is sent.
    const sent = requests.length
    fireEvent.click(screen.getByRole('button', { name: 'g7, black king' }))
    fireEvent.click(screen.getByRole('button', { name: 'g6, empty' }))
    expect(await screen.findByRole('button', { name: 'g6, black king' })).toBeInTheDocument()
    expect(requests).toHaveLength(sent)
  })

  it('highlights the hint square and plays out Show solution as Hard to Solve', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: categories() })
      if (url.endsWith('/categories/theme/fork/attempts')) return response(attempt('retiring'), 201)
      if (url.endsWith('/attempts/7/hint')) return response({ fromSquare: 'b2' })
      if (url.endsWith('/attempts/7/reveal')) {
        return response({
          remainingMoves: ['b2g7', 'g8g7', 'g2f3'],
          completion: completion({ previousList: 'retiring', list: 'hard_to_solve', wrongMoves: 0, hintUsed: true }),
        })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'category', category: 'theme', key: 'fork' }}
        onSessionFailure={vi.fn()}
      />,
    )
    expect(await screen.findByRole('heading', { name: 'Fork' })).toBeInTheDocument()
    expect(await screen.findByText('Retiring')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Hint' }))
    expect(await screen.findByRole('button', { name: 'b2, white queen, hint: move this piece' }))
      .toHaveAttribute('data-hint', 'true')
    expect(screen.getByRole('button', { name: 'Hint shown on the board' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Show solution' }))
    expect(await screen.findByText('Moved from Retiring to Hard to Solve.')).toBeInTheDocument()
    expect(screen.getByText('4.2 s, solution shown')).toBeInTheDocument()
    // The board shows the revealed line in the same render as the result, without a lagging effect.
    expect(screen.getByRole('button', { name: 'f3, white pawn' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'g7, black king' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next in Fork' })).toBeEnabled()
  })

  it('explains an exhausted pool and an attempt that ended elsewhere', async () => {
    let started = false
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: categories() })
      if (url.endsWith('/random/attempts')) {
        if (!started) {
          started = true
          return response({ error: 'no_puzzle_available' }, 404)
        }
        return response(attempt(), 201)
      }
      if (url.endsWith('/attempts/7/hint')) return response({ error: 'attempt_finished' }, 409)
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'random' }} onSessionFailure={vi.fn()} />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'every stored puzzle is already in one of your Training Lists, or the library is empty',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Try another puzzle' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Hint' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This attempt has ended')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Try another puzzle' })).toHaveFocus())
  })

  it('starts exactly one attempt when a puzzle route opens, even under StrictMode', async () => {
    const starts: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: categories() })
      if (url.endsWith('/random/attempts')) {
        starts.push(url)
        return response(attempt(), 201)
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(
      <StrictMode>
        <PuzzleTraining connectionState="connected" route={{ kind: 'random' }} onSessionFailure={vi.fn()} />
      </StrictMode>,
    )
    expect(await screen.findByRole('heading', { name: 'White to move' })).toBeInTheDocument()
    expect(starts).toHaveLength(1)
  })

  it('never rounds a time under the limit up to the limit', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: categories() })
      if (url.endsWith('/random/attempts')) return response(attempt(), 201)
      if (url.endsWith('/attempts/7/reveal')) {
        return response({ remainingMoves: ['b2g7', 'g8g7', 'g2f3'], completion: completion({ elapsedMillis: 10_960 }) })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'random' }} onSessionFailure={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Show solution' }))
    expect(await screen.findByText('10.9 s, solution shown')).toBeInTheDocument()
  })

  it('rejects a move result whose outcome and fields disagree', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: categories() })
      if (url.endsWith('/random/attempts')) return response(attempt(), 201)
      if (url.endsWith('/attempts/7/moves')) {
        return response({ outcome: 'correct', opponentReply: null, wrongMoves: 0, completion: null })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'random' }} onSessionFailure={vi.fn()} />)
    await screen.findByRole('heading', { name: 'White to move' })
    play('b2', 'g7')
    expect(await screen.findByRole('alert')).toHaveTextContent('That action could not be completed')
    expect(await screen.findByRole('button', { name: 'b2, white queen' })).toBeInTheDocument()
  })

  it('rejects a response that would reveal an unexpected shape', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: categories() })
      if (url.endsWith('/random/attempts')) return response({ ...attempt(), solutionMoves: ['b2g7'], rating: 99_999 }, 201)
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'random' }} onSessionFailure={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('A puzzle could not be started')
    expect(screen.queryByRole('heading', { name: 'White to move' })).not.toBeInTheDocument()
  })
})

function play(from: string, to: string) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${from}, white`) }))
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${to},`) }))
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function categories() {
  return [
    { kind: 'theme', key: 'fork', name: 'Fork', group: 'Motifs', parentKey: null },
    { kind: 'theme', key: 'mateIn1', name: 'Mate in 1', group: 'Mates', parentKey: null },
    { kind: 'opening-family', key: 'Caro-Kann_Defense', name: 'Caro-Kann Defense', group: 'Openings', parentKey: null },
    {
      kind: 'opening-variation',
      key: 'Sicilian_Defense_Accelerated_Dragon_Gurgenidze_Variation',
      name: LONG_OPENING,
      group: 'Openings',
      parentKey: 'Sicilian_Defense',
    },
  ]
}

function attempt(currentList: string | null = null) {
  return {
    attemptId: 7,
    puzzleId: 42,
    externalId: 'lDWH9',
    positionFen: FEN,
    setupMove: 'g3f3',
    rating: 1536,
    ownerMoves: 2,
    fastSolveLimitMillis: 11_000,
    currentList,
  }
}

function completion(overrides: Record<string, unknown> = {}) {
  return {
    previousList: null,
    list: 'wrong_moves',
    successes: 0,
    strikes: 0,
    wrongMoves: 1,
    hintUsed: false,
    elapsedMillis: 4_200,
    fastSolveLimitMillis: 11_000,
    gameUrl: 'https://lichess.org/game/OHeXKNku/black#52',
    themes: ['fork', 'short'],
    ...overrides,
  }
}
