import { StrictMode } from 'react'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PuzzleTraining, puzzleRoute } from './PuzzleTraining'

/** After 1.e4 e5 2.Nf3 Nc6: White to move. */
const ITALIAN = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3'
const LONG_OPENING = 'Sicilian Defense: Accelerated Dragon, Maróczy Bind, Gurgenidze Variation with a very long name'

type Call = { method: string; url: string }

describe('Own Puzzles', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    window.history.replaceState(null, '', '/')
  })

  it('parses only allowlisted Own Puzzles routes', () => {
    expect(puzzleRoute('#/puzzles/own')).toEqual({ kind: 'own-dashboard' })
    expect(puzzleRoute('#/puzzles/own/new')).toEqual({ kind: 'own-new', site: 'all', order: 'newest' })
    expect(puzzleRoute('#/puzzles/own/new/chesscom/random')).toEqual({ kind: 'own-new', site: 'chesscom', order: 'random' })
    expect(puzzleRoute('#/puzzles/own/discarded')).toEqual({ kind: 'own-discarded' })
    expect(puzzleRoute('#/puzzles/own/lists/worth-repeating')).toEqual({ kind: 'own-list', list: 'worth_repeating' })
    expect(puzzleRoute('#/puzzles/own/lists/mastered')).toEqual({ kind: 'own-list', list: 'mastered' })
    expect(puzzleRoute('#/puzzles/own/solve/lichess/newest')).toEqual({
      kind: 'own-solve', site: 'lichess', order: 'newest', first: null,
    })
    expect(puzzleRoute('#/puzzles/own/solve/all/random/41')).toEqual({
      kind: 'own-solve', site: 'all', order: 'random', first: 41,
    })
    expect(puzzleRoute('#/puzzles/own/repetition')).toEqual({ kind: 'own-repetition' })
    expect(puzzleRoute('#/puzzles/own/repetition/hard-to-solve')).toEqual({ kind: 'own-session', list: 'hard_to_solve' })
    expect(puzzleRoute('#/puzzles/own/repetition/mastered')).toEqual({ kind: 'own-mastered' })
    expect(puzzleRoute('#/puzzles/own/repetition/mastered/opening/Indian%20Game')).toEqual({
      kind: 'own-mastered-practice', group: 'opening', key: 'Indian Game',
    })
    expect(puzzleRoute('#/puzzles/own/repetition/mastered/phase/endgame')).toEqual({
      kind: 'own-mastered-practice', group: 'phase', key: 'endgame',
    })

    expect(puzzleRoute('#/puzzles/own/new/chess.com/newest')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/solve/all/oldest')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/solve/all/newest/0')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/solve/all/newest/99999999999999999')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/repetition/mastered')).not.toEqual({ kind: 'own-session' })
    expect(puzzleRoute('#/puzzles/own/repetition/mastered/phase/opening_phase')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/repetition/mastered/tactic/Fork')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/repetition/mastered/opening/%20Indian')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/repetition/mastered/opening/%E0%A4%A')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/lists/unclassified')).toBeNull()
    expect(puzzleRoute('#/puzzles/own/other')).toBeNull()
  })

  it('shows New, Repetition, Discarded, the Training Lists, and every category with counts', async () => {
    stubFetch(() => ({}))
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'own-dashboard' }} onSessionFailure={vi.fn()} />)

    expect(await screen.findByRole('heading', { name: 'New Own Puzzles 4' })).toBeInTheDocument()
    expect(screen.getByText(/3 from Lichess and 1 from chess.com/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Browse New puzzles' })).toHaveAttribute('href', '#/puzzles/own/new')
    expect(screen.getByRole('link', { name: 'Open Own Repetition' })).toHaveAttribute('href', '#/puzzles/own/repetition')
    expect(screen.getByRole('link', { name: 'View Discarded' })).toHaveAttribute('href', '#/puzzles/own/discarded')

    const lists = screen.getByRole('region', { name: 'Training Lists' })
    expect(within(lists).getByRole('link', { name: 'View Worth Repeating' }))
      .toHaveAttribute('href', '#/puzzles/own/lists/worth-repeating')
    // An empty list has nothing to view.
    expect(within(lists).queryByRole('link', { name: 'View Wrong Moves' })).not.toBeInTheDocument()

    const categories = screen.getByRole('region', { name: 'Your Own Puzzles by category' })
    expect(within(categories).getByRole('region', { name: 'By opening' })).toHaveTextContent(LONG_OPENING)
    expect(within(categories).getByRole('region', { name: 'By game phase' })).toHaveTextContent('Middlegame2')
    expect(within(categories).getByRole('region', { name: 'By tactic' })).toHaveTextContent('Fork2')
    expect(within(categories).getByRole('region', { name: 'By tactic' })).toHaveTextContent('Back-rank mate1')
    expect(within(categories).getByRole('region', { name: 'By tactic' })).toHaveTextContent('Pin1')
    // Summaries are counts only; practice by group is for Mastered puzzles.
    expect(within(categories).queryByRole('link')).not.toBeInTheDocument()
  })

  it('explains how Own Puzzles are created when there are none', async () => {
    stubFetch((call) => call.url === '/api/training/own-puzzles'
      ? {
        body: overview({
          newPuzzles: 0,
          newBySource: { lichess: 0, chesscom: 0 },
          discarded: 0,
          groups: { total: 0, openings: [], phases: [], tactics: [] },
        }),
      }
      : {})
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'own-dashboard' }} onSessionFailure={vi.fn()} />)
    expect(await screen.findByRole('heading', { name: 'No Own Puzzles yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open Games' })).toHaveAttribute('href', '#/games')
  })

  it('lists New puzzles by site with their source links, solving choices, and pages', async () => {
    const calls = stubFetch((call) => call.url.startsWith('/api/training/own-puzzles/new?')
      ? { body: { total: 25, puzzles: [puzzle(), puzzle({ id: 42, label: 'inaccuracy', openingName: null, tactics: [] })] } }
      : {})
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'own-new', site: 'chesscom', order: 'random' }}
        onSessionFailure={vi.fn()}
      />,
    )

    const row = (await screen.findByRole('heading', { name: 'Blunder · Middlegame' })).closest('li') as HTMLElement
    expect(row).toHaveTextContent(LONG_OPENING)
    expect(row).toHaveTextContent('Missed a fork')
    expect(row).toHaveTextContent('chess.com game')
    expect(row).toHaveTextContent('Move 12 (Black)')
    expect(within(row).getByRole('link', { name: /^Open source game/ }))
      .toHaveAttribute('href', '#/games/chesscom/145200000001/ply/24')
    expect(within(row).getByRole('link', { name: /^Solve Blunder/ }))
      .toHaveAttribute('href', '#/puzzles/own/solve/chesscom/random/41')
    expect(screen.getByText('Unknown opening')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Solve New puzzles' })).toHaveAttribute('href', '#/puzzles/own/solve/chesscom/random')
    expect(screen.getByRole('option', { name: 'chess.com (1)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'All sites (4)' })).toBeInTheDocument()
    expect(screen.getByLabelText('Solve in')).toHaveValue('random')
    expect(calls).toContainEqual({ method: 'GET', url: '/api/training/own-puzzles/new?offset=0&limit=20&source=chesscom' })

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(calls).toContainEqual({
      method: 'GET', url: '/api/training/own-puzzles/new?offset=20&limit=20&source=chesscom',
    }))
    expect(await screen.findByText('21–25 of 25')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Games from'), { target: { value: 'lichess' } })
    expect(window.location.hash).toBe('#/puzzles/own/new/lichess/random')
  })

  it('confirms a Discard by name, then reloads; a refused Discard says nothing changed', async () => {
    let refuse = false
    const calls = stubFetch((call) => {
      if (call.url === '/api/training/own-puzzles/41/discard') {
        return refuse ? { status: 409, body: { error: 'own_puzzle_classified' } } : { status: 204 }
      }
      return {}
    })
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'own-new', site: 'all', order: 'newest' }} onSessionFailure={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: /^Discard Blunder at move 12 \(black\)/ }))
    const confirm = screen.getByRole('group', { name: /Discard Blunder at move 12 \(black\)/ })
    expect(confirm).toHaveTextContent('You can restore it from Discarded.')
    await waitFor(() => expect(within(confirm).getByRole('button', { name: 'Keep it' })).toHaveFocus())
    fireEvent.click(within(confirm).getByRole('button', { name: 'Keep it' }))
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Discard Blunder/ })).toHaveFocus())

    fireEvent.click(screen.getByRole('button', { name: /^Discard Blunder/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Discard puzzle' }))
    expect(await screen.findByRole('status')).toHaveTextContent('discarded. Restore it from Discarded at any time.')
    expect(calls).toContainEqual({ method: 'POST', url: '/api/training/own-puzzles/41/discard' })
    await waitFor(() => expect(calls.filter((call) => call.url.startsWith('/api/training/own-puzzles/new?'))).toHaveLength(2))

    refuse = true
    fireEvent.click(await screen.findByRole('button', { name: /^Discard Blunder/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Discard puzzle' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('already in a Training List, so it can no longer be discarded')
  })

  it('restores a Discarded puzzle to New', async () => {
    const calls = stubFetch((call) => {
      if (call.url.startsWith('/api/training/own-puzzles/discarded?')) return { body: { total: 1, puzzles: [puzzle()] } }
      if (call.url === '/api/training/own-puzzles/41/restore') return { status: 204 }
      return {}
    })
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'own-discarded' }} onSessionFailure={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: /^Restore Blunder/ }))
    expect(await screen.findByRole('status')).toHaveTextContent('restored to New Own Puzzles.')
    expect(calls).toContainEqual({ method: 'POST', url: '/api/training/own-puzzles/41/restore' })
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Discarded Own Puzzles' })).toHaveFocus())
  })

  it('solves a New puzzle in the chosen order with the opponent reply and links to its source move', async () => {
    const calls = stubFetch((call) => {
      if (call.url.startsWith('/api/training/own-puzzles/new/attempts')) return { status: 201, body: attempt() }
      if (call.url === '/api/training/own-puzzles/attempts/9/moves') {
        const solved = calls.filter((entry) => entry.url.endsWith('/moves')).length > 1
        return {
          body: solved
            ? { outcome: 'solved', opponentReply: null, wrongMoves: 0, completion: ownCompletion() }
            : { outcome: 'correct', opponentReply: 'a7a6', wrongMoves: 0, completion: null },
        }
      }
      return {}
    })
    render(
      <StrictMode>
        <PuzzleTraining
          connectionState="connected"
          route={{ kind: 'own-solve', site: 'chesscom', order: 'random', first: null }}
          onSessionFailure={vi.fn()}
        />
      </StrictMode>,
    )

    expect(await screen.findByRole('heading', { name: 'White to move' })).toBeInTheDocument()
    expect(screen.getByText('Own Puzzles · New · Random order · chess.com')).toBeInTheDocument()
    expect(screen.getByText(/Blunder · Middlegame · Sicilian Defense · 2 moves to find/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← New Own Puzzles' })).toHaveAttribute('href', '#/puzzles/own/new/chesscom/random')
    expect(calls.filter((call) => call.url.includes('/new/attempts'))).toEqual([
      { method: 'POST', url: '/api/training/own-puzzles/new/attempts?source=chesscom&order=random' },
    ])

    play('f1', 'b5')
    expect(await screen.findByText(/Your opponent replied/)).toBeInTheDocument()
    play('b5', 'a4')
    expect(await screen.findByText('Placed in Worth Repeating.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the source game at move 12 (black)' }))
      .toHaveAttribute('href', '#/games/chesscom/145200000001/ply/24')
    // A solution is never sent before Show solution.
    expect(calls.filter((call) => call.url.endsWith('/reveal'))).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: 'Next puzzle' }))
    await waitFor(() => expect(calls.filter((call) => call.url.includes('/new/attempts'))).toHaveLength(2))
  })

  it('discards the open New puzzle after confirmation and explains an empty filtered pool', async () => {
    let starts = 0
    const calls = stubFetch((call) => {
      if (call.url.startsWith('/api/training/own-puzzles/new/attempts')) {
        starts += 1
        return starts === 1 ? { status: 201, body: attempt() } : { status: 404, body: { error: 'no_new_own_puzzles' } }
      }
      if (call.url === '/api/training/own-puzzles/41/discard') return { status: 204 }
      return {}
    })
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'own-solve', site: 'lichess', order: 'newest', first: null }}
        onSessionFailure={vi.fn()}
      />,
    )

    fireEvent.click(await screen.findByRole('button', { name: 'Discard' }))
    expect(screen.getByRole('group', { name: /Discard this puzzle\?/ })).toHaveTextContent('the next New puzzle opens')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Keep solving' })).toHaveFocus())
    fireEvent.click(screen.getByRole('button', { name: 'Discard puzzle' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('No New Own Puzzles from Lichess right now.')
    expect(screen.getByRole('status')).toHaveTextContent('Puzzle discarded.')
    expect(calls).toContainEqual({ method: 'POST', url: '/api/training/own-puzzles/41/discard' })
  })

  it('opens a chosen puzzle first and explains one that is no longer New', async () => {
    const calls = stubFetch((call) => {
      if (call.url === '/api/training/own-puzzles/41/attempts') return { status: 404, body: { error: 'own_puzzle_not_found' } }
      if (call.url.startsWith('/api/training/own-puzzles/new/attempts')) return { status: 201, body: attempt() }
      return {}
    })
    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'own-solve', site: 'all', order: 'newest', first: 41 }}
        onSessionFailure={vi.fn()}
      />,
    )

    expect(await screen.findByRole('alert')).toHaveTextContent('That puzzle is no longer New')
    fireEvent.click(screen.getByRole('button', { name: 'Try another puzzle' }))
    expect(await screen.findByRole('heading', { name: 'White to move' })).toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST').map((call) => call.url)).toEqual([
      '/api/training/own-puzzles/41/attempts',
      '/api/training/own-puzzles/new/attempts?source=all&order=newest',
    ])
  })

  it('runs an Own Practice Session without offering Discard', async () => {
    stubFetch((call) => {
      if (call.url === '/api/training/own-puzzles/repetition/hard_to_solve/sessions') {
        return { status: 201, body: { sessionId: 3, list: 'hard_to_solve', total: 2 } }
      }
      if (call.url === '/api/training/own-puzzles/sessions/3/attempts') {
        return { status: 201, body: { attempt: attempt('hard_to_solve'), position: 1, total: 2 } }
      }
      return {}
    })
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'own-session', list: 'hard_to_solve' }} onSessionFailure={vi.fn()} />)

    expect(await screen.findByText('1 of 2')).toBeInTheDocument()
    expect(screen.getByText('Own Repetition · Practice Session')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Discard' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← Own Repetition' })).toHaveAttribute('href', '#/puzzles/own/repetition')
  })

  it('shows the Own Repetition lists and practices Mastered puzzles by group without changing them', async () => {
    const calls = stubFetch((call) => {
      if (call.url === '/api/training/own-puzzles/repetition/mastered/opening/Indian%20Game/sessions') {
        return { status: 201, body: { sessionId: 4, list: 'mastered', total: 1 } }
      }
      if (call.url === '/api/training/own-puzzles/sessions/4/attempts') {
        return { status: 201, body: { attempt: attempt('mastered'), position: 1, total: 1 } }
      }
      return {}
    })
    const { unmount } = render(
      <PuzzleTraining connectionState="connected" route={{ kind: 'own-repetition' }} onSessionFailure={vi.fn()} />,
    )
    expect(await screen.findByRole('link', { name: 'Practice Worth Repeating' }))
      .toHaveAttribute('href', '#/puzzles/own/repetition/worth-repeating')
    expect(screen.getAllByText('No Own Puzzles in this list yet.')).toHaveLength(2)
    expect(screen.getByRole('link', { name: 'Browse Mastered' })).toHaveAttribute('href', '#/puzzles/own/repetition/mastered')
    unmount()

    const mastered = render(
      <PuzzleTraining connectionState="connected" route={{ kind: 'own-mastered' }} onSessionFailure={vi.fn()} />,
    )
    expect(await screen.findByRole('link', { name: 'Indian Game' }))
      .toHaveAttribute('href', '#/puzzles/own/repetition/mastered/opening/Indian%20Game')
    expect(screen.getByRole('link', { name: 'Endgame' })).toHaveAttribute('href', '#/puzzles/own/repetition/mastered/phase/endgame')
    expect(screen.getByRole('link', { name: 'Fork' })).toHaveAttribute('href', '#/puzzles/own/repetition/mastered/tactic/fork')
    mastered.unmount()

    render(
      <PuzzleTraining
        connectionState="connected"
        route={{ kind: 'own-mastered-practice', group: 'opening', key: 'Indian Game' }}
        onSessionFailure={vi.fn()}
      />,
    )
    expect(await screen.findByRole('heading', { name: 'Indian Game', level: 2 })).toBeInTheDocument()
    expect(screen.getByText('Practice only: a hint or Show solution does not change Mastered status.')).toBeInTheDocument()
    expect(calls).toContainEqual({
      method: 'POST', url: '/api/training/own-puzzles/repetition/mastered/opening/Indian%20Game/sessions',
    })
  })

  it('reports a revoked session and rejects an unexpected response shape', async () => {
    const onSessionFailure = vi.fn()
    stubFetch((call) => call.url === '/api/training/own-puzzles' ? { status: 401, body: {} } : {})
    const first = render(
      <PuzzleTraining connectionState="connected" route={{ kind: 'own-dashboard' }} onSessionFailure={onSessionFailure} />,
    )
    await waitFor(() => expect(onSessionFailure).toHaveBeenCalledWith('not_connected'))
    first.unmount()

    stubFetch((call) => call.url.startsWith('/api/training/own-puzzles/new?')
      ? { body: { total: 1, puzzles: [{ ...puzzle(), sourceGame: { source: 'chesscom', gameId: '../x', ply: 24 } }] } }
      : {})
    render(<PuzzleTraining connectionState="connected" route={{ kind: 'own-new', site: 'all', order: 'newest' }} onSessionFailure={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('New Own Puzzles could not be loaded. Nothing has changed.')
    expect(screen.queryByRole('link', { name: /Open source game/ })).not.toBeInTheDocument()
  })
})

/** Answers every Own Puzzle request with defaults unless {@code answer} overrides it. */
function stubFetch(answer: (call: Call) => { status?: number; body?: unknown }) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { method: init?.method ?? 'GET', url: input.toString() }
    calls.push(call)
    const custom = answer(call)
    if (custom.status !== undefined || custom.body !== undefined) {
      return custom.status === 204
        ? new Response(null, { status: 204 })
        : response(custom.body ?? {}, custom.status ?? 200)
    }
    if (call.url === '/api/training/own-puzzles') return response(overview())
    if (call.url.startsWith('/api/training/own-puzzles/new?')) return response({ total: 1, puzzles: [puzzle()] })
    if (call.url === '/api/training/own-puzzles/repetition') return response({ lists: lists() })
    if (call.url === '/api/training/own-puzzles/repetition/mastered') return response(groups())
    throw new Error(`Unexpected request: ${call.method} ${call.url}`)
  }))
  return calls
}

function play(from: string, to: string) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${from}, white`) }))
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${to},`) }))
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function lists() {
  return [
    { list: 'hard_to_solve', puzzles: 1 },
    { list: 'wrong_moves', puzzles: 0 },
    { list: 'worth_repeating', puzzles: 2 },
    { list: 'retiring', puzzles: 0 },
    { list: 'mastered', puzzles: 1 },
  ]
}

function groups() {
  return {
    total: 6,
    openings: [{ key: LONG_OPENING, puzzles: 4 }, { key: 'Indian Game', puzzles: 2 }],
    phases: [{ key: 'middlegame', puzzles: 2 }, { key: 'endgame', puzzles: 4 }],
    tactics: [{ key: 'fork', puzzles: 2 }, { key: 'back_rank', puzzles: 1 }, { key: 'pin', puzzles: 1 }],
  }
}

function overview(overrides: Record<string, unknown> = {}) {
  return {
    newPuzzles: 4,
    newBySource: { lichess: 3, chesscom: 1 },
    discarded: 1,
    lists: lists(),
    groups: groups(),
    ...overrides,
  }
}

function puzzle(overrides: Record<string, unknown> = {}) {
  return {
    id: 41,
    label: 'blunder',
    phase: 'middlegame',
    openingFamily: 'Sicilian Defense',
    openingName: LONG_OPENING,
    tactics: [{ theme: 'fork', kind: 'missed' }],
    sourceGame: { source: 'chesscom', gameId: '145200000001', ply: 24 },
    gamePlayedAt: '2026-11-20T18:00:00Z',
    ...overrides,
  }
}

function attempt(currentList: string | null = null) {
  return {
    attemptId: 9,
    puzzle: puzzle(),
    positionFen: ITALIAN,
    previousMove: 'b8c6',
    ownerMoves: 2,
    fastSolveLimitMillis: 11_000,
    currentList,
  }
}

function ownCompletion() {
  return {
    previousList: null,
    list: 'worth_repeating',
    successes: 0,
    strikes: 0,
    wrongMoves: 0,
    hintUsed: false,
    elapsedMillis: 5_000,
    fastSolveLimitMillis: 11_000,
    sourceGame: { source: 'chesscom', gameId: '145200000001', ply: 24 },
  }
}
