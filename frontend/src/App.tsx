import { useEffect, useState, type ReactNode } from 'react'
import styles from './App.module.css'

type HealthState = 'checking' | 'ready' | 'offline'
type IconName = 'dashboard' | 'games' | 'reports' | 'puzzles' | 'admin'
type EmptyState = { eyebrow: string; title: string; description: string }
type Route = { href: string; icon: IconName; label: string; emptyState: EmptyState }

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

        <section className={styles.account} aria-label="Lichess account">
          <span className={styles.accountIndicator} aria-hidden="true" />
          <span>
            <strong>Lichess</strong>
            <small>Not connected</small>
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
          <section className={styles.emptyState} aria-labelledby="workspace-title">
            <span className={styles.emptyStateIcon}>
              <NavIcon name={route.icon} />
            </span>
            <p className={styles.eyebrow}>{route.emptyState.eyebrow}</p>
            <h2 id="workspace-title">{route.emptyState.title}</h2>
            <p>{route.emptyState.description}</p>
          </section>
        </main>
      </div>
    </div>
  )
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
