import { expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { DashboardRatingContext } from './DashboardRatingContext'

it('shows only the four current Lichess ratings and a link to Games', () => {
  render(<DashboardRatingContext currentRatings={{ rapid: 1902, blitz: 1765, bullet: 1801, classical: null }} />)

  const panel = screen.getByRole('region', { name: 'Lichess ratings' })
  expect(within(panel).getByRole('link', { name: 'Open Games' })).toHaveAttribute('href', '#/games')
  expect(within(panel).getAllByRole('article').map((card) => card.textContent)).toEqual([
    'Rapid1,902', 'Blitz1,765', 'Bullet1,801', 'ClassicalNot rated',
  ])
})
