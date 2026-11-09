import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { App } from './App'

describe('App', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ status: 'ok' }),
      }),
    )
  })

  afterEach(() => vi.unstubAllGlobals())

  it('shows the application and backend readiness', async () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Chess Tweek' })).toBeInTheDocument()
    expect(await screen.findByText('Backend ready')).toBeInTheDocument()
  })
})
