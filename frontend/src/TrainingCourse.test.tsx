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

  it('submits an interactive move and reveals only the validated answer', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()
      requests.push(`${init?.method ?? 'GET'} ${url}`)
      if (url.endsWith('/progress')) return response(progress())
      if (url.endsWith('/lessons/read-the-last-move/start')) return response(progress('in_progress'))
      if (url.endsWith('/exercises/answer-the-check/attempts')) {
        expect(init).toMatchObject({ method: 'POST', credentials: 'same-origin' })
        expect(init?.body).toBe('{"move":"e1e2"}')
        return response({
          outcome: 'correct',
          feedback: 'Correct. Checks come first.',
          solutionMove: 'e1e2',
          explanation: 'Kxe2 removes the checking rook.',
        })
      }
      if (url === '/api/training/courses/grandmaster-thinking-method') return response(course())
      throw new Error(`Unexpected request: ${url}`)
    }))

    render(
      <TrainingCourse
        connectionState="connected"
        lessonKey="read-the-last-move"
        onSessionFailure={vi.fn()}
      />,
    )

    expect(await screen.findByRole('heading', { name: 'Read the last move' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'e1, white king' }))
    fireEvent.click(screen.getByRole('button', { name: 'e2, black rook' }))

    expect(await screen.findByText('Reference move: e1e2')).toBeInTheDocument()
    expect(screen.getByText('Kxe2 removes the checking rook.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e1, empty' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e2, white king' })).toBeInTheDocument()
    expect(screen.queryByTestId('best-move-arrow')).not.toBeInTheDocument()
    await waitFor(() => expect(requests.filter((request) => request.endsWith('/progress')).length).toBeGreaterThan(1))
  })
})

function response(body: unknown): Response {
  return { ok: true, status: 200, json: async () => body } as Response
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

function progress(state: 'not_started' | 'in_progress' = 'not_started') {
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
          'answer-the-check': { state: 'not_started', attempts: 0, hintLevel: 0 },
        },
      },
    },
  }
}
