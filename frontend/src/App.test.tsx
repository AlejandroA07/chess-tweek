import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from './App'

describe('App', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/')
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = input.toString()

        if (url === '/api/health') {
          return { ok: true, json: async () => ({ status: 'ok' }) }
        }

        if (url === '/api/lichess/session') {
          return { ok: true, json: async () => ({ connected: false, account: null }) }
        }

        throw new Error(`Unexpected request: ${url}`)
      }),
    )
  })

  afterEach(() => vi.unstubAllGlobals())

  it('shows the approved shell with Dashboard selected', async () => {
    render(<App />)

    const navigation = screen.getByRole('navigation', { name: 'Primary' })
    expect(within(navigation).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Dashboard',
      'Games',
      'Reports',
      'Puzzles',
      'Admin',
    ])
    expect(within(navigation).getByRole('link', { name: 'Dashboard' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(await screen.findByText('Backend ready')).toBeInTheDocument()
  })

  it('updates the route and selected navigation item', async () => {
    render(<App />)

    fireEvent.click(screen.getByRole('link', { name: 'Games' }))

    await waitFor(() => expect(window.location.hash).toBe('#/games'))
    expect(screen.getByRole('link', { name: 'Games' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('heading', { name: 'Games' })).toBeInTheDocument()
  })

  it('falls back to Dashboard for an unrecognized route', () => {
    window.history.replaceState(null, '', '/#/not-an-approved-route')

    render(<App />)

    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(screen.queryByText('not-an-approved-route')).not.toBeInTheDocument()
  })

  it('shows an intentional empty state for every approved destination', async () => {
    render(<App />)

    const destinations = [
      ['Dashboard', 'No chess activity yet'],
      ['Games', 'No games stored on this Mac'],
      ['Reports', 'No saved reports yet'],
      ['Puzzles', 'No puzzle training yet'],
      ['Admin', 'No local controls available yet'],
    ] as const

    for (const [destination, emptyStateHeading] of destinations) {
      fireEvent.click(screen.getByRole('link', { name: destination }))

      await waitFor(() =>
        expect(screen.getByRole('heading', { name: emptyStateHeading })).toBeInTheDocument(),
      )
    }
  })

  it('toggles compact navigation and closes it after choosing a destination', async () => {
    render(<App />)

    const navigationToggle = screen.getByRole('button', { name: 'Open navigation' })
    expect(navigationToggle).toHaveAttribute('aria-expanded', 'false')
    expect(navigationToggle).toHaveAttribute('aria-controls', 'primary-navigation')

    fireEvent.click(navigationToggle)
    expect(navigationToggle).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(screen.getByRole('link', { name: 'Games' }))

    expect(await screen.findByRole('heading', { name: 'Games' })).toBeInTheDocument()
    expect(navigationToggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('offers a fixed same-origin authorization form when Lichess is disconnected', async () => {
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Connect your Lichess account' })).toBeInTheDocument()
    expect(screen.getByText('Not connected')).toBeInTheDocument()

    const connectButton = screen.getByRole('button', { name: 'Connect Lichess' })
    const form = connectButton.closest('form')

    expect(form).toHaveAttribute('method', 'post')
    expect(form).toHaveAttribute('action', '/api/lichess/authorize')
  })

  it('shows the connected Lichess account and ratings without exposing a token', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }

      if (url === '/api/lichess/session') {
        return {
          ok: true,
          json: async () => ({
            connected: true,
            account: {
              id: 'owner-id',
              username: 'ChessOwner',
              ratings: {
                bullet: 1801,
                blitz: 1765,
                rapid: 1902,
                classical: null,
                correspondence: 2020,
              },
            },
          }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'ChessOwner' })).toBeInTheDocument()
    expect(screen.getByText('1,801')).toBeInTheDocument()
    expect(screen.getByText('1,765')).toBeInTheDocument()
    expect(screen.getByText('1,902')).toBeInTheDocument()
    expect(screen.getByText('Not rated')).toBeInTheDocument()
    expect(screen.getByText('2,020')).toBeInTheDocument()
    expect(screen.queryByText(/token/i)).not.toBeInTheDocument()
  })

  it('logs out through the protected endpoint and returns to the disconnected state', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return {
          ok: true,
          json: async () => ({
            connected: true,
            account: {
              id: 'owner-id',
              username: 'ChessOwner',
              ratings: {
                bullet: 1801,
                blitz: 1765,
                rapid: 1902,
                classical: null,
                correspondence: 2020,
              },
            },
          }),
        } as Response
      }
      if (url === '/api/lichess/logout') return { ok: true } as Response

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }))

    expect(await screen.findByRole('heading', { name: 'Connect your Lichess account' })).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(
      '/api/lichess/logout',
      expect.objectContaining({ method: 'POST', credentials: 'same-origin' }),
    )
  })

  it('shows an actionable upstream failure and can retry the session check', async () => {
    let sessionRequests = 0
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        sessionRequests += 1
        if (sessionRequests === 1) return { ok: false, status: 503 } as Response
        return {
          ok: true,
          status: 200,
          json: async () => ({ connected: false, account: null }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Lichess is temporarily unavailable' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('heading', { name: 'Connect your Lichess account' })).toBeInTheDocument()
    expect(sessionRequests).toBe(2)
  })

  it('turns a safe OAuth callback result into a visible message and cleans the URL', async () => {
    window.history.replaceState(null, '', '/?lichess=cancelled#/dashboard')

    render(<App />)

    expect(await screen.findByText('Lichess connection was cancelled. Nothing changed.')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toBe(''))
    expect(window.location.hash).toBe('#/dashboard')
  })

  it('rejects malformed account data instead of rendering it', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            connected: true,
            account: {
              id: 'owner-id',
              username: 'ChessOwner',
              ratings: { bullet: Number.NaN, blitz: 1, rapid: 1, classical: 1, correspondence: 1 },
            },
          }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Lichess is temporarily unavailable' })).toBeInTheDocument()
    expect(screen.queryByText('ChessOwner')).not.toBeInTheDocument()
  })
})
