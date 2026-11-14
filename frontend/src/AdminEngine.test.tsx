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

  it('runs the benchmark and shows the stored analysis profile', async () => {
    const fetchMock = stubFetch(
      ok({ state: 'ready', path: '/opt/homebrew/bin/stockfish', engineName: 'Stockfish 19', profile: null }),
      ok({
        state: 'ready',
        path: '/opt/homebrew/bin/stockfish',
        engineName: 'Stockfish 19',
        profile: profile(),
      }),
    )

    render(<AdminEngine onSessionFailure={vi.fn()} />)

    expect(await screen.findByText(/No benchmark yet/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Run Benchmark' }))
    expect(screen.getByRole('button', { name: 'Running benchmark…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Test Engine' })).toBeDisabled()

    expect(await screen.findByText('1.05 million nodes/s')).toBeInTheDocument()
    expect(screen.getByText('790,000 nodes · about 0.75 s')).toBeInTheDocument()
    expect(screen.getByText('3,150,000 nodes · about 3 s')).toBeInTheDocument()
    expect(screen.getByText(/2 threads · 256 MB hash · 3 best lines/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run Benchmark Again' })).toBeEnabled()
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/engine/benchmarks',
      expect.objectContaining({ method: 'POST', credentials: 'same-origin' }),
    )
  })

  it('warns when Stockfish changed after the stored benchmark', async () => {
    stubFetch(ok({
      state: 'ready',
      path: '/opt/homebrew/bin/stockfish',
      engineName: 'Stockfish 20',
      profile: profile(),
    }))

    render(<AdminEngine onSessionFailure={vi.fn()} />)

    expect(await screen.findByText(/Stockfish changed to Stockfish 20 after this benchmark/))
      .toBeInTheDocument()
  })

  it('explains a failed benchmark and keeps the previous profile', async () => {
    stubFetch(
      ok({ state: 'ready', path: '/opt/homebrew/bin/stockfish', engineName: 'Stockfish 19', profile: profile() }),
      { ok: false, status: 502, json: async () => ({ error: 'benchmark_failed' }) },
    )

    render(<AdminEngine onSessionFailure={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Run Benchmark Again' }))

    expect(await screen.findByText('The benchmark did not complete, so no profile was saved. Try again.'))
      .toBeInTheDocument()
    expect(screen.getByText('1.05 million nodes/s')).toBeInTheDocument()
  })

  it('rejects a profile whose deep budget is below its quick budget', async () => {
    stubFetch(ok({
      state: 'ready',
      path: '/opt/homebrew/bin/stockfish',
      engineName: 'Stockfish 19',
      profile: { ...profile(), deepNodes: 100_000 },
    }))

    render(<AdminEngine onSessionFailure={vi.fn()} />)

    expect(await screen.findByRole('heading', { name: 'Engine status could not be loaded' }))
      .toBeInTheDocument()
  })

  it('reports a revoked session to the shell', async () => {
    const onSessionFailure = vi.fn()
    stubFetch({ ok: false, status: 401, json: async () => ({ error: 'not_connected' }) })

    render(<AdminEngine onSessionFailure={onSessionFailure} />)

    await vi.waitFor(() => expect(onSessionFailure).toHaveBeenCalledWith('not_connected'))
  })
})

function profile() {
  return {
    id: 7,
    engineName: 'Stockfish 19',
    threads: 2,
    hashMb: 256,
    multiPv: 3,
    benchmarkPositions: 6,
    benchmarkNodesPerPosition: 1_000_000,
    nodesPerSecond: 1_050_000,
    quickTargetMs: 750,
    deepTargetMs: 3_000,
    quickNodes: 790_000,
    deepNodes: 3_150_000,
    createdAt: '2026-11-14T09:00:00Z',
  }
}
