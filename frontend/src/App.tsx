import { useCallback, useEffect, useState, type ReactNode } from 'react'
import styles from './App.module.css'
import { DashboardRatingContext } from './DashboardRatingContext'
import { GamesLibrary } from './GamesLibrary'

type HealthState = 'checking' | 'ready' | 'offline'
type IconName = 'dashboard' | 'games' | 'reports' | 'puzzles' | 'admin'
type EmptyState = { eyebrow: string; title: string; description: string }
type Route = { href: string; icon: IconName; label: string; emptyState: EmptyState }
type RatingSpeed = 'bullet' | 'blitz' | 'rapid' | 'classical' | 'correspondence'
type LichessAccount = {
  id: string
  username: string
  ratings: Record<RatingSpeed, number | null>
}
type SessionState =
  | { kind: 'checking' }
  | { kind: 'disconnected' }
  | { kind: 'connected'; account: LichessAccount }
  | { kind: 'unavailable' }
type CallbackNotice = 'cancelled' | 'invalid' | 'unavailable'

const navigation: Route[] = [
  {
    href: '#/dashboard',
    icon: 'dashboard',
    label: 'Dashboard',
    emptyState: {
      eyebrow: 'Your private chess workspace',
      title: 'No chess activity yet',
      description:
        'Ratings, recent games, reports, and training will appear here after you connect Lichess. Everything stays local on this Mac.',
    },
  },
  {
    href: '#/games',
    icon: 'games',
    label: 'Games',
    emptyState: {
      eyebrow: 'Local game library',
      title: 'No games stored on this Mac',
      description:
        'Completed Lichess games will appear here after you connect an account and choose Get Lichess Games.',
    },
  },
  {
    href: '#/reports',
    icon: 'reports',
    label: 'Reports',
    emptyState: {
      eyebrow: 'Overall Games Analysis',
      title: 'No saved reports yet',
      description:
        'Reports will appear here after exactly 10 eligible games have completed My Moves analysis.',
    },
  },
  {
    href: '#/puzzles',
    icon: 'puzzles',
    label: 'Puzzles',
    emptyState: {
      eyebrow: 'Private chess training',
      title: 'No puzzle training yet',
      description:
        'Daily, mixed, and report-owned puzzle training will appear here as its source material becomes available.',
    },
  },
  {
    href: '#/admin',
    icon: 'admin',
    label: 'Admin',
    emptyState: {
      eyebrow: 'Local operations',
      title: 'No local controls available yet',
      description:
        'Lichess, Stockfish, retrieval, storage, and database controls will appear here as those capabilities are implemented.',
    },
  },
]

export function App() {
  const [health, setHealth] = useState<HealthState>('checking')
  const [route, setRoute] = useState(currentRoute)
  const [navigationOpen, setNavigationOpen] = useState(false)
  const [session, setSession] = useState<SessionState>({ kind: 'checking' })
  const [accountAction, setAccountAction] = useState<'idle' | 'connecting' | 'logging-out'>('idle')
  const [accountError, setAccountError] = useState<string | null>(null)
  const [callbackNotice, setCallbackNotice] = useState<CallbackNotice | null>(readCallbackNotice)

  const refreshSession = useCallback(async (signal?: AbortSignal) => {
    setSession({ kind: 'checking' })
    setAccountError(null)

    try {
      const response = await fetch('/api/lichess/session', {
        credentials: 'same-origin',
        signal,
      })

      if (response.status === 503) {
        setSession({ kind: 'unavailable' })
        return
      }
      if (!response.ok) throw new Error('Session request failed')

      const body: unknown = await response.json()
      setSession(parseSession(body))
    } catch (error: unknown) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      setSession({ kind: 'unavailable' })
    }
  }, [])

  const handleCatalogSessionFailure = useCallback(
    (failure: 'not_connected' | 'session_unavailable') => {
      setAccountError(null)
      setSession(failure === 'not_connected' ? { kind: 'disconnected' } : { kind: 'unavailable' })
    },
    [],
  )

  useEffect(() => {
    const updateRoute = () => {
      setRoute(currentRoute())
      setNavigationOpen(false)
    }
    window.addEventListener('hashchange', updateRoute)
    return () => window.removeEventListener('hashchange', updateRoute)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void refreshSession(controller.signal)
    return () => controller.abort()
  }, [refreshSession])

  useEffect(() => {
    if (!callbackNotice) return
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.hash}`)
  }, [callbackNotice])

  const logout = async () => {
    setAccountAction('logging-out')
    setAccountError(null)

    try {
      const response = await fetch('/api/lichess/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) throw new Error('Logout request failed')
      setSession({ kind: 'disconnected' })
    } catch {
      setAccountError('Logout did not complete. Check the local backend and try again.')
    } finally {
      setAccountAction('idle')
    }
  }

  useEffect(() => {
    const controller = new AbortController()

    void fetch('/api/health', { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error('Health request failed')
        return response.json() as Promise<{ status: string }>
      })
      .then((response) => setHealth(response.status === 'ok' ? 'ready' : 'offline'))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        setHealth('offline')
      })

    return () => controller.abort()
  }, [])

  const statusText = {
    checking: 'Checking local backend…',
    ready: 'Backend ready',
    offline: 'Backend unavailable',
  }[health]

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <div className={styles.sidebarHeader}>
          <a
            className={styles.brand}
            href="#/dashboard"
            aria-label="Chess Tweek Dashboard"
            onClick={() => setNavigationOpen(false)}
          >
            <span className={styles.brandMark} aria-hidden="true">
              CT
            </span>
            <span>
              <strong>Chess Tweek</strong>
              <small>Analysis Studio</small>
            </span>
          </a>

          <button
            className={styles.navigationToggle}
            type="button"
            aria-label={navigationOpen ? 'Close navigation' : 'Open navigation'}
            aria-controls="primary-navigation"
            aria-expanded={navigationOpen}
            onClick={() => setNavigationOpen((open) => !open)}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d={navigationOpen ? 'M5 5l14 14M19 5 5 19' : 'M4 7h16M4 12h16M4 17h16'} />
            </svg>
          </button>
        </div>

        <nav
          id="primary-navigation"
          className={styles.navigation}
          aria-label="Primary"
          data-open={navigationOpen}
        >
          <ul>
            {navigation.map((item) => (
              <li key={item.href}>
                <a
                  className={styles.navLink}
                  href={item.href}
                  aria-current={item.href === route.href ? 'page' : undefined}
                  onClick={() => setNavigationOpen(false)}
                >
                  <NavIcon name={item.icon} />
                  <span>{item.label}</span>
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <section className={styles.account} aria-label="Lichess account" data-state={session.kind}>
          <span className={styles.accountIndicator} aria-hidden="true" />
          <span>
            <strong>Lichess</strong>
            <small>{accountStatus(session)}</small>
          </span>
        </section>
      </aside>

      <div className={styles.mainFrame}>
        <header className={styles.topBar}>
          <div>
            <p>Local workspace</p>
            <h1>{route.label}</h1>
          </div>
          <p className={styles.status} data-state={health} role="status">
            <span aria-hidden="true" />
            {statusText}
          </p>
        </header>

        <main className={styles.workspace}>
          <div className={styles.workspaceStack}>
            {route.href === '#/dashboard' && callbackNotice && (
              <CallbackBanner notice={callbackNotice} onDismiss={() => setCallbackNotice(null)} />
            )}
            {route.href === '#/dashboard' && (
              <ConnectionPanel
                session={session}
                action={accountAction}
                error={accountError}
                onConnect={() => setAccountAction('connecting')}
                onLogout={logout}
                onRetry={() => void refreshSession()}
              />
            )}
            {route.href === '#/dashboard' && session.kind === 'connected' && (
              <DashboardRatingContext
                key={session.account.id}
                currentRatings={session.account.ratings}
                onSessionFailure={handleCatalogSessionFailure}
              />
            )}
            {route.href === '#/games' ? (
              <GamesLibrary
                accountId={session.kind === 'connected' ? session.account.id : null}
                accountUsername={session.kind === 'connected' ? session.account.username : null}
                connectionState={session.kind}
                onSessionFailure={handleCatalogSessionFailure}
              />
            ) : route.href === '#/dashboard' && session.kind === 'connected' ? null : (
              <section className={styles.emptyState} aria-labelledby="workspace-title">
                <span className={styles.emptyStateIcon}>
                  <NavIcon name={route.icon} />
                </span>
                <p className={styles.eyebrow}>{route.emptyState.eyebrow}</p>
                <h2 id="workspace-title">{route.emptyState.title}</h2>
                <p>{route.emptyState.description}</p>
              </section>
            )}
          </div>
        </main>
      </div>
    </div>
  )
}

function CallbackBanner({ notice, onDismiss }: { notice: CallbackNotice; onDismiss: () => void }) {
  const messages: Record<CallbackNotice, string> = {
    cancelled: 'Lichess connection was cancelled. Nothing changed.',
    invalid: 'The Lichess connection could not be completed. Start again to get a fresh authorization.',
    unavailable: 'Lichess was unavailable during connection. Please try again.',
  }

  return (
    <div
      className={styles.callbackBanner}
      data-state={notice === 'cancelled' ? 'neutral' : 'error'}
      role={notice === 'cancelled' ? 'status' : 'alert'}
    >
      <p>{messages[notice]}</p>
      <button type="button" onClick={onDismiss}>Dismiss</button>
    </div>
  )
}

function ConnectionPanel({
  session,
  action,
  error,
  onConnect,
  onLogout,
  onRetry,
}: {
  session: SessionState
  action: 'idle' | 'connecting' | 'logging-out'
  error: string | null
  onConnect: () => void
  onLogout: () => Promise<void>
  onRetry: () => void
}) {
  if (session.kind === 'checking') {
    return (
      <section className={styles.connectionPanel} aria-live="polite">
        <p className={styles.eyebrow}>Lichess account</p>
        <h2>Checking Lichess connection…</h2>
      </section>
    )
  }

  if (session.kind === 'unavailable') {
    return (
      <section className={styles.connectionPanel} aria-live="polite">
        <p className={styles.eyebrow}>Lichess account</p>
        <h2>Lichess is temporarily unavailable</h2>
        <p>Your local data is safe. Check your connection and try again.</p>
        <button className={styles.secondaryButton} type="button" onClick={onRetry}>
          Retry
        </button>
      </section>
    )
  }

  if (session.kind === 'disconnected') {
    return (
      <section className={styles.connectionPanel}>
        <p className={styles.eyebrow}>Lichess account</p>
        <h2>Connect your Lichess account</h2>
        <p>Authorize read-only access to your account and puzzles. Chess Tweek keeps the credential out of browser code and local data.</p>
        <form method="post" action="/api/lichess/authorize" onSubmit={onConnect}>
          <button className={styles.primaryButton} type="submit" disabled={action === 'connecting'}>
            {action === 'connecting' ? 'Opening Lichess…' : 'Connect Lichess'}
          </button>
        </form>
        {error && <p className={styles.actionError} role="alert">{error}</p>}
      </section>
    )
  }

  return (
    <section className={styles.connectionPanel}>
      <p className={styles.eyebrow}>Lichess connected</p>
      <div className={styles.accountHeading}>
        <div>
          <h2>{session.account.username}</h2>
          <p>Read-only access to your account and puzzles</p>
        </div>
        <button
          className={styles.secondaryButton}
          type="button"
          disabled={action === 'logging-out'}
          onClick={() => void onLogout()}
        >
          {action === 'logging-out' ? 'Logging out…' : 'Log out'}
        </button>
      </div>
      {error && <p className={styles.actionError} role="alert">{error}</p>}
    </section>
  )
}

const ratingSpeeds: RatingSpeed[] = ['bullet', 'blitz', 'rapid', 'classical', 'correspondence']
function accountStatus(session: SessionState) {
  if (session.kind === 'checking') return 'Checking connection…'
  if (session.kind === 'connected') return session.account.username
  if (session.kind === 'unavailable') return 'Temporarily unavailable'
  return 'Not connected'
}

function parseSession(value: unknown): SessionState {
  if (!isRecord(value) || typeof value.connected !== 'boolean') {
    throw new Error('Invalid session response')
  }
  if (!value.connected && value.account === null) return { kind: 'disconnected' }
  if (!value.connected || !isRecord(value.account)) throw new Error('Invalid session response')

  const { account } = value
  if (
    !isBoundedText(account.id) ||
    !isBoundedText(account.username) ||
    !isRecord(account.ratings)
  ) {
    throw new Error('Invalid account response')
  }

  const ratings = account.ratings
  if (
    !ratingSpeeds.every(
      (speed) =>
        ratings[speed] === null ||
        (typeof ratings[speed] === 'number' &&
          Number.isSafeInteger(ratings[speed]) &&
          ratings[speed] >= 0),
    )
  ) {
    throw new Error('Invalid ratings response')
  }

  return {
    kind: 'connected',
    account: {
      id: account.id,
      username: account.username,
      ratings: Object.fromEntries(ratingSpeeds.map((speed) => [speed, ratings[speed]])) as Record<
        RatingSpeed,
        number | null
      >,
    },
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isBoundedText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 64
}

function readCallbackNotice(): CallbackNotice | null {
  const result = new URLSearchParams(window.location.search).get('lichess')
  return result === 'cancelled' || result === 'invalid' || result === 'unavailable' ? result : null
}

function currentRoute() {
  const hash = window.location.hash || '#/dashboard'
  return navigation.find((item) => item.href === hash) ?? navigation[0]
}

function NavIcon({ name }: { name: IconName }) {
  const paths: Record<IconName, ReactNode> = {
    dashboard: <path d="M4 4h6v6H4zm10 0h6v6h-6zM4 14h6v6H4zm10 0h6v6h-6z" />,
    games: <path d="M8 4h8l-1 5 3 3v8H6v-8l3-3zm1 8h6m-7 4h8" />,
    reports: <path d="M6 3h9l3 3v15H6zm9 0v4h4M9 11h6m-6 4h6" />,
    puzzles: <path d="M9 4a3 3 0 1 1 6 0v3h3v4a3 3 0 1 0 0 6v3h-5v-3a3 3 0 1 0-6 0v3H3v-6h3a3 3 0 1 0 0-6H3V7h6z" />,
    admin: <path d="M12 3 4 7v5c0 5 3.4 8 8 9 4.6-1 8-4 8-9V7zm0 5v5m0 3v.1" />,
  }

  return (
    <svg className={styles.navIcon} viewBox="0 0 24 24" aria-hidden="true">
      {paths[name]}
    </svg>
  )
}
