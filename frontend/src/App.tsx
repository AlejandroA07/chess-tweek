import { useEffect, useState, type ReactNode } from 'react'
import styles from './App.module.css'

type HealthState = 'checking' | 'ready' | 'offline'
type IconName = 'dashboard' | 'games' | 'reports' | 'puzzles' | 'admin'

const navigation: Array<{ href: string; icon: IconName; label: string }> = [
  { href: '#/dashboard', icon: 'dashboard', label: 'Dashboard' },
  { href: '#/games', icon: 'games', label: 'Games' },
  { href: '#/reports', icon: 'reports', label: 'Reports' },
  { href: '#/puzzles', icon: 'puzzles', label: 'Puzzles' },
  { href: '#/admin', icon: 'admin', label: 'Admin' },
]

export function App() {
  const [health, setHealth] = useState<HealthState>('checking')
  const [route, setRoute] = useState(currentRoute)

  useEffect(() => {
    const updateRoute = () => setRoute(currentRoute())
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
        <a className={styles.brand} href="#/dashboard" aria-label="Chess Tweek Dashboard">
          <span className={styles.brandMark} aria-hidden="true">
            CT
          </span>
          <span>
            <strong>Chess Tweek</strong>
            <small>Analysis Studio</small>
          </span>
        </a>

        <nav className={styles.navigation} aria-label="Primary">
          <ul>
            {navigation.map((item) => (
              <li key={item.href}>
                <a
                  className={styles.navLink}
                  href={item.href}
                  aria-current={item.href === route.href ? 'page' : undefined}
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
          <section className={styles.placeholder} aria-labelledby="workspace-title">
            <p className={styles.eyebrow}>Your private chess workspace</p>
            <h2 id="workspace-title">The studio foundation is ready.</h2>
            <p>
              Chess Tweek runs locally on this Mac. The workspace views will be added in the
              next focused checkpoint.
            </p>
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
