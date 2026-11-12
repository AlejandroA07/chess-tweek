import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import styles from './GamesLibrary.module.css'
import { GameCatalogFilters } from './GameCatalogFilters'
import { GameRetrievalPanel } from './GameRetrievalPanel'
import {
  CatalogRequestError,
  EMPTY_CATALOG_FILTERS,
  requestCatalog,
  requestEligibleSelectionTotal,
  type CatalogFilters,
  type CatalogGame,
} from './gameCatalog'

type CatalogState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; total: number; games: CatalogGame[]; nextOffset: number }
  | { kind: 'not-connected' }
  | { kind: 'session-unavailable' }
  | { kind: 'error' }

type ConnectionState = 'checking' | 'disconnected' | 'connected' | 'unavailable'

type GameSelection =
  | { kind: 'explicit'; ids: Set<string> }
  | { kind: 'filtered'; total: number; excludedIds: Set<string> }

const emptySelection = (): GameSelection => ({ kind: 'explicit', ids: new Set() })

export function GamesLibrary({
  accountId,
  accountUsername,
  connectionState,
  onSessionFailure,
}: {
  accountId: string | null
  accountUsername: string | null
  connectionState: ConnectionState
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [catalog, setCatalog] = useState<CatalogState>({ kind: 'idle' })
  const [refreshKey, setRefreshKey] = useState(0)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [olderError, setOlderError] = useState(false)
  const [retrievalOpen, setRetrievalOpen] = useState(false)
  const [filters, setFilters] = useState<CatalogFilters>(EMPTY_CATALOG_FILTERS)
  const [selection, setSelection] = useState<GameSelection>(emptySelection)
  const [selectionLoading, setSelectionLoading] = useState(false)
  const [selectionError, setSelectionError] = useState(false)
  const [selectionEmpty, setSelectionEmpty] = useState(false)
  const olderRequest = useRef<AbortController | null>(null)
  const selectionRequest = useRef<AbortController | null>(null)
  const libraryRef = useRef<HTMLElement | null>(null)
  const countRef = useRef<HTMLParagraphElement | null>(null)
  const focusTarget = useRef<'library' | 'count' | null>(null)
  const retrievalTriggerRef = useRef<HTMLButtonElement | null>(null)

  const closeRetrieval = () => {
    setRetrievalOpen(false)
    retrievalTriggerRef.current?.focus()
  }

  const retrievalPanel = retrievalOpen && accountUsername !== null
    ? (
        <GameRetrievalPanel
          username={accountUsername}
          onClose={closeRetrieval}
          onComplete={() => {
            selectionRequest.current?.abort()
            setSelection(emptySelection())
            setSelectionLoading(false)
            setSelectionError(false)
            setSelectionEmpty(false)
            setRefreshKey((key) => key + 1)
          }}
          onSessionFailure={onSessionFailure}
        />
      )
    : null

  const connectedView = (content: ReactNode) => <>{content}{retrievalPanel}</>

  const selectedCount = selection.kind === 'explicit'
    ? selection.ids.size
    : selection.total - selection.excludedIds.size

  const gameIsSelected = (gameId: string) => selection.kind === 'explicit'
    ? selection.ids.has(gameId)
    : !selection.excludedIds.has(gameId)

  const toggleGameSelection = (game: CatalogGame) => {
    if (!game.analysis.eligible) return
    setSelection((current) => {
      if (current.kind === 'filtered') {
        const excludedIds = new Set(current.excludedIds)
        if (excludedIds.has(game.id)) excludedIds.delete(game.id)
        else excludedIds.add(game.id)
        return { ...current, excludedIds }
      }
      const ids = new Set(current.ids)
      if (ids.has(game.id)) ids.delete(game.id)
      else ids.add(game.id)
      return { kind: 'explicit', ids }
    })
  }

  const clearSelection = () => {
    selectionRequest.current?.abort()
    setSelection(emptySelection())
    setSelectionLoading(false)
    setSelectionError(false)
    setSelectionEmpty(false)
  }

  const changeFilters = (next: CatalogFilters) => {
    clearSelection()
    setFilters(next)
  }

  const selectAllFiltered = async () => {
    const controller = new AbortController()
    selectionRequest.current?.abort()
    selectionRequest.current = controller
    setSelectionLoading(true)
    setSelectionError(false)
    setSelectionEmpty(false)
    try {
      const total = await requestEligibleSelectionTotal(controller.signal, filters)
      setSelection(total === 0
        ? emptySelection()
        : { kind: 'filtered', total, excludedIds: new Set() })
      setSelectionEmpty(total === 0)
    } catch (error: unknown) {
      if (error instanceof CatalogRequestError && error.failure === 'not_connected') {
        onSessionFailure(error.failure)
      } else if (error instanceof CatalogRequestError && error.failure === 'session_unavailable') {
        onSessionFailure(error.failure)
      } else if (!(error instanceof DOMException && error.name === 'AbortError')) {
        setSelectionError(true)
      }
    } finally {
      if (selectionRequest.current === controller) {
        selectionRequest.current = null
        setSelectionLoading(false)
      }
    }
  }

  useEffect(() => {
    if (accountId === null) {
      setCatalog({ kind: 'idle' })
      return
    }

    const controller = new AbortController()
    let ignore = false
    olderRequest.current?.abort()
    setCatalog({ kind: 'loading' })
    setLoadingOlder(false)
    setOlderError(false)

    void requestCatalog(0, controller.signal, filters)
      .then((response) => {
        if (!ignore) {
          setCatalog({
            kind: 'ready',
            total: response.total,
            games: response.games,
            nextOffset: response.offset + response.games.length,
          })
        }
      })
      .catch((error: unknown) => {
        if (ignore || (error instanceof DOMException && error.name === 'AbortError')) return
        if (error instanceof CatalogRequestError && error.failure === 'not_connected') {
          onSessionFailure(error.failure)
          setCatalog({ kind: 'not-connected' })
        } else if (error instanceof CatalogRequestError && error.failure === 'session_unavailable') {
          onSessionFailure(error.failure)
          setCatalog({ kind: 'session-unavailable' })
        } else {
          setCatalog({ kind: 'error' })
        }
      })

    return () => {
      ignore = true
      controller.abort()
    }
  }, [accountId, filters, onSessionFailure, refreshKey])

  useEffect(() => () => {
    olderRequest.current?.abort()
    selectionRequest.current?.abort()
  }, [])

  useEffect(() => {
    selectionRequest.current?.abort()
    setSelection(emptySelection())
    setSelectionLoading(false)
    setSelectionError(false)
    setSelectionEmpty(false)
  }, [accountId])

  useEffect(() => {
    if (focusTarget.current === 'library' && catalog.kind !== 'loading') {
      libraryRef.current?.focus()
      focusTarget.current = null
    } else if (focusTarget.current === 'count' && !loadingOlder) {
      countRef.current?.focus()
      focusTarget.current = null
    }
  }, [catalog, loadingOlder])

  const loadOlder = async () => {
    if (catalog.kind !== 'ready' || loadingOlder || catalog.nextOffset >= catalog.total) return

    const expectedOffset = catalog.nextOffset
    const controller = new AbortController()
    olderRequest.current?.abort()
    olderRequest.current = controller
    setLoadingOlder(true)
    setOlderError(false)

    try {
      const page = await requestCatalog(expectedOffset, controller.signal, filters)
      if (page.offset !== expectedOffset) throw new Error('Unexpected catalog page')
      focusTarget.current = 'count'
      setCatalog((current) => {
        if (current.kind !== 'ready') return current
        const knownIds = new Set(current.games.map((game) => game.id))
        const additions = page.games.filter((game) => !knownIds.has(game.id))
        return {
          kind: 'ready',
          total: page.total,
          games: [...current.games, ...additions],
          nextOffset: page.offset + page.games.length,
        }
      })
    } catch (error: unknown) {
      if (error instanceof CatalogRequestError && error.failure === 'not_connected') {
        onSessionFailure(error.failure)
        setCatalog({ kind: 'not-connected' })
      } else if (error instanceof CatalogRequestError && error.failure === 'session_unavailable') {
        onSessionFailure(error.failure)
        setCatalog({ kind: 'session-unavailable' })
      } else if (!(error instanceof DOMException && error.name === 'AbortError')) {
        setOlderError(true)
      }
    } finally {
      if (olderRequest.current === controller) olderRequest.current = null
      setLoadingOlder(false)
    }
  }

  if (connectionState === 'checking') {
    return <ConnectionMessage libraryRef={libraryRef} title="Checking Lichess connection…" />
  }

  if (connectionState === 'disconnected' || catalog.kind === 'not-connected') {
    return (
      <ConnectionMessage
        libraryRef={libraryRef}
        title="Connect Lichess to view stored games"
        description="Open Dashboard to connect or reconnect your account. Your existing local games are unchanged."
      />
    )
  }

  if (connectionState === 'unavailable' || catalog.kind === 'session-unavailable') {
    return (
      <ConnectionMessage
        libraryRef={libraryRef}
        title="Lichess connection is unavailable"
        description="Your stored games remain local and unchanged. Open Dashboard to retry the connection."
      />
    )
  }

  if (accountId === null || catalog.kind === 'idle') {
    return <ConnectionMessage libraryRef={libraryRef} title="Checking stored games…" />
  }

  if (catalog.kind === 'loading') {
    return connectedView(
      <section ref={libraryRef} className={styles.library} aria-live="polite" tabIndex={-1}>
        <LibraryHeading onGetGames={() => setRetrievalOpen(true)} triggerRef={retrievalTriggerRef} />
        <GameCatalogFilters
          filters={filters}
          onChange={changeFilters}
          onClear={() => changeFilters(EMPTY_CATALOG_FILTERS)}
        />
        <div className={styles.loadingGrid} aria-label="Loading stored games">
          {[0, 1, 2].map((card) => (
            <article className={styles.loadingCard} key={card} aria-hidden="true">
              <span className={styles.loadingHeading} />
              <span className={styles.loadingTitle} />
              <span className={styles.loadingLine} />
              <span className={styles.loadingOpening} />
              <span className={styles.loadingDetails} />
              <span className={styles.loadingStatus} />
            </article>
          ))}
        </div>
      </section>
    )
  }

  if (catalog.kind === 'error') {
    return connectedView(
      <section ref={libraryRef} className={styles.library} aria-live="polite" tabIndex={-1}>
        <LibraryHeading onGetGames={() => setRetrievalOpen(true)} triggerRef={retrievalTriggerRef} />
        <GameCatalogFilters
          filters={filters}
          onChange={changeFilters}
          onClear={() => changeFilters(EMPTY_CATALOG_FILTERS)}
        />
        <div className={styles.message} role="alert">
          <h3>Stored games could not be loaded</h3>
          <p>Your local data was not changed.</p>
          <button
            type="button"
            onClick={() => {
              focusTarget.current = 'library'
              setRefreshKey((key) => key + 1)
            }}
          >
            Retry
          </button>
        </div>
      </section>
    )
  }

  if (catalog.games.length === 0) {
    if (hasNarrowingFilters(filters)) {
      return connectedView(
        <section ref={libraryRef} className={styles.library} tabIndex={-1}>
          <LibraryHeading onGetGames={() => setRetrievalOpen(true)} triggerRef={retrievalTriggerRef} />
          <GameCatalogFilters
            filters={filters}
            onChange={changeFilters}
            onClear={() => changeFilters(EMPTY_CATALOG_FILTERS)}
          />
          <div className={styles.message}>
            <h3>No games match these filters</h3>
            <p>Clear filters to return to your complete stored library.</p>
            <button type="button" onClick={() => changeFilters(EMPTY_CATALOG_FILTERS)}>
              Clear filters
            </button>
          </div>
        </section>,
      )
    }
    return connectedView(
      <EmptyCatalog
        libraryRef={libraryRef}
        onGetGames={() => setRetrievalOpen(true)}
        triggerRef={retrievalTriggerRef}
      />,
    )
  }

  return connectedView(
    <section ref={libraryRef} className={styles.library} tabIndex={-1}>
      <LibraryHeading onGetGames={() => setRetrievalOpen(true)} triggerRef={retrievalTriggerRef} />
      <GameCatalogFilters
        filters={filters}
        onChange={changeFilters}
        onClear={() => changeFilters(EMPTY_CATALOG_FILTERS)}
      />
      <p ref={countRef} className={styles.count} tabIndex={-1}>
        {catalogCount(catalog.games.length, catalog.total, filters)}
      </p>
      <section className={styles.selectionActions} aria-label="Game selection">
        <div>
          <button
            type="button"
            disabled={selectionLoading || filters.analysis === 'catalog_only'}
            onClick={() => void selectAllFiltered()}
          >
            {selectionLoading ? 'Selecting filtered games…' : 'Select All Filtered'}
          </button>
          <button type="button" disabled={selectedCount === 0} onClick={clearSelection}>
            Clear Selection
          </button>
        </div>
        <p>Only analysis-eligible games can be selected. Changing filters clears selection.</p>
        {filters.analysis === 'catalog_only' && (
          <p>Catalog-only games are unavailable for selection.</p>
        )}
        {selectionError && (
          <p role="alert">Filtered games could not be selected. Try again.</p>
        )}
        {selectionEmpty && (
          <p role="status">No analysis-eligible games match the current filters.</p>
        )}
      </section>
      {selectedCount > 0 && (
        <aside className={styles.selectionBar} aria-live="polite">
          <strong>
            {selectedCount} {selectedCount === 1 ? 'game' : 'games'} selected
          </strong>
          <span>
            {selection.kind === 'filtered'
              ? 'All analysis-eligible games matching the current filters.'
              : 'Selected games are ready for a later eligible action.'}
          </span>
        </aside>
      )}
      <div className={styles.gameGrid}>
        {catalog.games.map((game) => (
          <GameCard
            key={game.id}
            game={game}
            selected={game.analysis.eligible && gameIsSelected(game.id)}
            onSelectionChange={() => toggleGameSelection(game)}
          />
        ))}
      </div>
      {catalog.nextOffset < catalog.total && (
        <div className={styles.loadOlder}>
          <button type="button" disabled={loadingOlder} onClick={() => void loadOlder()}>
            {loadingOlder ? 'Loading older games…' : 'Load older games'}
          </button>
          {olderError && <p role="alert">Older games could not be loaded. Try again.</p>}
        </div>
      )}
    </section>
  )
}

function LibraryHeading({
  onGetGames,
  triggerRef,
}: {
  onGetGames?: () => void
  triggerRef?: RefObject<HTMLButtonElement | null>
}) {
  return (
    <header className={styles.heading}>
      <div>
        <p className={styles.eyebrow}>Local game library</p>
        <h2>Lichess games</h2>
        <p>Completed games are stored locally on this Mac.</p>
      </div>
      {onGetGames && (
        <button ref={triggerRef} type="button" onClick={onGetGames}>Get Lichess Games</button>
      )}
    </header>
  )
}

function EmptyCatalog({
  libraryRef,
  onGetGames,
  triggerRef,
}: {
  libraryRef: RefObject<HTMLElement | null>
  onGetGames: () => void
  triggerRef: RefObject<HTMLButtonElement | null>
}) {
  return (
    <section ref={libraryRef} className={styles.library} tabIndex={-1}>
      <LibraryHeading onGetGames={onGetGames} triggerRef={triggerRef} />
      <div className={styles.message}>
        <h3>No games stored on this Mac</h3>
        <p>Connect Lichess, then choose Get Lichess Games to build your local library.</p>
      </div>
    </section>
  )
}

function ConnectionMessage({
  libraryRef,
  title,
  description,
}: {
  libraryRef: RefObject<HTMLElement | null>
  title: string
  description?: string
}) {
  return (
    <section ref={libraryRef} className={styles.library} tabIndex={-1}>
      <LibraryHeading />
      <div className={styles.message}>
        <h3>{title}</h3>
        {description && <p>{description}</p>}
        {(title.startsWith('Connect') || title.startsWith('Lichess')) && (
          <a href="#/dashboard">Open Dashboard</a>
        )}
      </div>
    </section>
  )
}

function GameCard({
  game,
  selected,
  onSelectionChange,
}: {
  game: CatalogGame
  selected: boolean
  onSelectionChange: () => void
}) {
  const result = titleCase(game.result)
  const opponentName = game.opponent.title
    ? `${game.opponent.title} ${game.opponent.name}`
    : game.opponent.name
  const opening = game.opening
    ? `${game.opening.eco} · ${game.opening.name}`
    : 'Opening unavailable'

  return (
    <article
      className={styles.gameCard}
      aria-label={`${result} against ${game.opponent.name}`}
      data-selected={selected}
    >
      <label className={styles.selectionToggle} data-eligible={game.analysis.eligible}>
        <input
          type="checkbox"
          checked={selected}
          disabled={!game.analysis.eligible}
          aria-label={game.analysis.eligible
            ? `Select game against ${game.opponent.name}`
            : `${game.opponent.name} cannot be selected because analysis is unavailable`}
          onChange={onSelectionChange}
        />
        <span>{game.analysis.eligible ? 'Select game' : 'Selection unavailable'}</span>
      </label>
      <div className={styles.cardHeading}>
        <span className={styles.result} data-result={game.result}>{result}</span>
        <span>{formatDate(game.playedAt)}</span>
      </div>
      <h3>{opponentName}</h3>
      <p className={styles.rating}>
        {formatOpponent(game)} · You {formatRating(game.ownerRating)}
      </p>
      <p className={styles.opening}>{opening}</p>
      <dl className={styles.details}>
        <div>
          <dt>Color</dt>
          <dd>{titleCase(game.ownerColor)}</dd>
        </div>
        <div>
          <dt>Clock</dt>
          <dd>{game.timeControl.label} {titleCase(game.speed)}</dd>
        </div>
        <div>
          <dt>Game</dt>
          <dd>{game.rated ? 'Rated' : 'Casual'}</dd>
        </div>
        <div>
          <dt>Variant</dt>
          <dd>{formatVariant(game.variant)}</dd>
        </div>
        <div>
          <dt>Opponent</dt>
          <dd>{opponentType(game)}</dd>
        </div>
      </dl>
      {game.analysis.status === 'not_analyzed' ? (
        <div className={styles.analysisGroup} aria-label="Analysis status">
          <p className={styles.analysis}>My moves · Not analyzed</p>
          <p className={styles.analysis}>Opponent moves · Not analyzed</p>
        </div>
      ) : (
        <p className={styles.analysis} data-eligible="false">
          Catalog only — analysis unavailable
        </p>
      )}
    </article>
  )
}

function formatOpponent(game: CatalogGame) {
  if (game.opponent.type === 'lichess_ai' && game.opponent.aiLevel !== null) {
    return `Lichess AI level ${game.opponent.aiLevel}`
  }
  return formatRating(game.opponent.rating)
}

function formatRating(value: number | null) {
  return value === null ? 'Unrated' : value.toLocaleString('en-US')
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(value))
}

function formatVariant(value: string) {
  if (value === 'standard') return 'Standard'
  if (value === 'chess960') return 'Chess960'
  return value.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (letter) => letter.toUpperCase())
}

function opponentType(game: CatalogGame) {
  return {
    human: 'Human',
    bot: 'Bot',
    lichess_ai: 'Lichess AI',
    anonymous: 'Anonymous',
  }[game.opponent.type]
}

function titleCase(value: string) {
  return value.charAt(0).toUpperCase() + value.slice(1)
}

function hasNarrowingFilters(filters: CatalogFilters) {
  return filters.result !== ''
    || filters.color !== ''
    || filters.opening !== ''
    || filters.opponent !== ''
    || filters.speed !== ''
    || filters.from !== ''
    || filters.to !== ''
    || filters.variant !== ''
    || filters.analysis !== ''
}

function catalogCount(loaded: number, total: number, filters: CatalogFilters) {
  if (hasNarrowingFilters(filters)) {
    if (loaded !== total) return `${loaded} of ${total} matching games loaded`
    return `${total} matching ${total === 1 ? 'game' : 'games'}`
  }
  return loaded === total
    ? `${total} stored ${total === 1 ? 'game' : 'games'}`
    : `${loaded} of ${total} stored games loaded`
}
