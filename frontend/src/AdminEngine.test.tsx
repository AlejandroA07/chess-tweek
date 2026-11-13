import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { AdminEngine } from './AdminEngine'

function ok(body: unknown): Partial<Response> {
  return { ok: true, status: 200, json: async () => body }
}

function stubFetch(...responses: Array<Partial<Response>>) {
  const fetchMock = vi.fn()
  for (const response of responses) fetchMock.mockResolvedValueOnce(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('AdminEngine', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows a found engine and confirms it with Test Engine', async () => {
    const fetchMock = stubFetch(
      ok({ state: 'not_checked', path: '/opt/homebrew/bin/stockfish', engineName: null }),
      ok({ state: 'ready', path: '/opt/homebrew/bin/stockfish', engineName: 'Stockfish 17.1' }),
    )

    render(<AdminEngine onSessionFailure={vi.fn()} />)

    expect(await screen.findByText('Found, not tested')).toBeInTheDocument()
    expect(screen.getByText('/opt/homebrew/bin/stockfish')).toBeInTheDocument()
    expect(screen.getByText('Unknown until tested')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Test Engine' }))

    expect(await screen.findByText('Ready')).toBeInTheDocument()
    expect(screen.getByText('Stockfish 17.1')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/engine/health-checks',
      expect.objectContaining({ method: 'POST', credentials: 'same-origin' }),
    )
  })

  it('explains how to install a missing engine', async () => {
    stubFetch(ok({ state: 'not_installed', path: '/opt/homebrew/bin/stockfish', engineName: null }))

    render(<AdminEngine onSessionFailure={vi.fn()} />)

    expect(await screen.findByText('Not installed')).toBeInTheDocument()
    expect(screen.getByText('brew install stockfish')).toBeInTheDocument()
  })

  it('reports a busy engine without losing the current status', async () => {
    stubFetch(
      ok({ state: 'ready', path: '/opt/homebrew/bin/stockfish', engineName: 'Stockfish 17.1' }),
      { ok: false, status: 409, json: async () => ({ error: 'engine_busy' }) },
    )

    render(<AdminEngine onSessionFailure={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Test Engine' }))

    expect(await screen.findByText('Stockfish is busy with another task. Try again when it finishes.'))
      .toBeInTheDocument()
    expect(screen.getByText('Ready')).toBeInTheDocument()
  })

  it('rejects an inconsistent status instead of rendering it', async () => {
    stubFetch(
      ok({ state: 'ready', path: '/opt/homebrew/bin/stockfish', engineName: null }),
      ok({ state: 'not_installed', path: null, engineName: null }),
    )

    render(<AdminEngine onSessionFailure={vi.fn()} />)

    expect(await screen.findByRole('heading', { name: 'Engine status could not be loaded' }))
      .toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('Not installed')).toBeInTheDocument()
  })

  it('reports a revoked session to the shell', async () => {
    const onSessionFailure = vi.fn()
    stubFetch({ ok: false, status: 401, json: async () => ({ error: 'not_connected' }) })

    render(<AdminEngine onSessionFailure={onSessionFailure} />)

    await vi.waitFor(() => expect(onSessionFailure).toHaveBeenCalledWith('not_connected'))
  })
})
