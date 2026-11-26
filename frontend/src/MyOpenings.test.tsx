import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MyOpenings } from './MyOpenings'
import type { PuzzleCategory } from './puzzles'

const category = (kind: PuzzleCategory['kind'], key: string, name: string, parentKey: string | null = null): PuzzleCategory =>
  ({ kind, key, name, parentKey, group: 'Openings', puzzles: 100 })
const categories = [
  category('opening-family', 'London_System', 'London System'),
  category('opening-family', 'Caro-Kann_Defense', 'Caro-Kann Defense'),
  category('opening-family', 'Sicilian_Defense', 'Sicilian Defense'),
  category('opening-variation', 'London_System_A', 'London System Alpha', 'London_System'),
  category('opening-variation', 'London_System_B', 'London System Beta', 'London_System'),
]
beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())

it('starts with London first, adds openings, favorites and reorders variations and persists per account', () => {
  const view = render(<MyOpenings accountId="owner" categories={categories} />)
  expect(screen.getAllByRole('region').map((item) => item.getAttribute('aria-label'))).toEqual(['London System', 'Caro-Kann Defense'])
  const london = screen.getByRole('region', { name: 'London System' })
  expect(within(london).getByRole('button', { name: 'Favorite London System' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(within(london).getByRole('button', { name: 'London System' }))
  fireEvent.click(within(london).getByRole('button', { name: 'Favorite Beta' }))
  expect(within(london).getAllByRole('link').map((item) => item.textContent)).toEqual(['Practice London System', 'Beta', 'Alpha'])
  fireEvent.change(screen.getByLabelText('Add opening'), { target: { value: 'Sicilian_Defense' } })
  fireEvent.click(screen.getByRole('button', { name: 'Add opening' }))
  fireEvent.click(screen.getByRole('button', { name: 'Move Sicilian Defense up' }))
  expect(screen.getAllByRole('region').map((item) => item.getAttribute('aria-label'))).toEqual(['London System', 'Sicilian Defense', 'Caro-Kann Defense'])
  view.unmount()
  const other = render(<MyOpenings accountId="other" categories={categories} />)
  expect(screen.queryByRole('region', { name: 'Sicilian Defense' })).not.toBeInTheDocument()
  other.unmount()
  render(<MyOpenings accountId="owner" categories={categories} />)
  expect(screen.getAllByRole('region').map((item) => item.getAttribute('aria-label'))).toEqual(['London System', 'Sicilian Defense', 'Caro-Kann Defense'])
  fireEvent.click(screen.getByRole('button', { name: 'London System' }))
  expect(screen.getByRole('button', { name: 'Favorite Beta' })).toHaveAttribute('aria-pressed', 'true')
})

it('preserves choices when storage cannot be written', () => {
  render(<MyOpenings accountId="owner" categories={categories} />)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('unavailable') })
  fireEvent.click(screen.getByRole('button', { name: 'Remove London System' }))
  expect(screen.getByRole('alert')).toHaveTextContent('could not be saved')
  expect(screen.getByRole('region', { name: 'London System' })).toBeInTheDocument()
})

it('handles malformed stored preferences safely', () => {
  localStorage.setItem('chess-tweek:my-openings:owner', '{"openings":["bad key"]}')
  render(<MyOpenings accountId="owner" categories={categories} />)
  expect(screen.getByRole('alert')).toHaveTextContent('could not be read')
  expect(screen.getByRole('region', { name: 'London System' })).toBeInTheDocument()
})
