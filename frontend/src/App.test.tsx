import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from './App'

describe('App', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ status: 'ok' }),
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
})
