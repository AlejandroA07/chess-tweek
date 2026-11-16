import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from './App'
import { formatVariation, moveLabel } from './GameReview'
import { parseReview } from './reviewData'

const GAME_ID = 'game0001'
const START = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'
const PLIES = [
  ['white', 'e4', 'e2e4', 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1'],
  ['black', 'e5', 'e7e5', 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2'],
  ['white', 'Bc4', 'f1c4', 'rnbqkbnr/pppp1ppp/8/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR b KQkq - 1 2'],
  ['black', 'Nc6', 'b8c6', 'r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR w KQkq - 2 3'],
  ['white', 'Qh5', 'd1h5', 'r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3'],
  ['black', 'Nf6', 'g8f6', 'r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4'],
  ['white', 'Qxf7#', 'h5f7', 'r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4'],
] as const

type Review = ReturnType<typeof review>

function card(analyzed: { my: boolean; opponent: boolean }, overrides: Record<string, unknown> = {}) {
  const version = (id: number) => ({
    id,
    completedAt: '2026-11-14T12:02:00Z',
    currentProfile: true,
    moves: 3,
    inaccuracies: 0,
    mistakes: 0,
    blunders: 1,
  })
  return {
    id: GAME_ID,
    source: 'lichess',
    playedAt: '2026-11-13T10:00:00Z',
    result: 'loss',
    ownerColor: 'black',
    ownerRating: 1300,
    ownerRatingChange: -6,
    opponent: { type: 'human', name: 'LondonSystemEnthusiastWithAVeryLongName', rating: 1340, title: null, aiLevel: null },
    speed: 'rapid',
    rated: true,
    variant: 'standard',
    timeControl: { initialSeconds: 600, incrementSeconds: 0, daysPerTurn: null, label: '10+0' },
    opening: { eco: 'C23', name: "Bishop's Opening" },
    analysis: {
      eligible: true,
      status: analyzed.my || analyzed.opponent ? 'analyzed' : 'not_analyzed',
      myMoves: analyzed.my ? version(12) : null,
      opponentMoves: analyzed.opponent ? version(13) : null,
    },
    ...overrides,
  }
}

function line(move: string, moveSan: string, score: { cp?: number; mate?: number }, pv: string[], pvSan: string[]) {
  return {
    move,
    moveSan,
    cp: score.cp ?? null,
    mate: score.mate ?? null,
    win: 100,
    draw: 600,
    loss: 300,
    pv,
    pvSan,
  }
}

function judged(ply: number, label: string, overrides: Record<string, unknown> = {}) {
  const [, san, uci] = PLIES[ply - 1]
  return {
    ply,
    san,
    uci,
    fenBefore: ply === 1 ? START : PLIES[ply - 2][3],
    phase: 'opening',
    pass: 'quick',
    label,
    mateEvent: 'none',
    playedBest: label === 'none',
    bestMove: uci,
    bestMoveSan: san,
    expectedBefore: 1000,
    expectedAfter: 1000,
    loss: 0,
    afterTerminal: 'none',
    before: [line(uci, san, { cp: 20 }, [uci], [san])],
    after: [line('g1f3', 'Nf3', { cp: -20 }, ['g1f3'], ['Nf3'])],
    tactics: [],
    ...overrides,
  }
}

function phaseStatistics(moves: number, blunders: number) {
  return { moves, inaccuracies: 0, mistakes: 0, blunders, averageLoss: blunders * 40, averageCentipawnLoss: blunders * 1000 / Math.max(moves, 1) }
}

function review(options: { my?: boolean; opponent?: boolean } = {}) {
  const my = options.my ?? true
  const opponent = options.opponent ?? false
  return {
    game: card({ my, opponent }),
    initialFen: START,
    plies: PLIES.map(([color, san, uci, fen], index) => ({ ply: index + 1, color, san, uci, fen })),
    phases: { source: 'inferred', middlegameStart: 20, endgameStart: null },
    myMoves: my ? { id: 12, completedAt: '2026-11-14T12:02:00Z', currentProfile: true } : null,
    opponentMoves: opponent ? { id: 13, completedAt: '2026-11-14T12:03:00Z', currentProfile: true } : null,
    selected: my
      ? {
          side: 'my_moves',
          versionId: 12,
          analyzedColor: 'black',
          engineName: 'Stockfish 18',
          quickNodes: 800000,
          deepNodes: 4000000,
          completedAt: '2026-11-14T12:02:00Z',
          currentProfile: true,
          statistics: {
            ...phaseStatistics(3, 1),
            averageLoss: 13.3,
            averageCentipawnLoss: 333.3,
            byPhase: {
              opening: { ...phaseStatistics(3, 1), averageLoss: 13.3, averageCentipawnLoss: 333.3 },
              middlegame: phaseStatistics(0, 0),
              endgame: phaseStatistics(0, 0),
            },
          },
          evaluations: [1, 2, 3, 4, 5, 6, 7].map((index) => ({
            index,
            whiteExpected: index >= 6 ? 2000 : 1000,
            whiteCp: index === 7 ? null : index === 6 ? null : 20,
            whiteMate: index === 6 ? 1 : null,
          })),
          moves: [
            judged(2, 'none'),
            judged(4, 'none'),
            judged(6, 'blunder', {
              pass: 'deep',
              mateEvent: 'allowed_mate',
              playedBest: false,
              bestMove: 'd8e7',
              bestMoveSan: 'Qe7',
              expectedBefore: 900,
              expectedAfter: 0,
              loss: 900,
              before: [line('d8e7', 'Qe7', { cp: -40 }, ['d8e7', 'g1f3', 'g8f6'], ['Qe7', 'Nf3', 'Nf6'])],
              after: [line('h5f7', 'Qxf7#', { mate: 1 }, ['h5f7'], ['Qxf7#'])],
              tactics: [{ theme: 'allowed_mate', kind: 'allowed', piece: null, square: null, targets: [] }],
            }),
          ],
        }
      : null,
  }
}

describe('Game review', () => {
  let reviews: Record<string, { status: number; body: unknown }>
  let requests: { url: string; init?: RequestInit }[]
  let startResponse: { status: number; body: unknown }

  beforeEach(() => {
    window.history.replaceState(null, '', `/#/games/lichess/${GAME_ID}`)
    requests = []
    reviews = { '': { status: 200, body: review() } }
    startResponse = {
      status: 202,
      body: {
        job: {
          gameId: GAME_ID,
          source: 'lichess',
          perspective: 'opponent_moves',
          state: 'running',
          pass: 'starting',
          judged: 0,
          total: 0,
          failure: null,
          startedAt: '2026-11-14T12:00:00Z',
          finishedAt: null,
          versionIds: [],
        },
      },
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push({ url, init })
      if (url === '/api/health') return json(200, { status: 'ok' })
      if (url === '/api/lichess/session') return json(200, connectedSession())
      if (url === '/api/analysis') return json(200, { job: null })
      if (url === `/api/games/lichess/${GAME_ID}/analyses`) return json(startResponse.status, startResponse.body)
      const prefix = `/api/games/lichess/${GAME_ID}/review`
      if (url.startsWith(prefix)) {
        const response = reviews[url.slice(prefix.length)]
        if (response) return json(response.status, response.body)
      }
      if (url.startsWith('/api/games?')) {
        return json(200, { total: 1, offset: 0, limit: 50, games: [card({ my: true, opponent: false })] })
      }
      throw new Error(`Unexpected request: ${url}`)
    }))
  })

  afterEach(() => vi.unstubAllGlobals())

  it('opens a chess.com game by its site and ID', async () => {
    const chessComReview = { ...review(), game: card({ my: true, opponent: false }, { id: '145200000001', source: 'chesscom' }) }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push({ url, init })
      if (url === '/api/health') return json(200, { status: 'ok' })
      if (url === '/api/lichess/session') return json(200, connectedSession())
      if (url === '/api/analysis') return json(200, { job: null })
      if (url === '/api/games/chesscom/145200000001/review') return json(200, chessComReview)
      throw new Error(`Unexpected request: ${url}`)
    }))
    window.history.replaceState(null, '', '/#/games/chesscom/145200000001')
    render(<App />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Game Review' })).toBeInTheDocument()
    expect(await screen.findByText('LondonSystemEnthusiastWithAVeryLongName', { exact: false }))
      .toBeInTheDocument()
    expect(requests.some((request) => request.url === '/api/games/chesscom/145200000001/review')).toBe(true)
    expect(requests.some((request) => request.url.includes('/lichess/145200000001'))).toBe(false)
  })

  it('opens from Games with Open Game and shows the game, statistics, and findings', async () => {
    window.history.replaceState(null, '', '/#/games')
    render(<App />)
    const link = await screen.findByRole('link', {
      name: 'Open game against LondonSystemEnthusiastWithAVeryLongName',
    })
    expect(link).toHaveAttribute('href', `#/games/lichess/${GAME_ID}`)
    window.history.replaceState(null, '', `/#/games/lichess/${GAME_ID}`)
    fireEvent(window, new HashChangeEvent('hashchange'))

    expect(await screen.findByRole('heading', { level: 1, name: 'Game Review' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Games' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Back to Games' })).toHaveAttribute('href', '#/games')
    expect(screen.getByText('C23 · Bishop\'s Opening')).toBeInTheDocument()
    expect(screen.getByText('Loss', { selector: 'p' })).toBeInTheDocument()

    const analysisRegion = screen.getByRole('region', { name: 'Analysis' })
    expect(within(analysisRegion).getByRole('tab', { name: 'My Moves' })).toHaveAttribute('aria-selected', 'true')
    expect(within(analysisRegion).getByText('Blunders', { selector: 'dt' }).nextSibling).toHaveTextContent('1')
    expect(within(analysisRegion).getByText('13.3 pts')).toBeInTheDocument()
    expect(within(analysisRegion).getByText('Average centipawn loss', { selector: 'dt' }).nextSibling)
      .toHaveTextContent('333.3')
    expect(within(analysisRegion).getByRole('table', { name: 'By phase (inferred)' })).toBeInTheDocument()

    const views = within(analysisRegion).getAllByRole('button', { name: /^View mistake/ })
    expect(views).toHaveLength(1)
    const findings = views.map((view) => view.closest('li') as HTMLElement)
    expect(findings[0]).toHaveTextContent('Blunder')
    expect(findings[0]).toHaveTextContent('3… Nf6')
    expect(findings[0]).toHaveTextContent('Allowed a forced mate.')
    expect(findings[0]).toHaveTextContent('45% → 0%')
    expect(findings[0]).toHaveTextContent('45.0 pts')
    expect(findings[0]).toHaveTextContent('+0.4 → #1')

    expect(screen.getByRole('button', { name: '3… Nf6, Blunder' })).toBeInTheDocument()
  })

  it('shows the mistake first, then the best move and the reply as playable engine lines', async () => {
    const { container } = render(<App />)
    const view = await screen.findByRole('button', { name: 'View mistake 3… Nf6' })
    fireEvent.click(view)

    // The board shows the mistake itself: the position after it, with the played move marked.
    expect(view).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('img', {
      name: /Position after your blunder 3… Nf6\. Best was 3… Qe7\. Black at the bottom\./,
    })).toBeInTheDocument()
    expect(container.querySelector('[data-square="g8"]')).toHaveAttribute('data-marked', 'true')
    expect(container.querySelector('[data-square="f6"]')).toHaveAttribute('data-marked', 'true')
    expect(container.querySelector('[data-testid="best-move-arrow"]')).toBeNull()
    expect(screen.getByText(/You played/)).toHaveTextContent('You played 3… Nf6 · Blunder · 45.0 pts lost')

    // The best move is a button that plays it on the board.
    fireEvent.click(screen.getByRole('button', { name: 'Show Best Move: 3… Qe7' }))
    expect(screen.getByText('Best line', { selector: 'strong' }).closest('[role="status"]'))
      .toHaveTextContent('Best line · engine line, not played in the game')
    expect(screen.getByRole('img', { name: /Engine line, not played in the game: 3… Qe7\./ })).toBeInTheDocument()
    expect(container.querySelector('[data-square="d8"]')).toHaveAttribute('data-marked', 'true')
    expect(container.querySelector('[data-square="e7"] span')).toHaveTextContent('\u265B')
    // The bar shows the line's evaluation: +0.4 for White, 60% for White from WDL 100/600/300 for Black.
    expect(screen.getByTestId('evaluation-bar')).toHaveAccessibleName(
      'Evaluation bar: +0.4 for White, White\'s expected score 60 percent.',
    )

    // The line can be stepped through with the buttons, its moves, and the keyboard.
    fireEvent.click(screen.getByRole('button', { name: 'Next line move' }))
    expect(screen.getByRole('img', { name: /Engine line, not played in the game: 3… Qe7 4\. Nf3\./ })).toBeInTheDocument()
    fireEvent.keyDown(screen.getByLabelText(/Board navigation/), { key: 'End' })
    expect(screen.getByRole('img', { name: /3… Qe7 4\. Nf3 Nf6\./ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'End of the line' })).toBeDisabled()
    fireEvent.click(within(screen.getByRole('list', { name: 'Best line moves' })).getByRole('button', { name: '3… Qe7' }))
    expect(screen.getByRole('img', { name: /Engine line, not played in the game: 3… Qe7\./ })).toBeInTheDocument()

    // Back to the game returns to the mistake.
    fireEvent.click(screen.getByRole('button', { name: 'Back to the Game' }))
    expect(screen.getByRole('img', { name: /Position after your blunder 3… Nf6\./ })).toBeInTheDocument()

    // The reply line shows why it was a blunder.
    fireEvent.click(screen.getByRole('button', { name: 'Show Their Best Reply' }))
    expect(screen.getByRole('img', { name: /Engine line, not played in the game: 4\. Qxf7#\./ })).toBeInTheDocument()
    expect(container.querySelector('[data-square="f7"] span')).toHaveTextContent('\u265B')

    // Moving in the game leaves the line.
    fireEvent.click(screen.getByRole('button', { name: '1. e4' }))
    expect(screen.queryByText('Back to the Game')).not.toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Position after 1\. e4\./ })).toBeInTheDocument()
  })

  it('labels proven tactics and answers the three coach questions for the shown mistake', async () => {
    render(<App />)
    const card = (await screen.findByRole('button', { name: 'View mistake 3… Nf6' })).closest('li') as HTMLElement
    expect(within(within(card).getByRole('list', { name: 'Tactic labels' })).getByText('Allowed mate'))
      .toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Coach' })).not.toBeInTheDocument()

    fireEvent.click(within(card).getByRole('button', { name: 'View mistake 3… Nf6' }))
    const coach = screen.getByRole('region', { name: 'Coach' })
    expect(within(coach).getByRole('button', { name: 'Why?' })).toHaveAttribute('aria-pressed', 'true')
    expect(coach).toHaveTextContent(
      '3… Nf6 lowered your expected score from 45% to 0% (45.0 points). The engine preferred 3… Qe7, which keeps it near 45%. It allows a forced mate: 4. Qxf7#.',
    )

    fireEvent.click(within(coach).getByRole('button', { name: 'What was the threat?' }))
    expect(coach).toHaveTextContent('After 3… Nf6, your opponent’s best reply is 4. Qxf7#, a checkmate.')
    expect(coach).toHaveTextContent('Best line instead: 3… Qe7 4. Nf3 Nf6.')

    fireEvent.click(within(coach).getByRole('button', { name: 'What should I train?' }))
    expect(within(coach).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Defensive checks: before moving, list every check the opponent has.',
      'Mate-in-two puzzles (pattern theme: mateIn2).',
    ])

    // Entering an engine line hides the coach; returning shows it again.
    fireEvent.click(screen.getByRole('button', { name: 'Show Best Move: 3… Qe7' }))
    expect(screen.queryByRole('region', { name: 'Coach' })).not.toBeInTheDocument()
  })

  it('opens engine lines from the finding card', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Show best move 3… Qe7' }))
    expect(screen.getByRole('img', { name: /Engine line, not played in the game: 3… Qe7\./ })).toBeInTheDocument()
    const card = screen.getByRole('button', { name: 'View mistake 3… Nf6' }).closest('li') as HTMLElement
    const details = card.querySelector('details') as HTMLElement
    expect(details).toHaveAttribute('open')
    fireEvent.click(within(within(card).getByRole('list', { name: 'Best line for 3… Nf6' })).getByRole('button', { name: '4. Nf3' }))
    expect(screen.getByRole('img', { name: /3… Qe7 4\. Nf3\./ })).toBeInTheDocument()
    fireEvent.click(within(within(card).getByRole('list', { name: 'Reply line after 3… Nf6' })).getByRole('button', { name: '4. Qxf7#' }))
    expect(screen.getByRole('img', { name: /Engine line, not played in the game: 4\. Qxf7#\./ })).toBeInTheDocument()
  })

  it('navigates moves with buttons, the move list, and the keyboard, and flips the board', async () => {
    const { container } = render(<App />)
    const board = await screen.findByRole('img', { name: /Starting position\. Black at the bottom\./ })
    expect(container.querySelector('[data-square]')).toHaveAttribute('data-square', 'h1')
    expect(screen.getByRole('button', { name: 'Previous move' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Next move' }))
    expect(screen.getByRole('img', { name: /Position after 1\. e4\./ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1. e4' })).toHaveAttribute('aria-current', 'step')
    expect(container.querySelector('[data-square="e4"]')).toHaveAttribute('data-marked', 'true')

    const panel = screen.getByLabelText(/Board navigation/)
    fireEvent.keyDown(panel, { key: 'End' })
    expect(screen.getByRole('img', { name: /Position after 4\. Qxf7#\./ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next move' })).toBeDisabled()
    fireEvent.keyDown(panel, { key: 'ArrowLeft' })
    expect(screen.getByRole('img', { name: /Position after 3… Nf6\./ })).toBeInTheDocument()
    fireEvent.keyDown(panel, { key: 'Home' })
    expect(screen.getByRole('img', { name: /Starting position/ })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '2. Bc4' }))
    expect(screen.getByRole('img', { name: /Position after 2\. Bc4\./ })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Flip Board' }))
    expect(screen.getByText('White at the bottom')).toBeInTheDocument()
    expect(container.querySelector('[data-square]')).toHaveAttribute('data-square', 'a8')
    expect(board).toBeDefined()
  })

  it('shows an evaluation bar beside the board that follows the shown position', async () => {
    render(<App />)
    const bar = await screen.findByTestId('evaluation-bar')
    // The start position has no evaluation of its own, so the first evaluated position is used.
    expect(bar).toHaveAccessibleName('Evaluation bar: +0.2 for White, White\'s expected score 50 percent.')
    expect(bar).toHaveAttribute('data-orientation', 'black')
    expect(bar.firstElementChild).toHaveStyle({ height: '50%' })

    fireEvent.click(screen.getByRole('button', { name: '3… Nf6, Blunder' }))
    expect(bar).toHaveAccessibleName('Evaluation bar: #1 for White, White\'s expected score 100 percent.')
    expect(bar.firstElementChild).toHaveStyle({ height: '100%' })
    // White is winning and sits at the top when Black is at the bottom.
    expect(within(bar).getByText('#1')).toHaveAttribute('data-position', 'top')

    fireEvent.click(screen.getByRole('button', { name: 'Last position' }))
    expect(within(bar).getByText('1-0')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Flip Board' }))
    expect(bar).toHaveAttribute('data-orientation', 'white')
    expect(within(bar).getByText('1-0')).toHaveAttribute('data-position', 'bottom')
  })

  it('hides the evaluation bar when the side has no analysis', async () => {
    reviews[''] = { status: 200, body: review({ my: false }) }
    render(<App />)
    await screen.findByRole('heading', { name: 'Not analyzed yet' })
    expect(screen.queryByTestId('evaluation-bar')).not.toBeInTheDocument()
  })

  it('disables the unanalyzed side and starts its analysis from the review', async () => {
    render(<App />)
    const opponentTab = await screen.findByRole('tab', { name: /Opponent Moves/ })
    expect(opponentTab).toHaveTextContent('Opponent Moves · Not analyzed')
    expect(opponentTab).toBeDisabled()

    reviews['?side=opponent_moves'] = { status: 200, body: review() }
    fireEvent.click(screen.getByRole('button', { name: 'Analyze Opponent Moves' }))
    await waitFor(() => {
      const start = requests.find((request) => request.url === `/api/games/lichess/${GAME_ID}/analyses`)
      expect(start?.init).toMatchObject({ method: 'POST', body: JSON.stringify({ perspective: 'opponent_moves' }) })
    })
    expect(await screen.findByRole('region', { name: 'Game analysis' })).toBeInTheDocument()
  })

  it('switches to the opponent side when both are analyzed', async () => {
    reviews[''] = { status: 200, body: review({ opponent: true }) }
    const opponentReview: Review = review({ opponent: true })
    if (opponentReview.selected) {
      opponentReview.selected = {
        ...opponentReview.selected,
        side: 'opponent_moves',
        versionId: 13,
        analyzedColor: 'white',
        moves: [],
      }
    }
    reviews['?side=opponent_moves'] = { status: 200, body: opponentReview }
    render(<App />)
    fireEvent.click(await screen.findByRole('tab', { name: 'Opponent Moves' }))
    expect(await screen.findByText('No inaccuracies, mistakes, or blunders in these moves.')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Opponent Moves' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('White at the bottom')).toBeInTheDocument()
  })

  it('offers every analysis when the game has not been analyzed', async () => {
    reviews[''] = { status: 200, body: review({ my: false }) }
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Not analyzed yet' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Analyze My Moves' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Analyze Both Sides' })).toBeEnabled()
  })

  it('explains that catalog-only games have no moves to replay', async () => {
    reviews[''] = {
      status: 200,
      body: {
        ...review({ my: false }),
        game: card({ my: false, opponent: false }, {
          variant: 'chess960',
          analysis: { eligible: false, status: 'unsupported_variant', myMoves: null, opponentMoves: null },
        }),
        plies: [],
        phases: null,
      },
    }
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Moves are not available for this game' }))
      .toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Analyze My Moves' })).not.toBeInTheDocument()
  })

  it('reports a missing game without a retry', async () => {
    reviews[''] = { status: 404, body: { error: 'game_not_found' } }
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'This game is not stored' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })

  it('recovers from an unavailable review with Retry', async () => {
    reviews[''] = { status: 503, body: { error: 'review_unavailable' } }
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'This review could not be loaded' })).toBeInTheDocument()
    reviews[''] = { status: 200, body: review() }
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('button', { name: 'View mistake 3… Nf6' })).toBeInTheDocument()
  })

  it('treats a malformed review as unavailable instead of showing part of it', async () => {
    const broken = review()
    if (broken.selected) broken.selected = { ...broken.selected, versionId: 99 }
    reviews[''] = { status: 200, body: broken }
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'This review could not be loaded' })).toBeInTheDocument()
  })

  it('returns to the connection prompt when the session is gone', async () => {
    reviews[''] = { status: 401, body: { error: 'not_connected' } }
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Connect Lichess to view stored games' })).toBeInTheDocument()
  })

  it.each([
    '/#/games/lichess/bad!id',
    '/#/games/chesscom/game0001',
    '/#/games/elsewhere/game0001',
    '/#/games/game0001',
  ])('ignores the review link %s because it is not a valid game', async (link) => {
    window.history.replaceState(null, '', link)
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()
    expect(requests.some((request) => request.url.includes('/review'))).toBe(false)
  })
})

describe('Review formatting and parsing', () => {
  it('numbers moves and variations from either side', () => {
    expect(moveLabel(1, 'e4')).toBe('1. e4')
    expect(moveLabel(6, 'Nf6')).toBe('3… Nf6')
    expect(formatVariation(6, ['Qe7', 'Nf3', 'Nf6'])).toBe('3… Qe7 4. Nf3 Nf6')
    expect(formatVariation(7, ['Qxf7#'])).toBe('4. Qxf7#')
  })

  it('rejects out-of-range or inconsistent evidence', () => {
    expect(() => parseReview(review())).not.toThrow()
    const outOfRange = review()
    if (outOfRange.selected) {
      outOfRange.selected.moves = [judged(6, 'blunder', { ply: 9 })]
    }
    expect(() => parseReview(outOfRange)).toThrow()
    const badLine = review()
    if (badLine.selected) {
      badLine.selected.moves = [judged(2, 'none', { before: [line('e7e5', 'e5', { cp: 1, mate: 2 }, ['e7e5'], ['e5'])] })]
    }
    expect(() => parseReview(badLine)).toThrow()
    const badFen = { ...review(), initialFen: '<script>' }
    expect(() => parseReview(badFen)).toThrow()
  })
})

function json(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as Response
}

function connectedSession() {
  return {
    connected: true,
    account: {
      id: 'owner-id',
      username: 'ChessOwner',
      ratings: { bullet: 1801, blitz: 1765, rapid: 1302, classical: null, correspondence: null },
    },
  }
}
