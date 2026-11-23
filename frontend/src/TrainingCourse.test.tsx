import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TrainingCourse } from './TrainingCourse'

describe('TrainingCourse', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keeps original course material browseable without an account', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      expect(input.toString()).toBe('/api/training/courses/grandmaster-thinking-method')
      return response(course())
    }))

    render(
      <TrainingCourse
        connectionState="disconnected"
        lessonKey={null}
        onSessionFailure={vi.fn()}
      />,
    )

    expect(await screen.findByRole('heading', { name: 'Grandmaster Thinking Method' }))
      .toBeInTheDocument()
    expect(screen.getByText('Course material remains available.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Start lesson' })).toHaveAttribute(
      'href', '#/training/lessons/read-the-last-move',
    )
    expect(screen.getAllByText('Planned')).toHaveLength(1)
    expect(screen.getByText(/independent Chess Tweek course/)).toBeInTheDocument()
  })

  it('plays the whole line as a timed puzzle attempt with a written hint', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push(`${init?.method ?? 'GET'} ${url} ${init?.body ?? ''}`.trim())
      if (url.endsWith('/progress')) return response(progress())
      if (url.endsWith('/lessons/read-the-last-move/start')) return response(progress('in_progress'))
      if (url.endsWith('/exercises/answer-the-check/attempts')) return response(attempt(), 201)
      if (url.endsWith('/attempts/5/hint')) return response({ level: 1, text: 'Use the king.' })
      if (url.endsWith('/attempts/5/moves')) {
        const move = JSON.parse(String(init?.body)).move
        if (move === 'e1d1') {
          return response({ outcome: 'wrong', opponentReply: null, wrongMoves: 1, feedback: 'Look at the checking rook.', explanation: null, completion: null })
        }
        if (move === 'e1e2') {
          return response({ outcome: 'correct', opponentReply: 'e8d7', wrongMoves: 1, feedback: null, explanation: null, completion: null })
        }
        if (move === 'e2d3') {
          return response({
            outcome: 'solved',
            opponentReply: null,
            wrongMoves: 1,
            feedback: 'Correct. Checks come first.',
            explanation: 'Kxe2 removes the checking rook.',
            completion: completion({ hintUsed: true }),
          })
        }
      }
      if (url === '/api/training/courses/grandmaster-thinking-method') return response(course())
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(<TrainingCourse connectionState="connected" lessonKey="read-the-last-move" onSessionFailure={vi.fn()} />)

    expect(await screen.findByRole('heading', { name: 'Read the last move' })).toBeInTheDocument()
    // The timer starts only when solving starts, so the board waits for that choice.
    expect(screen.getByRole('button', { name: 'e1, white king' })).toBeDisabled()
    expect(screen.getByText(/also counts as an attempt on its Lichess puzzle/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Start solving' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'e1, white king' })).toBeEnabled())
    expect(screen.getByText(/2 moves to find/)).toBeInTheDocument()
    expect(screen.getByText(/places this puzzle in Hard to Solve/)).toBeInTheDocument()

    play('e1', 'd1')
    expect(await screen.findByText('Look at the checking rook.')).toBeInTheDocument()
    expect(screen.getByText(/Wrong Moves 1/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e1, white king' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Hint: thinking step' }))
    expect(await screen.findByText('Use the king.')).toBeInTheDocument()
    expect(requests).toContain('POST /api/training/courses/grandmaster-thinking-method/exercises/answer-the-check/attempts/5/hint {"level":1}')

    play('e1', 'e2')
    expect(await screen.findByRole('button', { name: 'd7, black king' })).toBeInTheDocument()
    expect(screen.getByText(/Your opponent replied/)).toBeInTheDocument()

    play('e2', 'd3')
    expect(await screen.findByText('Line complete')).toBeInTheDocument()
    expect(screen.getByText('Kxe2 removes the checking rook.')).toBeInTheDocument()
    expect(screen.getByText('Puzzle training: placed in Hard to Solve. Solve Time 7.4 s of 11 s.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Solve again' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'd3, white king' })).toBeInTheDocument()
    await waitFor(() => expect(requests.filter((request) => request.endsWith('/progress')).length).toBeGreaterThan(1))
  })

  it('shows the solution as a hint and says Solve Time is not counted', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/progress')) return response(progress())
      if (url.endsWith('/lessons/read-the-last-move/start')) return response(progress('in_progress'))
      if (url.endsWith('/exercises/answer-the-check/attempts')) return response(attempt('retiring'), 201)
      if (url.endsWith('/attempts/5/reveal')) {
        return response({
          remainingMoves: ['e1e2', 'e8d7', 'e2d3'],
          explanation: 'Kxe2 removes the checking rook.',
          completion: completion({ previousList: 'retiring', hintUsed: true }),
        })
      }
      if (url === '/api/training/courses/grandmaster-thinking-method') return response(course())
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<TrainingCourse connectionState="connected" lessonKey="read-the-last-move" onSessionFailure={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Start solving' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Show solution' }))
    expect(await screen.findByText('Solution shown')).toBeInTheDocument()
    expect(screen.getByText('Puzzle training: moved from Retiring to Hard to Solve. Solve Time is not counted.'))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'd3, white king' })).toBeInTheDocument()
  })

  it('reviews a resolved answer without an attempt and can solve it again', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push(`${init?.method ?? 'GET'} ${url}`)
      if (url.endsWith('/progress')) return response(progress('in_progress', 'solved'))
      if (url.endsWith('/lessons/read-the-last-move/start')) return response(progress('in_progress', 'solved'))
      if (url.endsWith('/exercises/answer-the-check/answer')) {
        return response({ solutionLine: ['e1e2', 'e8d7', 'e2d3'], explanation: 'Kxe2 removes the checking rook.' })
      }
      if (url.endsWith('/exercises/answer-the-check/attempts')) return response(attempt('retiring'), 201)
      if (url === '/api/training/courses/grandmaster-thinking-method') return response(course())
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<TrainingCourse connectionState="connected" lessonKey="read-the-last-move" onSessionFailure={vi.fn()} />)

    expect(await screen.findByText('Solved')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Review answer' }))
    expect(await screen.findByText('Answer review')).toBeInTheDocument()
    expect(screen.getByText('Reviewing the answer does not change your Training Lists.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'd3, white king' })).toBeInTheDocument()
    expect(requests.some((request) => request.startsWith('POST') && request.endsWith('/attempts'))).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: 'Solve again' }))
    expect(await screen.findByRole('button', { name: 'Show solution' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e1, white king' })).toBeEnabled()
    expect(requests).toContain(
      'POST /api/training/courses/grandmaster-thinking-method/exercises/answer-the-check/attempts',
    )
  })

  it('explains an attempt that ended elsewhere and offers to start again', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString()
      if (url.endsWith('/progress')) return response(progress())
      if (url.endsWith('/lessons/read-the-last-move/start')) return response(progress('in_progress'))
      if (url.endsWith('/exercises/answer-the-check/attempts')) return response(attempt(), 201)
      if (url.endsWith('/attempts/5/hint')) return response({ error: 'attempt_finished' }, 409)
      if (url === '/api/training/courses/grandmaster-thinking-method') return response(course())
      throw new Error(`Unexpected request: ${url}`)
    }))
    render(<TrainingCourse connectionState="connected" lessonKey="read-the-last-move" onSessionFailure={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Start solving' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Hint: thinking step' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This attempt has ended')
    expect(screen.getByRole('button', { name: 'Start solving' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'e1, white king' })).toBeDisabled()
  })

  it('shows one exercise board and moves through the lesson in order', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(course())))

    render(
      <TrainingCourse
        connectionState="disconnected"
        lessonKey="read-the-last-move"
        onSessionFailure={vi.fn()}
      />,
    )

    expect(await screen.findByRole('group', { name: 'Position 1: Remove the checking piece.' }))
      .toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Position 2: Find the forcing reply.' }))
      .not.toBeInTheDocument()
    expect(screen.getByText('Position 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous position' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Next position' }))

    expect(screen.getByRole('group', { name: 'Position 2: Find the forcing reply.' }))
      .toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Position 1: Remove the checking piece.' }))
      .not.toBeInTheDocument()
    expect(screen.getByText('Position 2 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous position' })).toBeEnabled()
  })

  it('opens another lesson at its first position', async () => {
    const twoLessons = course()
    const first = twoLessons.modules[0].lessons[0]
    twoLessons.modules[0].lessons.push({
      ...first,
      key: 'generate-candidates',
      title: 'Generate candidates',
      exercises: first.exercises.map((exercise) => ({
        ...exercise,
        key: `${exercise.key}-candidates`,
        prompt: `${exercise.prompt} Candidates.`,
      })),
    })
    vi.stubGlobal('fetch', vi.fn(async () => response(twoLessons)))
    const onSessionFailure = vi.fn()
    const { rerender } = render(
      <TrainingCourse
        connectionState="disconnected"
        lessonKey="read-the-last-move"
        onSessionFailure={onSessionFailure}
      />,
    )
    await screen.findByRole('group', { name: 'Position 1: Remove the checking piece.' })
    fireEvent.click(screen.getByRole('button', { name: 'Next position' }))
    expect(screen.getByText('Position 2 of 3')).toBeInTheDocument()

    rerender(
      <TrainingCourse
        connectionState="disconnected"
        lessonKey="generate-candidates"
        onSessionFailure={onSessionFailure}
      />,
    )

    expect(await screen.findByRole('group', {
      name: 'Position 1: Remove the checking piece. Candidates.',
    })).toBeInTheDocument()
    expect(screen.getByText('Position 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous position' })).toBeDisabled()
  })
})

function response(body: unknown, status = 200): Response {
  return { ok: status < 400, status, json: async () => body } as Response
}

function play(from: string, to: string) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${from}, white`) }))
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${to},`) }))
}

function attempt(currentList: string | null = null) {
  return {
    attemptId: 5,
    puzzleId: 42,
    externalId: 'lDWH9',
    positionFen: '4k3/8/8/8/8/8/4r3/4K3 w - - 0 1',
    setupMove: 'e3e2',
    rating: 1600,
    ownerMoves: 2,
    fastSolveLimitMillis: 11_000,
    currentList,
  }
}

function completion(overrides: Record<string, unknown> = {}) {
  return {
    previousList: null,
    list: 'hard_to_solve',
    successes: 0,
    strikes: 0,
    wrongMoves: 1,
    hintUsed: false,
    elapsedMillis: 7_400,
    fastSolveLimitMillis: 11_000,
    gameUrl: 'https://lichess.org/game/OHeXKNku/black#52',
    themes: ['intermezzo'],
    ...overrides,
  }
}

function course() {
  return {
    key: 'grandmaster-thinking-method',
    version: 1,
    title: 'Grandmaster Thinking Method',
    subtitle: 'A repeatable way to choose moves with purpose',
    attribution: 'An independent Chess Tweek course inspired by public teaching principles.',
    modules: [
      {
        key: 'repeatable-move-routine',
        title: 'Repeatable Move Routine',
        description: 'Build one calm sequence.',
        status: 'available',
        lessons: [
          {
            key: 'read-the-last-move',
            title: 'Read the last move',
            summary: 'Identify what changed and what the opponent threatens.',
            estimatedMinutes: 10,
            explanation: ['Read the position before choosing your move.'],
            checklist: ['Am I in check?'],
            exercises: [
              {
                key: 'answer-the-check',
                fen: '4k3/8/8/8/8/8/4r3/4K3 w - - 0 1',
                orientation: 'white',
                difficultyTier: 'foundation',
                sourceRating: 1600,
                prompt: 'Remove the checking piece.',
              },
              {
                key: 'forcing-reply',
                fen: '4k3/8/8/8/8/8/4r3/4K3 w - - 0 1',
                orientation: 'white',
                difficultyTier: 'development',
                sourceRating: 1850,
                prompt: 'Find the forcing reply.',
              },
              {
                key: 'safety-check',
                fen: '4k3/8/8/8/8/8/4r3/4K3 w - - 0 1',
                orientation: 'white',
                difficultyTier: 'stretch',
                sourceRating: 2050,
                prompt: 'Run the final safety check.',
              },
            ],
          },
        ],
      },
      {
        key: 'opening-readiness',
        title: 'Opening Readiness',
        description: 'Finish the opening jobs.',
        status: 'planned',
        lessons: [],
      },
    ],
  }
}

function progress(
  state: 'not_started' | 'in_progress' = 'not_started',
  exerciseState: 'not_started' | 'in_progress' | 'solved' | 'revealed' = 'not_started',
) {
  return {
    courseKey: 'grandmaster-thinking-method',
    contentVersion: 1,
    completedLessons: 0,
    totalLessons: 1,
    recommendedLessonKey: 'read-the-last-move',
    lessons: {
      'read-the-last-move': {
        state,
        exercises: {
          'answer-the-check': { state: exerciseState, attempts: 0, hintLevel: 0 },
          'forcing-reply': { state: 'not_started', attempts: 0, hintLevel: 0 },
          'safety-check': { state: 'not_started', attempts: 0, hintLevel: 0 },
        },
      },
    },
  }
}
