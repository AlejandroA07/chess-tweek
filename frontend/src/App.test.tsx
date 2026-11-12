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

  it('renders stored games as safe catalog cards for the connected account', async () => {
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
      if (url === '/api/games?offset=0&limit=50') {
        return {
          ok: true,
          json: async () => ({
            total: 2,
            offset: 0,
            limit: 50,
            games: [
              {
                id: 'game0001',
                playedAt: '2026-11-11T00:00:00Z',
                result: 'win',
                ownerColor: 'white',
                ownerRating: 1800,
                opponent: {
                  type: 'bot',
                  name: 'StudyBot',
                  rating: 1750,
                  title: 'BOT',
                  aiLevel: null,
                },
                speed: 'blitz',
                rated: true,
                variant: 'standard',
                timeControl: {
                  initialSeconds: 300,
                  incrementSeconds: 3,
                  daysPerTurn: null,
                  label: '5+3',
                },
                opening: { eco: 'C20', name: "King's Pawn Game" },
                analysis: { eligible: true, status: 'not_analyzed' },
              },
              {
                id: 'game0002',
                playedAt: '2026-11-10T00:00:00Z',
                result: 'loss',
                ownerColor: 'black',
                ownerRating: 1792,
                opponent: {
                  type: 'human',
                  name: 'VariantPlayerWithAnIntentionallyLongDisplayNameForOverflow',
                  rating: 1810,
                  title: null,
                  aiLevel: null,
                },
                speed: 'rapid',
                rated: false,
                variant: 'crazyhouse',
                timeControl: {
                  initialSeconds: 600,
                  incrementSeconds: 5,
                  daysPerTurn: null,
                  label: '10+5',
                },
                opening: null,
                analysis: { eligible: false, status: 'unsupported_variant' },
              },
            ],
          }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))

    expect(await screen.findByText('2 stored games')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Lichess games' })).toBeInTheDocument()
    const botCard = screen.getByRole('article', { name: 'Win against StudyBot' })
    expect(within(botCard).getByText('BOT StudyBot')).toBeInTheDocument()
    expect(within(botCard).getByText("C20 · King's Pawn Game")).toBeInTheDocument()
    expect(within(botCard).getByText('5+3 Blitz')).toBeInTheDocument()
    expect(within(botCard).getByText('My moves · Not analyzed')).toBeInTheDocument()
    expect(within(botCard).getByText('Opponent moves · Not analyzed')).toBeInTheDocument()
    expect(within(botCard).getByText('Bot')).toBeInTheDocument()
    const variantCard = screen.getByRole('article', {
      name: 'Loss against VariantPlayerWithAnIntentionallyLongDisplayNameForOverflow',
    })
    expect(within(variantCard).getByText('VariantPlayerWithAnIntentionallyLongDisplayNameForOverflow'))
      .toBeInTheDocument()
    expect(screen.getByText('Catalog only — analysis unavailable')).toBeInTheDocument()

    const eligibleSelection = within(botCard).getByRole('checkbox', {
      name: 'Select game against StudyBot',
    })
    expect(eligibleSelection).toBeEnabled()
    expect(within(variantCard).getByRole('checkbox', {
      name: 'VariantPlayerWithAnIntentionallyLongDisplayNameForOverflow cannot be selected because analysis is unavailable',
    })).toBeDisabled()

    fireEvent.click(eligibleSelection)
    expect(screen.getByText('1 game selected')).toBeInTheDocument()
    expect(botCard).toHaveAttribute('data-selected', 'true')

    fireEvent.click(screen.getByRole('button', { name: 'Clear Selection' }))
    expect(screen.queryByText('1 game selected')).not.toBeInTheDocument()
    expect(eligibleSelection).not.toBeChecked()
  })

  it('selects every eligible game in the filtered catalog, including unloaded pages', async () => {
    const eligible = catalogCard('game0001', 'EligiblePlayer', '2026-11-11T00:00:00Z')
    const unsupported = {
      ...catalogCard('game0002', 'VariantPlayer', '2026-11-10T00:00:00Z'),
      variant: 'crazyhouse',
      analysis: { eligible: false, status: 'unsupported_variant' as const },
    }
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return { ok: true, json: async () => connectedSession() } as Response
      }
      if (url === '/api/games?offset=0&limit=50') {
        return {
          ok: true,
          json: async () => ({ total: 75, offset: 0, limit: 50, games: [eligible, unsupported] }),
        } as Response
      }
      if (url === '/api/games?offset=0&limit=1&analysis=eligible') {
        return {
          ok: true,
          json: async () => ({ total: 61, offset: 0, limit: 1, games: [eligible] }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))

    expect(await screen.findByText('2 of 75 stored games loaded')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Select All Filtered' }))

    expect(await screen.findByText('61 games selected')).toBeInTheDocument()
    const eligibleSelection = screen.getByRole('checkbox', {
      name: 'Select game against EligiblePlayer',
    })
    expect(eligibleSelection).toBeChecked()
    expect(screen.getByRole('checkbox', {
      name: 'VariantPlayer cannot be selected because analysis is unavailable',
    })).toBeDisabled()
    expect(screen.getByText('All analysis-eligible games matching the current filters.'))
      .toBeInTheDocument()

    fireEvent.click(eligibleSelection)
    expect(screen.getByText('60 games selected')).toBeInTheDocument()
    expect(eligibleSelection).not.toBeChecked()

    fireEvent.click(eligibleSelection)
    expect(screen.getByText('61 games selected')).toBeInTheDocument()
    expect(eligibleSelection).toBeChecked()
  })

  it('explains when the filtered catalog contains no eligible games', async () => {
    const unsupported = {
      ...catalogCard('game0002', 'VariantPlayer', '2026-11-10T00:00:00Z'),
      variant: 'crazyhouse',
      analysis: { eligible: false, status: 'unsupported_variant' as const },
    }
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return { ok: true, json: async () => connectedSession() } as Response
      }
      if (url === '/api/games?offset=0&limit=50') {
        return {
          ok: true,
          json: async () => ({ total: 1, offset: 0, limit: 50, games: [unsupported] }),
        } as Response
      }
      if (url === '/api/games?offset=0&limit=1&analysis=eligible') {
        return {
          ok: true,
          json: async () => ({ total: 0, offset: 0, limit: 1, games: [] }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))
    expect(await screen.findByText('1 stored game')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Select All Filtered' }))

    expect(await screen.findByText('No analysis-eligible games match the current filters.'))
      .toBeInTheDocument()
    expect(screen.queryByText(/games selected/)).not.toBeInTheDocument()
  })

  it('combines the full catalog filters and changes its sort order', async () => {
    const catalogUrls: string[] = []
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return { ok: true, json: async () => connectedSession() } as Response
      }
      if (url.startsWith('/api/games?')) {
        catalogUrls.push(url)
        const filtered = url.includes('result=loss')
          && url.includes('color=black')
          && url.includes('opening=C20')
          && url.includes('opponent=human')
          && url.includes('speed=blitz')
          && url.includes('from=2026-11-01')
          && url.includes('to=2026-11-10')
          && url.includes('variant=standard')
          && url.includes('analysis=eligible')
          && url.includes('sort=oldest')
        const loss = {
          ...catalogCard('game0002', 'FilteredPlayer', '2026-11-10T00:00:00Z'),
          result: 'loss' as const,
          ownerColor: 'black' as const,
        }
        return {
          ok: true,
          json: async () => filtered
            ? {
                total: 1,
                offset: 0,
                limit: url.includes('limit=1') ? 1 : 50,
                games: [loss],
              }
            : {
                total: 2,
                offset: 0,
                limit: 50,
                games: [
                  catalogCard('game0001', 'RecentPlayer', '2026-11-11T00:00:00Z'),
                  loss,
                ],
              },
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))
    expect(await screen.findByText('2 stored games')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('combobox', { name: 'Result' }), {
      target: { value: 'loss' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Color' }), {
      target: { value: 'black' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Opening' }), {
      target: { value: 'C20' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Opponent type' }), {
      target: { value: 'human' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Speed' }), {
      target: { value: 'blitz' },
    })
    fireEvent.change(screen.getByLabelText('Games from'), {
      target: { value: '2026-11-01' },
    })
    fireEvent.change(screen.getByLabelText('Games to'), {
      target: { value: '2026-11-10' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Variant' }), {
      target: { value: 'standard' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Analysis status' }), {
      target: { value: 'eligible' },
    })
    fireEvent.change(screen.getByRole('combobox', { name: 'Sort games' }), {
      target: { value: 'oldest' },
    })

    expect(await screen.findByText('1 matching game')).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Loss against FilteredPlayer' })).toBeInTheDocument()
    expect(catalogUrls).toContain(
      '/api/games?offset=0&limit=50&result=loss&color=black&opening=C20&opponent=human'
        + '&speed=blitz&from=2026-11-01&to=2026-11-10&variant=standard'
        + '&analysis=eligible&sort=oldest',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Select All Filtered' }))
    expect(await screen.findByText('1 game selected')).toBeInTheDocument()
    expect(catalogUrls).toContain(
      '/api/games?offset=0&limit=1&result=loss&color=black&opening=C20&opponent=human'
        + '&speed=blitz&from=2026-11-01&to=2026-11-10&variant=standard'
        + '&analysis=eligible&sort=oldest',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByText('2 stored games')).toBeInTheDocument()
    expect(screen.queryByText('1 game selected')).not.toBeInTheDocument()
  })

  it('gets an older Lichess batch manually and refreshes the stored catalog', async () => {
    let catalogRequests = 0
    let retrievalRequest: RequestInit | undefined
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return { ok: true, json: async () => connectedSession() } as Response
      }
      if (url === '/api/games?offset=0&limit=50') {
        catalogRequests += 1
        return {
          ok: true,
          json: async () => catalogRequests === 1
            ? { total: 0, offset: 0, limit: 50, games: [] }
            : {
                total: 1,
                offset: 0,
                limit: 50,
                games: [catalogCard('game0001', 'OlderPlayer', '2026-11-01T00:00:00Z')],
              },
        } as Response
      }
      if (url === '/api/lichess/games/retrievals') {
        retrievalRequest = init
        return {
          ok: true,
          json: async () => ({ requested: 5, received: 1, rejected: 0, before: '2026-11-02' }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))

    expect(await screen.findByRole('heading', { name: 'No games stored on this Mac' }))
      .toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Get Lichess Games' }))
    const retrievalPanel = screen.getByRole('complementary', { name: 'Get Lichess Games' })
    expect(retrievalPanel).toHaveTextContent('Connected as ChessOwner')

    fireEvent.change(screen.getByRole('spinbutton', { name: 'Number of games' }), {
      target: { value: '5' },
    })
    fireEvent.change(screen.getByLabelText('Games before'), {
      target: { value: '2026-11-02' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Get Games' }))

    expect(await screen.findByText('Received 1 game from Lichess.')).toBeInTheDocument()
    expect(await screen.findByRole('article', { name: 'Win against OlderPlayer' })).toBeInTheDocument()
    expect(retrievalRequest).toMatchObject({
      method: 'POST',
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ count: 5, before: '2026-11-02' }),
    })
    expect(catalogRequests).toBe(2)
  })

  it('shows the safe retry time when Lichess rate-limits manual retrieval', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return { ok: true, json: async () => connectedSession() } as Response
      }
      if (url === '/api/games?offset=0&limit=50') {
        return {
          ok: true,
          json: async () => ({ total: 0, offset: 0, limit: 50, games: [] }),
        } as Response
      }
      if (url === '/api/lichess/games/retrievals') {
        return {
          ok: false,
          status: 429,
          headers: new Headers({ 'Retry-After': '73' }),
          json: async () => ({ error: 'lichess_rate_limited' }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))
    expect(await screen.findByRole('heading', { name: 'No games stored on this Mac' }))
      .toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Get Lichess Games' }))
    fireEvent.click(screen.getByRole('button', { name: 'Get Games' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Lichess asked us to wait 73 seconds before trying again.',
    )
    expect(screen.getByRole('button', { name: 'Get Games' })).toBeEnabled()
  })

  it('loads older catalog pages without discarding visible games', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return { ok: true, json: async () => connectedSession() } as Response
      }
      if (url === '/api/games?offset=0&limit=50') {
        return {
          ok: true,
          json: async () => ({
            total: 2,
            offset: 0,
            limit: 50,
            games: [catalogCard('game0001', 'RecentPlayer', '2026-11-11T00:00:00Z')],
          }),
        } as Response
      }
      if (url === '/api/games?offset=1&limit=50') {
        return {
          ok: true,
          json: async () => ({
            total: 2,
            offset: 1,
            limit: 50,
            games: [catalogCard('game0002', 'OlderPlayer', '2026-11-10T00:00:00Z')],
          }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))

    expect(await screen.findByText('1 of 2 stored games loaded')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Load older games' }))

    expect(await screen.findByRole('article', { name: 'Win against OlderPlayer' })).toBeInTheDocument()
    const loadedCount = screen.getByText('2 stored games')
    expect(loadedCount).toBeInTheDocument()
    expect(loadedCount).toHaveFocus()
    expect(screen.queryByRole('button', { name: 'Load older games' })).not.toBeInTheDocument()
  })

  it('shows a safe catalog failure and retries without losing the app shell', async () => {
    let catalogRequests = 0
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return { ok: true, json: async () => connectedSession() } as Response
      }
      if (url === '/api/games?offset=0&limit=50') {
        catalogRequests += 1
        if (catalogRequests === 1) return { ok: false, status: 503 } as Response
        return {
          ok: true,
          json: async () => ({ total: 0, offset: 0, limit: 50, games: [] }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))

    expect(await screen.findByRole('heading', { name: 'Stored games could not be loaded' }))
      .toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Games' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    const emptyHeading = await screen.findByRole('heading', { name: 'No games stored on this Mac' })
    expect(emptyHeading).toBeInTheDocument()
    expect(emptyHeading.closest('section')).toHaveFocus()
    expect(catalogRequests).toBe(2)
  })

  it('updates the shared session when the catalog discovers revoked access', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString()

      if (url === '/api/health') {
        return { ok: true, json: async () => ({ status: 'ok' }) } as Response
      }
      if (url === '/api/lichess/session') {
        return { ok: true, json: async () => connectedSession() } as Response
      }
      if (url === '/api/games?offset=0&limit=50') {
        return {
          ok: false,
          status: 401,
          json: async () => ({ error: 'not_connected' }),
        } as Response
      }

      throw new Error(`Unexpected request: ${url}`)
    })

    render(<App />)
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))

    expect(await screen.findByRole('heading', { name: 'Connect Lichess to view stored games' }))
      .toBeInTheDocument()
    expect(screen.getByText('Not connected')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Open Dashboard' }))
    expect(await screen.findByRole('heading', { name: 'Connect your Lichess account' }))
      .toBeInTheDocument()
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
      ['Games', 'Connect Lichess to view stored games'],
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
    fireEvent.click(screen.getByRole('link', { name: 'Games' }))
    expect(await screen.findByRole('heading', { name: 'Lichess connection is unavailable' }))
      .toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Open Dashboard' }))
    expect(await screen.findByRole('heading', { name: 'Lichess is temporarily unavailable' }))
      .toBeInTheDocument()
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

function connectedSession() {
  return {
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
  }
}

function catalogCard(id: string, opponentName: string, playedAt: string) {
  return {
    id,
    playedAt,
    result: 'win',
    ownerColor: 'white',
    ownerRating: 1800,
    opponent: {
      type: 'human',
      name: opponentName,
      rating: 1750,
      title: null,
      aiLevel: null,
    },
    speed: 'blitz',
    rated: true,
    variant: 'standard',
    timeControl: {
      initialSeconds: 300,
      incrementSeconds: 3,
      daysPerTurn: null,
      label: '5+3',
    },
    opening: { eco: 'C20', name: "King's Pawn Game" },
    analysis: { eligible: true, status: 'not_analyzed' },
  }
}
