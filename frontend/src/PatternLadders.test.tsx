import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PuzzleTraining, puzzleRoute } from './PuzzleTraining'

const FEN = '5rk1/5pb1/B1p4p/2p5/8/5q1P/PQ4P1/R5K1 w - - 0 27'
const BASE = '/api/training/puzzles'

describe('Pattern ladders', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('parses only allowlisted ladder routes', () => {
    expect(puzzleRoute('#/puzzles/ladders')).toEqual({ kind: 'ladders' })
    expect(puzzleRoute('#/puzzles/ladders/operaMate')).toEqual({ kind: 'ladder', pattern: 'operaMate' })
    expect(puzzleRoute('#/puzzles/ladders/operaMate/1000-1400')).toEqual({
      kind: 'ladder-play', pattern: 'operaMate', ratingPackage: '1000-1400',
    })
    expect(puzzleRoute('#/puzzles/ladders/operaMate/2600-plus')).toEqual({
      kind: 'ladder-play', pattern: 'operaMate', ratingPackage: '2600-plus',
    })
    expect(puzzleRoute('#/puzzles/ladders/operaMate/fast')).toEqual({ kind: 'ladder-fast', pattern: 'operaMate' })
    expect(puzzleRoute('#/puzzles/ladders/operaMate/1000-1500')).toBeNull()
    expect(puzzleRoute('#/puzzles/ladders/opera%20mate')).toBeNull()
    expect(puzzleRoute('#/puzzles/ladders/operaMate/1000-1400/extra')).toBeNull()
  })

  it('is offered from the Puzzle dashboard', () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ categories: [] })))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'dashboard' }} onSessionFailure={vi.fn()} />)
    expect(screen.getByRole('link', { name: 'Open Pattern ladders' })).toHaveAttribute('href', '#/puzzles/ladders')
  })

  it('lists mate and tactic patterns in Themes page order with their ladder sizes', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: [] })
      if (url.endsWith('/ladders')) return response({ patterns: patterns() })
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'ladders' }} onSessionFailure={vi.fn()} />)

    const opera = await screen.findByRole('link', { name: /^Opera mate 27,763/ })
    expect(opera).toHaveAttribute('href', '#/puzzles/ladders/operaMate')
    expect(opera).toHaveTextContent('A rook mates on the back rank, protected by a bishop.')
    const headings = screen.getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)
    expect(headings).toEqual(['Motifs', 'Advanced', 'Mate themes'])
    // Catalog order inside a section, not the server's alphabetical order.
    const mates = within(screen.getByRole('region', { name: 'Mate themes' })).getAllByRole('link')
    expect(mates.map((link) => link.getAttribute('href'))).toEqual([
      '#/puzzles/ladders/backRankMate',
      '#/puzzles/ladders/operaMate',
    ])
    expect(screen.getByRole('link', { name: /^Fork 456,127/ })).toBeInTheDocument()
  })

  it('shows every rating package with its progress and restarts one after confirmation', async () => {
    const requests: string[] = []
    let restarted = false
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push(`${init?.method ?? 'GET'} ${url}`)
      if (url.endsWith('/categories')) return response({ categories: [] })
      if (url.endsWith('/ladders/operaMate')) return response(ladder(restarted))
      if (url.endsWith('/ladders/operaMate/1000-1400/restart')) {
        restarted = true
        return new Response(null, { status: 204 })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'ladder', pattern: 'operaMate' }}
        onSessionFailure={vi.fn()}
      />,
    )

    expect(await screen.findByRole('heading', { name: 'Opera mate', level: 2 })).toBeInTheDocument()
    const packages = screen.getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)
    expect(packages).toEqual([
      'A quick climb through Opera mate',
      'Below 1000 0', '1000–1399 6,140', '1400–1799 9,624', '1800–2199 6,615', '2200–2599 20',
      '2600 and above 12',
    ])
    const below = screen.getByRole('region', { name: /^Below 1000/ })
    expect(within(below).getByText('No puzzles of this pattern in this rating range.')).toBeInTheDocument()
    expect(within(below).queryByRole('link')).not.toBeInTheDocument()

    const started = screen.getByRole('region', { name: /^1000–1399/ })
    expect(within(started).getByText('At puzzle 34 of 6,140. Last rating 1187.')).toBeInTheDocument()
    expect(within(started).getByRole('link', { name: 'Continue 1000–1399' }))
      .toHaveAttribute('href', '#/puzzles/ladders/operaMate/1000-1400')
    expect(within(screen.getByRole('region', { name: /^1400–1799/ })).getByRole('link', { name: 'Start 1400–1799' }))
      .toHaveAttribute('href', '#/puzzles/ladders/operaMate/1400-1800')

    fireEvent.click(within(started).getByRole('button', { name: 'Restart 1000–1399' }))
    expect(within(started).getByText(/Restart Opera mate, 1000–1399\?/)).toBeInTheDocument()
    await waitFor(() => expect(within(started).getByRole('button', { name: 'Keep my place' })).toHaveFocus())
    fireEvent.click(within(started).getByRole('button', { name: 'Keep my place' }))
    expect(requests.some((request) => request.includes('/restart'))).toBe(false)

    fireEvent.click(within(started).getByRole('button', { name: 'Restart 1000–1399' }))
    fireEvent.click(within(started).getByRole('button', { name: 'Restart package' }))
    expect(await within(screen.getByRole('region', { name: /^1000–1399/ })).findByText('Not started.'))
      .toBeInTheDocument()
    expect(requests).toContain(`POST ${BASE}/ladders/operaMate/1000-1400/restart`)
  })

  it('jumps to a typed position or to the last 10 or 20 puzzles of a package', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push(`${init?.method ?? 'GET'} ${url} ${init?.body ?? ''}`.trim())
      if (url.endsWith('/categories')) return response({ categories: [] })
      if (url.endsWith('/ladders/operaMate')) return response(ladder(false))
      if (url.endsWith('/jump')) return new Response(null, { status: 204 })
      throw new Error(`Unexpected request: ${url}`)
    }))
    window.location.hash = '#/puzzles/ladders/operaMate'
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'ladder', pattern: 'operaMate' }}
        onSessionFailure={vi.fn()}
      />,
    )

    const started = await screen.findByRole('region', { name: /^1000–1399/ })
    // The empty package and a package of 10 or fewer offer no shortcut they cannot use.
    expect(within(screen.getByRole('region', { name: /^Below 1000/ })).queryByLabelText(/Go to puzzle/)).toBeNull()
    const small = screen.getByRole('region', { name: /^2600 and above/ })
    expect(within(small).queryByRole('button', { name: /Last 20/ })).toBeNull()
    expect(within(small).getByRole('button', { name: /Last 10/ })).toBeInTheDocument()
    // Exactly 20 puzzles is enough for Last 20.
    const twenty = screen.getByRole('region', { name: /^2200–2599/ })
    expect(within(twenty).getByRole('button', { name: /Last 20/ })).toBeInTheDocument()

    const go = within(started).getByRole('button', { name: 'Go' })
    expect(go).toBeDisabled()
    fireEvent.change(within(started).getByLabelText('Go to puzzle (1–6,140)'), { target: { value: '6141' } })
    expect(go).toBeDisabled()
    fireEvent.change(within(started).getByLabelText('Go to puzzle (1–6,140)'), { target: { value: '980' } })
    fireEvent.click(go)
    await waitFor(() => expect(window.location.hash).toBe('#/puzzles/ladders/operaMate/1000-1400'))
    expect(requests).toContain(`POST ${BASE}/ladders/operaMate/1000-1400/jump {"position":980}`)

    fireEvent.click(within(started).getByRole('button', { name: 'Last 20 puzzles of 1000–1399' }))
    fireEvent.click(within(small).getByRole('button', { name: 'Last 10 puzzles of 2600 and above' }))
    await waitFor(() => expect(requests).toContain(`POST ${BASE}/ladders/operaMate/2600-plus/jump {"position":3}`))
    expect(requests).toContain(`POST ${BASE}/ladders/operaMate/1000-1400/jump {"position":6121}`)
  })

  it('runs a fast ladder through its rating and combination steps, then ends', async () => {
    let served = 0
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push(`${init?.method ?? 'GET'} ${url}`)
      if (url.endsWith('/categories')) {
        return response({ categories: [{ kind: 'theme', key: 'operaMate', name: 'Opera mate', group: 'Mate themes', parentKey: null, puzzles: 30_000 }] })
      }
      if (url.endsWith('/ladders/operaMate/fast')) return response({ fastLadderId: 9, total: 2 }, 201)
      if (url.endsWith('/ladders/fast/9/attempts')) {
        served += 1
        if (served === 1) {
          return response({ attempt: attempt(), position: 1, total: 2, step: { ratingLow: 0, ratingHigh: 800, combinedWith: null } }, 201)
        }
        if (served === 2) {
          return response({
            attempt: { ...attempt(), attemptId: 8, rating: 2110 },
            position: 2,
            total: 2,
            step: { ratingLow: null, ratingHigh: null, combinedWith: { key: 'pin', name: 'Pin', group: 'Motifs', puzzles: 0 } },
          }, 201)
        }
        return response({ error: 'ladder_complete' }, 409)
      }
      if (url.includes('/reveal')) {
        return response({ remainingMoves: ['b2g7', 'g8g7', 'g2f3'], completion: completion() })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'ladder-fast', pattern: 'operaMate' }}
        onSessionFailure={vi.fn()}
      />,
    )

    expect(await screen.findByRole('heading', { name: 'White to move' })).toBeInTheDocument()
    expect(screen.getByText('Rated below 800')).toBeInTheDocument()
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Show solution' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Next in fast ladder' }))
    expect(await screen.findByText('With Pin')).toBeInTheDocument()
    expect(screen.getByText('2 of 2')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Show solution' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Next in fast ladder' }))
    expect(await screen.findByText(/Fast ladder complete/)).toBeInTheDocument()
    const back = screen.getByRole('link', { name: 'Back to Opera mate' })
    expect(back).toHaveAttribute('href', '#/puzzles/ladders/operaMate')
    // The end never draws a new set by itself.
    expect(requests.filter((request) => request.endsWith('/fast'))).toHaveLength(1)
  })

  it('offers the fast ladder first on a ladder page', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: [] })
      return response(ladder(false))
    }))
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'ladder', pattern: 'operaMate' }}
        onSessionFailure={vi.fn()}
      />,
    )
    const card = await screen.findByRole('region', { name: 'A quick climb through Opera mate' })
    expect(within(card).getByRole('link', { name: 'Start fast ladder' })).toHaveAttribute(
      'href', '#/puzzles/ladders/operaMate/fast',
    )
    const regions = screen.getAllByRole('region').map((region) => region.getAttribute('aria-labelledby'))
    expect(regions.indexOf('fast-ladder-title')).toBeLessThan(regions.indexOf('package-up-to-1000-title'))
  })

  it('climbs a package with its place shown, then offers another package at the end', async () => {
    let next = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      if (url.endsWith('/categories')) {
        return response({ categories: [{ kind: 'theme', key: 'operaMate', name: 'Opera mate', group: 'Mate themes', parentKey: null, puzzles: 30_000 }] })
      }
      if (url.endsWith('/ladders/operaMate/1000-1400/attempts')) {
        next += 1
        if (next > 1) return response({ error: 'ladder_complete' }, 409)
        return response({ attempt: attempt(), progress: { presented: 35, lastRating: 1187 } }, 201)
      }
      if (url.endsWith('/attempts/7/reveal')) {
        return response({ remainingMoves: ['b2g7', 'g8g7', 'g2f3'], completion: completion() })
      }
      throw new Error(`Unexpected request: ${init?.method ?? 'GET'} ${url}`)
    }))
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'ladder-play', pattern: 'operaMate', ratingPackage: '1000-1400' }}
        onSessionFailure={vi.fn()}
      />,
    )

    expect(await screen.findByRole('heading', { name: 'White to move' })).toBeInTheDocument()
    expect(screen.getByText('Pattern ladder · 1000–1399')).toBeInTheDocument()
    expect(screen.getByText('Puzzle 35 in 1000–1399')).toBeInTheDocument()
    expect(screen.getByText(/Lichess 1187/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← Opera mate' })).toHaveAttribute('href', '#/puzzles/ladders/operaMate')

    fireEvent.click(screen.getByRole('button', { name: 'Show solution' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Next in ladder' }))

    expect(await screen.findByText(/You have reached the end of 1000–1399/)).toBeInTheDocument()
    const choose = screen.getByRole('link', { name: 'Choose a package' })
    expect(choose).toHaveAttribute('href', '#/puzzles/ladders/operaMate')
    await waitFor(() => expect(choose).toHaveFocus())
    expect(screen.queryByRole('button', { name: 'Try another puzzle' })).not.toBeInTheDocument()
  })

  it('offers a retry when a ladder cannot load and reports a revoked session', async () => {
    const onSessionFailure = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 401 })))
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'ladder', pattern: 'operaMate' }}
        onSessionFailure={onSessionFailure}
      />,
    )
    expect(await screen.findByText(/This ladder could not be loaded/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(onSessionFailure).toHaveBeenCalledWith('not_connected')
  })

  it('rejects an unexpected ladder response shape', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/categories')) return response({ categories: [] })
      const broken = ladder(false)
      broken.packages = broken.packages.slice(1)
      return response(broken)
    }))
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'ladder', pattern: 'operaMate' }}
        onSessionFailure={vi.fn()}
      />,
    )
    expect(await screen.findByText(/This ladder could not be loaded/)).toBeInTheDocument()
  })
})

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function patterns() {
  return [
    { key: 'deflection', name: 'Deflection', group: 'Advanced', puzzles: 169_277 },
    { key: 'backRankMate', name: 'Back rank mate', group: 'Mate themes', puzzles: 55_409 },
    { key: 'operaMate', name: 'Opera mate', group: 'Mate themes', puzzles: 27_763 },
    { key: 'fork', name: 'Fork', group: 'Motifs', puzzles: 456_127 },
  ]
}

function ladder(restarted: boolean) {
  const sizes = [0, 6_140, 9_624, 6_615, 20, 12]
  const keys = ['up-to-1000', '1000-1400', '1400-1800', '1800-2200', '2200-2600', '2600-plus']
  return {
    pattern: { key: 'operaMate', name: 'Opera mate', group: 'Mate themes', puzzles: 25_386 },
    packages: keys.map((key, index) => ({
      key,
      low: index === 0 ? 0 : 600 + index * 400,
      high: index === 5 ? 4100 : 1000 + index * 400,
      puzzles: sizes[index],
      progress: index === 1 && !restarted ? { presented: 34, lastRating: 1187 } : null,
    })),
  }
}

function attempt() {
  return {
    attemptId: 7,
    puzzleId: 42,
    externalId: 'lDWH9',
    positionFen: FEN,
    setupMove: 'g3f3',
    rating: 1187,
    ownerMoves: 2,
    fastSolveLimitMillis: 11_000,
    currentList: null,
  }
}

function completion() {
  return {
    previousList: null,
    list: 'hard_to_solve',
    successes: 0,
    strikes: 0,
    wrongMoves: 0,
    hintUsed: true,
    elapsedMillis: 4_200,
    fastSolveLimitMillis: 11_000,
    gameUrl: 'https://lichess.org/game/OHeXKNku/black#52',
    themes: ['operaMate', 'mateIn2'],
  }
}
