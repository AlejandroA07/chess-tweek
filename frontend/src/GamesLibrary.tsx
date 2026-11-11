import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import styles from './GamesLibrary.module.css'
import { GameRetrievalPanel } from './GameRetrievalPanel'
import { CatalogRequestError, requestCatalog, type CatalogGame } from './gameCatalog'

type CatalogState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; total: number; games: CatalogGame[]; nextOffset: number }
  | { kind: 'not-connected' }
  | { kind: 'session-unavailable' }
  | { kind: 'error' }

type ConnectionState = 'checking' | 'disconnected' | 'connected' | 'unavailable'

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
  const olderRequest = useRef<AbortController | null>(null)
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
          onComplete={() => setRefreshKey((key) => key + 1)}
          onSessionFailure={onSessionFailure}
        />
      )
    : null

  const connectedView = (content: ReactNode) => <>{content}{retrievalPanel}</>

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

    void requestCatalog(0, controller.signal)
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
  }, [accountId, onSessionFailure, refreshKey])

  useEffect(() => () => olderRequest.current?.abort(), [])

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
      const page = await requestCatalog(expectedOffset, controller.signal)
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
      <p ref={countRef} className={styles.count} tabIndex={-1}>
        {catalog.games.length === catalog.total
          ? `${catalog.total} stored games`
          : `${catalog.games.length} of ${catalog.total} stored games loaded`}
      </p>
      <div className={styles.gameGrid}>
        {catalog.games.map((game) => <GameCard key={game.id} game={game} />)}
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

function GameCard({ game }: { game: CatalogGame }) {
  const result = titleCase(game.result)
  const opponentName = game.opponent.title
    ? `${game.opponent.title} ${game.opponent.name}`
    : game.opponent.name
  const opening = game.opening
    ? `${game.opening.eco} · ${game.opening.name}`
    : 'Opening unavailable'

  return (
    <article className={styles.gameCard} aria-label={`${result} against ${game.opponent.name}`}>
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
