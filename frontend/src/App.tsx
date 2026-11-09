import { useEffect, useState } from 'react'
import styles from './App.module.css'

type HealthState = 'checking' | 'ready' | 'offline'

export function App() {
  const [health, setHealth] = useState<HealthState>('checking')

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
    <main className={styles.workspace}>
      <section className={styles.card} aria-labelledby="app-title">
        <p className={styles.eyebrow}>Analysis Studio</p>
        <h1 id="app-title">Chess Tweek</h1>
        <p className={styles.summary}>
          The local macOS application foundation is running.
        </p>
        <p className={styles.status} data-state={health} role="status">
          <span aria-hidden="true" />
          {statusText}
        </p>
      </section>
    </main>
  )
}
