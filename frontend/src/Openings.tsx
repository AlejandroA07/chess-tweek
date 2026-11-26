import { useEffect, useId, useRef, useState } from 'react'
import { InteractiveChessBoard, type BoardOrientation } from './ChessBoard'
import { gameReviewHref } from './gameRef'
import styles from './Openings.module.css'
import {
  explorerHref,
  ExplorerRequestError,
  fetchLichess,
  fetchLine,
  fetchMasters,
  fetchMyGames,
  fetchNote,
  fetchNoteIndex,
  games,
  NOTE_LIMIT,
  percent,
  RATING_GROUPS,
  resetNote,
  saveNote,
  SPEED_LABELS,
  SPEEDS,
  type Database,
  type ExplorerFailure,
  type ExplorerMove,
  type ExplorerRoute,
  type LichessStatistics,
  type Line,
  type MyGamesFilter,
  type MyGamesStatistics,
  type Note,
  type NoteEntry,
  type RatingGroup,
  type Results,
  type Speed,
} from './openingExplorer'

type SessionFailure = 'not_connected' | 'session_unavailable'
type Props = { explore: ExplorerRoute | null; onSessionFailure: (failure: SessionFailure) => void }

/** The Openings destination: a home page with the noted positions, and the explorer board. */
export function Openings({ explore, onSessionFailure }: Props) {
  return explore === null
    ? <OpeningsHome onSessionFailure={onSessionFailure} />
    : <Explorer key={explorerHref(explore)} route={explore} onSessionFailure={onSessionFailure} />
}

const DEFAULT_SPEEDS: Speed[] = ['rapid']
const DEFAULT_RATINGS: RatingGroup[] = [1600, 1800]
const DATABASE_LABELS: Record<Database, string> = { masters: 'Masters', lichess: 'Lichess', mine: 'My games' }

function ratingLabel(group: RatingGroup) {
  const index = RATING_GROUPS.indexOf(group)
  const next = RATING_GROUPS[index + 1]
  if (group === 0) return 'Below 1000'
  return next === undefined ? `${group} and above` : `${group}–${next - 1}`
}

function failureOf(error: unknown): ExplorerFailure | null {
  if (error instanceof DOMException && error.name === 'AbortError') return null
  return error instanceof ExplorerRequestError ? error.failure : { kind: 'unavailable' }
}

function OpeningsHome({ onSessionFailure }: { onSessionFailure: (failure: SessionFailure) => void }) {
  const [entries, setEntries] = useState<NoteEntry[] | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    fetchNoteIndex(controller.signal).then(setEntries, (error: unknown) => {
      const failure = failureOf(error)
      if (failure === null) return
      if (failure.kind === 'not_connected' || failure.kind === 'session_unavailable') onSessionFailure(failure.kind)
      else setFailed(true)
    })
    return () => controller.abort()
  }, [onSessionFailure])

  const openings = entries === null ? [] : [...new Set(entries.flatMap((entry) => entry.opening === null ? [] : [entry.opening]))]
  const others = entries?.filter((entry) => entry.opening === null) ?? []

  return <div className={styles.page}>
    <section className={styles.intro} aria-labelledby="openings-title">
      <p className={styles.eyebrow}>Opening explorer</p>
      <h2 id="openings-title">Explore openings</h2>
      <p>See what masters, Lichess players, and you have played from any position, and keep notes on the plans behind your openings.</p>
      <a className={styles.primaryLink} href={explorerHref({ from: null, moves: [] })}>Open the explorer</a>
    </section>
    <section className={styles.panel} aria-labelledby="notes-title">
      <h2 id="notes-title">Positions with notes</h2>
      <p className={styles.muted}>Starter notes for the London System and the Caro-Kann Defense come with Chess Tweek. You can edit, hide, or restore them, and add your own notes to any position.</p>
      {failed && <p role="alert">The notes could not be loaded. Reload the page to try again.</p>}
      {entries === null && !failed && <p role="status">Loading notes…</p>}
      {openings.map((opening) => <div key={opening} className={styles.noteGroup}>
        <h3>{opening}</h3>
        <ul className={styles.noteList}>
          {entries!.filter((entry) => entry.opening === opening).map((entry) =>
            <NoteLink key={entry.fen + entry.title} entry={entry} />)}
        </ul>
      </div>)}
      {others.length > 0 && <div className={styles.noteGroup}>
        <h3>Your other notes</h3>
        <ul className={styles.noteList}>{others.map((entry) => <NoteLink key={entry.fen} entry={entry} />)}</ul>
      </div>}
    </section>
  </div>
}

function NoteLink({ entry }: { entry: NoteEntry }) {
  const href = entry.uciMoves.length > 0
    ? explorerHref({ from: null, moves: entry.uciMoves })
    : explorerHref({ from: entry.fen, moves: [] })
  return <li>
    <a href={href}>{entry.title ?? 'Your note'}</a>
    {entry.moves.length > 0 && <span className={styles.technical}>{movesText(entry.moves, true)}</span>}
    <span className={styles.sourceLabel}>{entry.source === 'starter' ? 'Starter note' : 'Your note'}</span>
    <p>{entry.excerpt}</p>
  </li>
}

/** A move with its number when White plays it, or when it opens a line with Black to move. */
function moveLabel(index: number, san: string, whiteFirst: boolean) {
  const ply = whiteFirst ? index : index + 1
  if (ply % 2 === 0) return `${ply / 2 + 1}. ${san}`
  return index === 0 ? `${(ply - 1) / 2 + 1}… ${san}` : san
}

/** SAN moves with move numbers, such as "1. d4 d5 2. Bf4". */
function movesText(moves: readonly string[], whiteFirst: boolean) {
  return moves.map((move, index) => moveLabel(index, move, whiteFirst)).join(' ')
}

type StatsState =
  | { kind: 'loading' }
  | { kind: 'lichess'; data: LichessStatistics }
  | { kind: 'mine'; data: MyGamesStatistics }
  | { kind: 'failed'; failure: ExplorerFailure }

function Explorer({ route, onSessionFailure }: { route: ExplorerRoute; onSessionFailure: (failure: SessionFailure) => void }) {
  const [line, setLine] = useState<Line | null>(null)
  const [lineFailed, setLineFailed] = useState(false)
  const [cursor, setCursor] = useState(route.moves.length)
  const [orientation, setOrientation] = useState<BoardOrientation>('white')
  const [database, setDatabase] = useState<Database>('masters')
  const [speeds, setSpeeds] = useState<Speed[]>(DEFAULT_SPEEDS)
  const [ratings, setRatings] = useState<RatingGroup[]>(DEFAULT_RATINGS)
  const [mineFilter, setMineFilter] = useState<MyGamesFilter>({ color: '', speed: '', source: '' })
  const [stats, setStats] = useState<StatsState>({ kind: 'loading' })
  const [retry, setRetry] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    fetchLine(route.from, route.moves, controller.signal).then(setLine, (error: unknown) => {
      const failure = failureOf(error)
      if (failure === null) return
      if (failure.kind === 'not_connected' || failure.kind === 'session_unavailable') onSessionFailure(failure.kind)
      else setLineFailed(true)
    })
    return () => controller.abort()
  }, [route, onSessionFailure])

  const fen = line === null ? null : cursor === 0 ? line.start : line.moves[cursor - 1]?.fen ?? line.fen
  const speedKey = speeds.join(',')
  const ratingKey = ratings.join(',')

  useEffect(() => {
    if (fen === null) return
    const controller = new AbortController()
    setStats({ kind: 'loading' })
    const request = database === 'masters'
      ? fetchMasters(fen, controller.signal).then((data): StatsState => ({ kind: 'lichess', data }))
      : database === 'lichess'
        ? fetchLichess(fen, speedKey === '' ? [] : speedKey.split(',') as Speed[],
          ratingKey === '' ? [] : ratingKey.split(',').map(Number) as RatingGroup[], controller.signal)
          .then((data): StatsState => ({ kind: 'lichess', data }))
        : fetchMyGames(fen, mineFilter, controller.signal).then((data): StatsState => ({ kind: 'mine', data }))
    request.then(setStats, (error: unknown) => {
      const failure = failureOf(error)
      if (failure === null) return
      if (failure.kind === 'not_connected' || failure.kind === 'session_unavailable') onSessionFailure(failure.kind)
      else setStats({ kind: 'failed', failure })
    })
    return () => controller.abort()
  }, [fen, database, speedKey, ratingKey, mineFilter, retry, onSessionFailure])

  const play = (uci: string) => {
    if (line === null) return
    const moves = [...line.moves.slice(0, cursor).map((move) => move.uci), uci]
    if (moves.length > 200) return
    window.location.hash = explorerHref({ from: route.from, moves })
  }

  const sans = line?.moves.map((move) => move.san) ?? []
  const whiteFirst = line === null || line.start.split(' ')[1] === 'w'
  const toMove = fen === null ? null : fen.split(' ')[1] === 'w' ? 'White' : 'Black'

  return <div className={styles.explorer}>
    <a className={styles.backLink} href="#/openings">Back to Openings</a>
    <div className={styles.workspace}>
      <div className={styles.boardColumn} data-active-board>
        {lineFailed && <p role="alert">This line could not be opened. <a href={explorerHref({ from: null, moves: [] })}>Start from the beginning</a>.</p>}
        {fen !== null && <InteractiveChessBoard
          fen={fen}
          orientation={orientation}
          label={`Opening explorer: ${toMove} to move`}
          disabled={false}
          solution={null}
          initialLastMove={cursor > 0 ? line!.moves[cursor - 1].uci : null}
          onMove={(move) => { play(move) }}
        />}
        <div className={styles.boardControls} role="group" aria-label="Move through the line">
          <button type="button" onClick={() => setCursor(0)} disabled={cursor === 0}>Start</button>
          <button type="button" onClick={() => setCursor(cursor - 1)} disabled={cursor === 0}>Back</button>
          <button type="button" onClick={() => setCursor(cursor + 1)} disabled={line === null || cursor >= line.moves.length}>Forward</button>
          <button type="button" onClick={() => setCursor(line?.moves.length ?? 0)} disabled={line === null || cursor >= line.moves.length}>End</button>
          <button type="button" onClick={() => setOrientation(orientation === 'white' ? 'black' : 'white')}>Flip Board</button>
        </div>
        <p className={styles.muted}>{orientation === 'white' ? 'White' : 'Black'} at the bottom. {toMove !== null && `${toMove} to move.`}</p>
      </div>
      <div className={styles.sideColumn}>
        <section className={styles.panel} aria-labelledby="line-title">
          <h2 id="line-title">Line</h2>
          {sans.length === 0
            ? <p className={styles.muted}>{route.from === null ? 'Starting position. Play a move on the board or choose one below.' : 'Saved position. Play a move on the board or choose one below.'}</p>
            : <ol className={styles.moveList}>
              {sans.map((san, index) => <li key={index}>
                <button type="button" aria-current={index + 1 === cursor ? 'step' : undefined} onClick={() => setCursor(index + 1)}>
                  {moveLabel(index, san, whiteFirst)}
                </button>
              </li>)}
            </ol>}
        </section>
        <NotePanel fen={fen} onSessionFailure={onSessionFailure} />
        <section className={styles.panel} aria-labelledby="games-title">
          <h2 id="games-title">Games from this position</h2>
          <fieldset className={styles.choice}>
            <legend>Games from</legend>
            {(['masters', 'lichess', 'mine'] as const).map((value) => <label key={value}>
              <input type="radio" name="database" value={value} checked={database === value} onChange={() => setDatabase(value)} />
              {DATABASE_LABELS[value]}
            </label>)}
          </fieldset>
          {database === 'lichess' && <div className={styles.filters}>
            <fieldset className={styles.choice}>
              <legend>Speeds</legend>
              {SPEEDS.map((speed) => <label key={speed}>
                <input type="checkbox" checked={speeds.includes(speed)} onChange={() => setSpeeds(toggle(speeds, speed, SPEEDS))} />
                {SPEED_LABELS[speed]}
              </label>)}
            </fieldset>
            <fieldset className={styles.choice}>
              <legend>Rating groups</legend>
              {RATING_GROUPS.map((group) => <label key={group}>
                <input type="checkbox" checked={ratings.includes(group)} onChange={() => setRatings(toggle(ratings, group, RATING_GROUPS))} />
                {ratingLabel(group)}
              </label>)}
            </fieldset>
            <p className={styles.muted}>With no speed or rating group chosen, all Lichess games count.</p>
          </div>}
          {database === 'mine' && <div className={styles.selects}>
            <label>Played as
              <select value={mineFilter.color} onChange={(event) => setMineFilter({ ...mineFilter, color: event.target.value as MyGamesFilter['color'] })}>
                <option value="">Either color</option><option value="white">White</option><option value="black">Black</option>
              </select>
            </label>
            <label>Speed
              <select value={mineFilter.speed} onChange={(event) => setMineFilter({ ...mineFilter, speed: event.target.value as MyGamesFilter['speed'] })}>
                <option value="">Any speed</option>
                {SPEEDS.map((speed) => <option key={speed} value={speed}>{SPEED_LABELS[speed]}</option>)}
              </select>
            </label>
            <label>Site
              <select value={mineFilter.source} onChange={(event) => setMineFilter({ ...mineFilter, source: event.target.value as MyGamesFilter['source'] })}>
                <option value="">Both sites</option><option value="lichess">Lichess</option><option value="chesscom">chess.com</option>
              </select>
            </label>
          </div>}
          <Statistics database={database} state={stats} onPlay={play} onRetry={() => setRetry(retry + 1)} />
        </section>
      </div>
    </div>
  </div>
}

function toggle<T>(values: readonly T[], value: T, order: readonly T[]): T[] {
  const next = values.includes(value) ? values.filter((item) => item !== value) : [...values, value]
  return order.filter((item) => next.includes(item))
}

function Statistics({ database, state, onPlay, onRetry }: {
  database: Database
  state: StatsState
  onPlay: (uci: string) => void
  onRetry: () => void
}) {
  if (state.kind === 'loading') return <p role="status">Loading {DATABASE_LABELS[database]} games…</p>
  if (state.kind === 'failed') {
    const message = state.failure.kind === 'rate_limited'
      ? `Lichess asked Chess Tweek to slow down. Try again in ${state.failure.retryAfterSeconds} seconds. My games still works.`
      : state.failure.kind === 'lichess_unavailable'
        ? 'The Lichess explorer could not be reached. My games still works.'
        : state.failure.kind === 'invalid'
          ? 'This position could not be looked up.'
          : 'The statistics could not be loaded.'
    return <div className={styles.notice} role="alert">
      <p>{message}</p>
      <button type="button" onClick={onRetry}>Try again</button>
    </div>
  }
  const data = state.data
  const total = games(data.results)
  return <div className={styles.statistics}>
    {state.kind === 'lichess' && state.data.opening !== null &&
      <p className={styles.opening}><span className={styles.technical}>{state.data.opening.eco}</span> {state.data.opening.name}</p>}
    {state.kind === 'lichess' && state.data.stale &&
      <p className={styles.notice} role="status">Lichess could not be reached, so these are the results saved on {new Date(state.data.fetchedAt).toLocaleDateString()}.</p>}
    {total === 0
      ? <p>{database === 'mine' ? 'None of your stored games reached this position.' : `No ${DATABASE_LABELS[database]} games reached this position.`}</p>
      : <>
        <p className={styles.summary}>
          <span className={styles.technical}>{total.toLocaleString()}</span> games · White wins {percent(data.results.white, total)} · Draws {percent(data.results.draws, total)} · Black wins {percent(data.results.black, total)}
        </p>
        <MovesTable moves={data.moves} showRating={state.kind === 'lichess'} label={`Moves played in ${DATABASE_LABELS[database]} games`} onPlay={onPlay} />
      </>}
    {state.kind === 'lichess' && state.data.topGames.length > 0 && <div>
      <h3>Top games</h3>
      <ul className={styles.gameList}>
        {state.data.topGames.map((game) => <li key={game.id}>
          {player(game.white)} – {player(game.black)}
          <span className={styles.technical}>{game.winner === 'white' ? '1–0' : game.winner === 'black' ? '0–1' : '½–½'}</span>
          {game.year !== null && <span className={styles.muted}>{game.year}</span>}
        </li>)}
      </ul>
    </div>}
    {state.kind === 'mine' && state.data.recentGames.length > 0 && <div>
      <h3>Your recent games here</h3>
      <ul className={styles.gameList}>
        {state.data.recentGames.map((game) => <li key={`${game.game.source}:${game.game.id}`}>
          <a href={gameReviewHref(game.game)}>
            {game.ownerResult === 'win' ? 'Won' : game.ownerResult === 'loss' ? 'Lost' : 'Drew'} as {game.ownerColor} against {game.opponentName}{game.opponentRating === null ? '' : ` (${game.opponentRating})`}
          </a>
          <span className={styles.muted}>{SPEED_LABELS[game.speed]} · {game.game.source === 'lichess' ? 'Lichess' : 'chess.com'} · {new Date(game.playedAt).toLocaleDateString()}</span>
          {game.nextSan !== null && <span className={styles.technical}>Next: {game.nextSan}</span>}
        </li>)}
      </ul>
    </div>}
    {state.kind === 'lichess' && <p className={styles.muted}>Statistics from the Lichess opening explorer, saved {new Date(state.data.fetchedAt).toLocaleString()}.</p>}
  </div>
}

function player(value: { name: string; rating: number | null; title: string | null }) {
  return `${value.title === null ? '' : `${value.title} `}${value.name}${value.rating === null ? '' : ` (${value.rating})`}`
}

function MovesTable({ moves, showRating, label, onPlay }: {
  moves: ExplorerMove[]
  showRating: boolean
  label: string
  onPlay: (uci: string) => void
}) {
  return <table className={styles.moves}>
    <caption className={styles.visuallyHidden}>{label}</caption>
    <thead>
      <tr>
        <th scope="col">Move</th>
        <th scope="col">Games</th>
        <th scope="col">White / Draws / Black</th>
        {showRating && <th scope="col">Average rating</th>}
      </tr>
    </thead>
    <tbody>
      {moves.map((move) => <tr key={move.uci}>
        <th scope="row"><button type="button" aria-label={`Play ${move.san}`} onClick={() => onPlay(move.uci)}>{move.san}</button></th>
        <td className={styles.technical}>{games(move.results).toLocaleString()}</td>
        <td><ResultBar results={move.results} /></td>
        {showRating && <td className={styles.technical}>{move.averageRating ?? '—'}</td>}
      </tr>)}
    </tbody>
  </table>
}

function ResultBar({ results }: { results: Results }) {
  const total = games(results)
  const text = `${percent(results.white, total)} / ${percent(results.draws, total)} / ${percent(results.black, total)}`
  return <span className={styles.result}>
    <span className={styles.technical}>{text}</span>
    <span className={styles.bar} aria-hidden="true">
      <span className={styles.barWhite} style={{ flexGrow: results.white }} />
      <span className={styles.barDraw} style={{ flexGrow: results.draws }} />
      <span className={styles.barBlack} style={{ flexGrow: results.black }} />
    </span>
  </span>
}

type NoteState =
  | { kind: 'loading' }
  | { kind: 'ready'; note: Note | null }
  | { kind: 'failed' }

function NotePanel({ fen, onSessionFailure }: { fen: string | null; onSessionFailure: (failure: SessionFailure) => void }) {
  const [state, setState] = useState<NoteState>({ kind: 'loading' })
  const [editing, setEditing] = useState<string | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const textId = useId()
  const editRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (fen === null) return
    const controller = new AbortController()
    setState({ kind: 'loading' })
    setEditing(null)
    setConfirmingDelete(false)
    fetchNote(fen, controller.signal).then((note) => setState({ kind: 'ready', note }), (error: unknown) => {
      const failure = failureOf(error)
      if (failure === null) return
      if (failure.kind === 'not_connected' || failure.kind === 'session_unavailable') onSessionFailure(failure.kind)
      else setState({ kind: 'failed' })
    })
    return () => controller.abort()
  }, [fen, reload, onSessionFailure])

  const isEditing = editing !== null
  useEffect(() => {
    if (isEditing) editRef.current?.focus()
  }, [isEditing])

  const run = async (action: () => Promise<void>, success: string) => {
    setBusy(true)
    setMessage(null)
    try {
      await action()
      setEditing(null)
      setConfirmingDelete(false)
      setMessage(success)
      setReload((value) => value + 1)
    } catch (error: unknown) {
      const failure = failureOf(error)
      if (failure !== null && (failure.kind === 'not_connected' || failure.kind === 'session_unavailable')) {
        onSessionFailure(failure.kind)
      } else {
        setMessage('The note was not saved. Nothing has changed.')
      }
    } finally {
      setBusy(false)
    }
  }

  const note = state.kind === 'ready' ? state.note : null
  const hidden = note !== null && note.source === 'owner' && note.text === '' && note.starter !== null

  return <section className={styles.panel} aria-labelledby="note-title">
    <h2 id="note-title">Note for this position</h2>
    {message && <p role="status" className={styles.muted}>{message}</p>}
    {state.kind === 'loading' && <p role="status">Loading the note…</p>}
    {state.kind === 'failed' && <p role="alert">The note could not be loaded.</p>}
    {state.kind === 'ready' && editing === null && <>
      {note === null && <p className={styles.muted}>No note for this position.</p>}
      {hidden && <p className={styles.muted}>You hid the starter note “{note.starter!.title}” here.</p>}
      {note !== null && !hidden && <article className={styles.note}>
        <p className={styles.noteMeta}>
          <span className={styles.sourceLabel}>{note.source === 'starter' ? 'Starter note' : 'Your note'}</span>
          {note.title !== null && <strong>{note.title}</strong>}
        </p>
        <p className={styles.noteText}>{note.text}</p>
      </article>}
      <div className={styles.noteActions}>
        {!hidden && <button type="button" onClick={() => setEditing(note?.text ?? '')}>{note === null ? 'Add note' : 'Edit note'}</button>}
        {note !== null && note.source === 'owner' && note.starter !== null &&
          <button type="button" disabled={busy} onClick={() => void run(() => resetNote(fen!), 'The starter note is back.')}>Restore starter note</button>}
        {note !== null && note.source === 'owner' && note.starter === null && !confirmingDelete &&
          <button type="button" className={styles.textButton} onClick={() => setConfirmingDelete(true)}>Delete note</button>}
      </div>
      {confirmingDelete && <div className={styles.confirm} role="group" aria-label="Delete your note">
        <p>Delete your note at this position? It cannot be brought back.</p>
        <button type="button" autoFocus onClick={() => setConfirmingDelete(false)}>Keep note</button>
        <button type="button" className={styles.dangerButton} disabled={busy} onClick={() => void run(() => resetNote(fen!), 'Your note was deleted.')}>Delete note</button>
      </div>}
    </>}
    {editing !== null && <form className={styles.editor} onSubmit={(event) => {
      event.preventDefault()
      const text = editing
      void run(() => saveNote(fen!, text), text.trim() === ''
        ? note?.starter ? 'The starter note is hidden here.' : 'The note was removed.'
        : 'Your note was saved.')
    }}>
      <label htmlFor={textId}>Your note</label>
      <textarea id={textId} ref={editRef} value={editing} maxLength={NOTE_LIMIT} rows={8}
        aria-describedby={`${textId}-help`} onChange={(event) => setEditing(event.target.value)} />
      <p id={`${textId}-help`} className={styles.muted}>
        Up to {NOTE_LIMIT.toLocaleString()} characters ({editing.length.toLocaleString()} used). Line breaks are kept.
        {note?.starter ? ' Saving empty text hides the starter note here.' : ''}
      </p>
      <div className={styles.noteActions}>
        <button type="submit" className={styles.primaryButton} disabled={busy}>{busy ? 'Saving…' : 'Save note'}</button>
        <button type="button" onClick={() => setEditing(null)} disabled={busy}>Cancel</button>
      </div>
    </form>}
  </section>
}
