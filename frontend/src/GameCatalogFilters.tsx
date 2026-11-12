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
  const active = Object.entries(filters).some(([key, value]) => (
    key === 'sort' ? value !== 'newest' : value !== ''
  ))
  const update = <Key extends keyof CatalogFilters>(key: Key, value: CatalogFilters[Key]) => {
    onChange({ ...filters, [key]: value })
  }

  return (
    <section className={styles.toolbar} aria-label="Game filters">
      <label>
        <span>Result</span>
        <select
          value={filters.result}
          onChange={(event) => update('result', event.target.value as CatalogFilters['result'])}
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
          onChange={(event) => update('color', event.target.value as CatalogFilters['color'])}
        >
          <option value="">All colors</option>
          <option value="white">White</option>
          <option value="black">Black</option>
        </select>
      </label>

      <label>
        <span>Opening</span>
        <input
          type="search"
          list="opening-filter-options"
          maxLength={80}
          placeholder="ECO or opening name"
          value={filters.opening}
          onChange={(event) => update('opening', event.target.value)}
        />
        <datalist id="opening-filter-options" />
      </label>

      <label>
        <span>Opponent type</span>
        <select
          value={filters.opponent}
          onChange={(event) => update(
            'opponent', event.target.value as CatalogFilters['opponent'],
          )}
        >
          <option value="">All opponents</option>
          <option value="human">Human</option>
          <option value="lichess_ai">Lichess AI</option>
          <option value="bot">Bot</option>
          <option value="anonymous">Anonymous</option>
        </select>
      </label>

      <label>
        <span>Speed</span>
        <select
          value={filters.speed}
          onChange={(event) => update('speed', event.target.value as CatalogFilters['speed'])}
        >
          <option value="">All speeds</option>
          <option value="ultraBullet">UltraBullet</option>
          <option value="bullet">Bullet</option>
          <option value="blitz">Blitz</option>
          <option value="rapid">Rapid</option>
          <option value="classical">Classical</option>
          <option value="correspondence">Correspondence</option>
        </select>
      </label>

      <label>
        <span>Games from</span>
        <input
          type="date"
          min="2000-01-01"
          max={filters.to || '2100-12-31'}
          value={filters.from}
          onChange={(event) => update('from', event.target.value)}
        />
      </label>

      <label>
        <span>Games to</span>
        <input
          type="date"
          min={filters.from || '2000-01-01'}
          max="2100-12-31"
          value={filters.to}
          onChange={(event) => update('to', event.target.value)}
        />
      </label>

      <label>
        <span>Variant</span>
        <select
          value={filters.variant}
          onChange={(event) => update(
            'variant', event.target.value as CatalogFilters['variant'],
          )}
        >
          <option value="">All variants</option>
          <option value="standard">Standard</option>
          <option value="chess960">Chess960</option>
          <option value="crazyhouse">Crazyhouse</option>
          <option value="antichess">Antichess</option>
          <option value="atomic">Atomic</option>
          <option value="horde">Horde</option>
          <option value="kingOfTheHill">King of the Hill</option>
          <option value="racingKings">Racing Kings</option>
          <option value="threeCheck">Three-check</option>
          <option value="fromPosition">From Position</option>
        </select>
      </label>

      <label>
        <span>Analysis status</span>
        <select
          value={filters.analysis}
          onChange={(event) => update(
            'analysis', event.target.value as CatalogFilters['analysis'],
          )}
        >
          <option value="">All analysis states</option>
          <option value="eligible">Eligible</option>
          <option value="catalog_only">Catalog only</option>
        </select>
      </label>

      <label>
        <span>Sort games</span>
        <select
          value={filters.sort}
          onChange={(event) => update('sort', event.target.value as CatalogFilters['sort'])}
        >
          <option value="newest">Newest first</option>
          <option value="oldest">Oldest first</option>
        </select>
      </label>

      <button type="button" disabled={!active} onClick={onClear}>Clear filters</button>
    </section>
  )
}
