import { useState } from 'react'
import type { PuzzleCategory } from './puzzles'
import styles from './MyOpenings.module.css'

type Preferences = { openings: string[]; favorites: string[]; variationOrder: Record<string, string[]>; variationFavorites: string[] }
const defaults = (): Preferences => ({
  openings: ['London_System', 'Caro-Kann_Defense'], favorites: ['London_System'],
  variationOrder: {}, variationFavorites: [],
})
const keyPattern = /^[A-Za-z0-9_-]{1,120}$/
const validList = (value: unknown): value is string[] => Array.isArray(value) && value.length <= 1000 &&
  value.every((item) => typeof item === 'string' && keyPattern.test(item)) && new Set(value).size === value.length

function readPreferences(storageKey: string): { value: Preferences; error: string | null } {
  try {
    const raw = localStorage.getItem(storageKey)
    if (raw === null) return { value: defaults(), error: null }
    if (raw.length > 200_000) throw new Error('Too large')
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid')
    const data = value as Record<string, unknown>
    if (!validList(data.openings) || !validList(data.favorites) || !validList(data.variationFavorites) ||
      typeof data.variationOrder !== 'object' || data.variationOrder === null || Array.isArray(data.variationOrder)) throw new Error('Invalid')
    const entries = Object.entries(data.variationOrder)
    if (entries.length > 1000 || entries.some(([key, list]) => !keyPattern.test(key) || !validList(list))) throw new Error('Invalid')
    return { value: { openings: data.openings, favorites: data.favorites, variationFavorites: data.variationFavorites,
      variationOrder: Object.fromEntries(entries) as Record<string, string[]> }, error: null }
  } catch {
    return { value: defaults(), error: 'Saved opening preferences could not be read. Changes will replace them.' }
  }
}

function move(items: string[], key: string, direction: number) {
  const next = [...items]
  const from = next.indexOf(key)
  const to = from + direction
  if (from < 0 || to < 0 || to >= next.length) return next
  ;[next[from], next[to]] = [next[to], next[from]]
  return next
}

function ordered(keys: string[], favorites: string[]) {
  return [...keys.filter((key) => favorites.includes(key)), ...keys.filter((key) => !favorites.includes(key))]
}

export function MyOpenings({ categories, accountId }: { categories: PuzzleCategory[]; accountId: string | null }) {
  const storageKey = `chess-tweek:my-openings:${encodeURIComponent(accountId ?? '')}`
  const [initial] = useState(() => accountId === null
    ? { value: defaults(), error: 'Connect Lichess to save your openings.' }
    : readPreferences(storageKey))
  const [preferences, setPreferences] = useState(initial.value)
  const [error, setError] = useState(initial.error)
  const [choice, setChoice] = useState('')
  const [expanded, setExpanded] = useState<string[]>([])
  const families = categories.filter((category) => category.kind === 'opening-family')
  const familyByKey = new Map(families.map((family) => [family.key, family]))
  const selected = ordered(preferences.openings, preferences.favorites).filter((key) => familyByKey.has(key))
  const options = families.filter((family) => !preferences.openings.includes(family.key))
    .sort((a, b) => a.name.localeCompare(b.name))

  const save = (value: Preferences) => {
    if (accountId === null) return
    try {
      if (!validList(value.openings) || !validList(value.favorites) || !validList(value.variationFavorites) ||
          Object.entries(value.variationOrder).length > 1000 ||
          Object.entries(value.variationOrder).some(([key, list]) => !keyPattern.test(key) || !validList(list))) {
        throw new Error('Preference limit reached')
      }
      const serialized = JSON.stringify(value)
      if (serialized.length > 200_000) throw new Error('Preference limit reached')
      localStorage.setItem(storageKey, serialized)
      setPreferences(value)
      setError(null)
    } catch {
      setError('Your changes could not be saved. Check browser storage and try again.')
    }
  }
  const toggle = (list: string[], key: string) => list.includes(key) ? list.filter((item) => item !== key) : [...list, key]
  const relocate = (list: string[], favorites: string[], key: string, direction: number, visible: string[]) => {
    const displayed = ordered(list, favorites).filter((item) => visible.includes(item))
    const index = displayed.indexOf(key)
    const neighbor = displayed[index + direction]
    if (neighbor === undefined || favorites.includes(key) !== favorites.includes(neighbor)) return list
    return [...move(displayed, key, direction), ...list.filter((item) => !visible.includes(item))]
  }

  return <div className={styles.list}>
    {error && <p role="alert">{error}</p>}
    <div className={styles.add}>
      <label htmlFor="add-opening">Add opening</label>
      <select id="add-opening" value={choice} onChange={(event) => setChoice(event.target.value)}>
        <option value="">Choose an opening</option>
        {options.map((family) => <option key={family.key} value={family.key}>{family.name}</option>)}
      </select>
      <button type="button" disabled={choice === '' || accountId === null} onClick={() => {
        if (!options.some((option) => option.key === choice) || preferences.openings.length >= 1000) return
        save({ ...preferences, openings: [...preferences.openings, choice] })
        setChoice('')
      }}>Add opening</button>
    </div>
    {selected.length === 0 && <p>No selected openings are available in the imported puzzle library.</p>}
    {selected.map((key, index) => {
      const family = familyByKey.get(key)!
      const variations = categories.filter((category) => category.kind === 'opening-variation' && category.parentKey === key)
      const savedOrder = preferences.variationOrder[key] ?? []
      const keys = [...savedOrder.filter((id) => variations.some((item) => item.key === id)),
        ...variations.map((item) => item.key).filter((id) => !savedOrder.includes(id))]
      const variationKeys = ordered(keys, preferences.variationFavorites)
      return <section key={key} className={styles.family} aria-label={family.name}>
        <div className={styles.row}>
          <button className={styles.disclosure} type="button" aria-expanded={expanded.includes(key)} onClick={() => setExpanded(toggle(expanded, key))}>{family.name}</button>
          <span>{family.puzzles.toLocaleString()} puzzles</span>
          <button type="button" aria-pressed={preferences.favorites.includes(key)} onClick={() => save({ ...preferences, favorites: toggle(preferences.favorites, key) })}>Favorite {family.name}</button>
          <button type="button" aria-label={`Move ${family.name} up`} disabled={index === 0 || preferences.favorites.includes(key) !== preferences.favorites.includes(selected[index - 1])} onClick={() => save({ ...preferences, openings: relocate(preferences.openings, preferences.favorites, key, -1, selected) })}>↑</button>
          <button type="button" aria-label={`Move ${family.name} down`} disabled={index === selected.length - 1 || preferences.favorites.includes(key) !== preferences.favorites.includes(selected[index + 1])} onClick={() => save({ ...preferences, openings: relocate(preferences.openings, preferences.favorites, key, 1, selected) })}>↓</button>
          <button type="button" onClick={() => save({ ...preferences, openings: preferences.openings.filter((id) => id !== key), favorites: preferences.favorites.filter((id) => id !== key) })}>Remove {family.name}</button>
        </div>
        {expanded.includes(key) && <div className={styles.variations}>
          <a href={`#/puzzles/category/opening-family/${encodeURIComponent(key)}`}>Practice {family.name}</a>
          {variationKeys.map((id, position) => {
            const variation = variations.find((item) => item.key === id)!
            const name = variation.name.startsWith(`${family.name} `) ? variation.name.slice(family.name.length + 1) : variation.name
            return <div className={styles.row} key={id}>
              <a href={`#/puzzles/category/opening-variation/${encodeURIComponent(id)}`}>{name}</a>
              <span>{variation.puzzles.toLocaleString()}</span>
              <button type="button" aria-pressed={preferences.variationFavorites.includes(id)} onClick={() => save({ ...preferences, variationFavorites: toggle(preferences.variationFavorites, id) })}>Favorite {name}</button>
              <button type="button" aria-label={`Move ${name} up`} disabled={position === 0 || preferences.variationFavorites.includes(id) !== preferences.variationFavorites.includes(variationKeys[position - 1])} onClick={() => save({ ...preferences, variationOrder: { ...preferences.variationOrder, [key]: relocate(keys, preferences.variationFavorites, id, -1, variationKeys) } })}>↑</button>
              <button type="button" aria-label={`Move ${name} down`} disabled={position === variationKeys.length - 1 || preferences.variationFavorites.includes(id) !== preferences.variationFavorites.includes(variationKeys[position + 1])} onClick={() => save({ ...preferences, variationOrder: { ...preferences.variationOrder, [key]: relocate(keys, preferences.variationFavorites, id, 1, variationKeys) } })}>↓</button>
            </div>
          })}
        </div>}
      </section>
    })}
  </div>
}
