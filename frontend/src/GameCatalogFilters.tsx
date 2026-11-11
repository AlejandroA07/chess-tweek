import styles from './GameCatalogFilters.module.css'
import type { CatalogFilters } from './gameCatalog'

export function GameCatalogFilters({
  filters,
  onChange,
  onClear,
}: {
  filters: CatalogFilters
  onChange: (filters: CatalogFilters) => void
  onClear: () => void
}) {
  const active = filters.result !== '' || filters.color !== '' || filters.sort !== 'newest'

  return (
    <section className={styles.toolbar} aria-label="Game filters">
      <label>
        <span>Result</span>
        <select
          value={filters.result}
          onChange={(event) => onChange({
            ...filters,
            result: event.target.value as CatalogFilters['result'],
          })}
        >
          <option value="">All results</option>
          <option value="win">Wins</option>
          <option value="loss">Losses</option>
          <option value="draw">Draws</option>
        </select>
      </label>

      <label>
        <span>Color</span>
        <select
          value={filters.color}
          onChange={(event) => onChange({
            ...filters,
            color: event.target.value as CatalogFilters['color'],
          })}
        >
          <option value="">All colors</option>
          <option value="white">White</option>
          <option value="black">Black</option>
        </select>
      </label>

      <label>
        <span>Sort games</span>
        <select
          value={filters.sort}
          onChange={(event) => onChange({
            ...filters,
            sort: event.target.value as CatalogFilters['sort'],
          })}
        >
          <option value="newest">Newest first</option>
          <option value="oldest">Oldest first</option>
        </select>
      </label>

      <button type="button" disabled={!active} onClick={onClear}>Clear filters</button>
    </section>
  )
}
