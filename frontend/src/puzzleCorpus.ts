export type CorpusPhase = 'downloading' | 'importing' | 'finalizing' | 'paused' | 'failed' | 'verified'

export type CorpusStorage = {
  databaseBytes: number
  usableBytes: number
  requiredFreeBytes: number
}

export type StoredSnapshot = {
  snapshotDate: string
  sourceUrl: string
  downloadChecksum: string
  importerVersion: string
  recordCount: number
  importedAt: string
}

export type UpdateCheck = {
  checkedAt: string
  remoteSnapshotDate: string
  remoteBytes: number
  updateAvailable: boolean
}

export type CorpusImport = {
  id: number
  phase: CorpusPhase
  failureCode: string | null
  snapshotDate: string
  sourceBytes: number
  downloadedBytes: number
  consumedBytes: number
  processedRecords: number
  insertedPuzzles: number
  updatedPuzzles: number
  unchangedPuzzles: number
  rejectedRecords: number
  conflictingRecords: number
  removedPuzzles: number
  retainedPuzzles: number
  sourceDeleted: boolean
  startedAt: string
  updatedAt: string
  completedAt: string | null
}

export type CorpusIssue = {
  recordNumber: number
  kind: 'rejected' | 'conflict'
  externalId: string | null
  reason: string
}

export type CorpusStatus = {
  zstdAvailable: boolean
  storage: CorpusStorage
  stored: StoredSnapshot | null
  lastCheck: UpdateCheck | null
  latestImport: CorpusImport | null
  issues: CorpusIssue[]
}

export type CorpusFailure =
  | 'not_connected'
  | 'session_unavailable'
  | 'update_check_failed'
  | 'import_running'
  | 'no_update_available'
  | 'zstd_missing'
  | 'insufficient_disk'
  | 'no_active_import'
  | 'puzzle_corpus_unavailable'

const FAILURES: readonly CorpusFailure[] = [
  'not_connected',
  'session_unavailable',
  'update_check_failed',
  'import_running',
  'no_update_available',
  'zstd_missing',
  'insufficient_disk',
  'no_active_import',
]

export class CorpusRequestError extends Error {
  constructor(readonly failure: CorpusFailure) {
    super(failure)
  }
}

const PHASES: readonly CorpusPhase[] = ['downloading', 'importing', 'finalizing', 'paused', 'failed', 'verified']
const ACTIVE: readonly CorpusPhase[] = ['downloading', 'importing', 'finalizing']
const DATE = /^\d{4}-\d{2}-\d{2}$/
const CODE = /^[a-z][a-z_]{0,63}$/
const EXTERNAL_ID = /^[A-Za-z0-9]{1,32}$/
const CHECKSUM = /^[a-f0-9]{64}$/
const VERSION = /^[A-Za-z0-9._-]{1,64}$/

export function isActive(phase: CorpusPhase) {
  return ACTIVE.includes(phase)
}

export function requestCorpusStatus(signal?: AbortSignal): Promise<CorpusStatus> {
  return send('/api/admin/puzzle-corpus', { method: 'GET', signal })
}

export function checkCorpusUpdate(): Promise<CorpusStatus> {
  return send('/api/admin/puzzle-corpus/update-checks', { method: 'POST' })
}

export function startCorpusImport(): Promise<CorpusStatus> {
  return send('/api/admin/puzzle-corpus/imports', { method: 'POST' })
}

export function cancelCorpusImport(): Promise<CorpusStatus> {
  return send('/api/admin/puzzle-corpus/imports/cancel', { method: 'POST' })
}

async function send(url: string, init: RequestInit): Promise<CorpusStatus> {
  const response = await fetch(url, {
    ...init,
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    const error = await safeErrorCode(response)
    if (response.status === 401) throw new CorpusRequestError('not_connected')
    if (error !== null && FAILURES.includes(error as CorpusFailure)) {
      throw new CorpusRequestError(error as CorpusFailure)
    }
    throw new CorpusRequestError('puzzle_corpus_unavailable')
  }
  return parseCorpusStatus(await response.json() as unknown)
}

async function safeErrorCode(response: Response) {
  try {
    const body: unknown = await response.json()
    return typeof body === 'object' && body !== null && 'error' in body &&
      typeof body.error === 'string' && body.error.length <= 64
      ? body.error
      : null
  } catch {
    return null
  }
}

function invalid(): never {
  throw new Error('Invalid puzzle corpus status')
}

function object(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) invalid()
  return value as Record<string, unknown>
}

function count(record: Record<string, unknown>, field: string) {
  const value = record[field]
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) invalid()
  return value
}

function text(record: Record<string, unknown>, field: string, pattern: RegExp) {
  const value = record[field]
  if (typeof value !== 'string' || !pattern.test(value)) invalid()
  return value
}

function instant(record: Record<string, unknown>, field: string) {
  const value = record[field]
  if (typeof value !== 'string' || value.length > 40 || !Number.isFinite(Date.parse(value))) invalid()
  return value
}

function sourceUrl(value: unknown) {
  if (typeof value !== 'string' || value.length > 240) invalid()
  let url: URL
  try {
    url = new URL(value)
  } catch {
    invalid()
  }
  const loopback = url.protocol === 'http:' && url.hostname === '127.0.0.1'
  if ((url.protocol !== 'https:' && !loopback) || url.username || url.password) invalid()
  return value
}

export function parseCorpusStatus(value: unknown): CorpusStatus {
  const record = object(value)
  if (typeof record.zstdAvailable !== 'boolean' || !Array.isArray(record.issues) || record.issues.length > 50) {
    invalid()
  }
  const storage = object(record.storage)
  return {
    zstdAvailable: record.zstdAvailable,
    storage: {
      databaseBytes: count(storage, 'databaseBytes'),
      usableBytes: count(storage, 'usableBytes'),
      requiredFreeBytes: count(storage, 'requiredFreeBytes'),
    },
    stored: record.stored === null ? null : parseStored(record.stored),
    lastCheck: record.lastCheck === null ? null : parseCheck(record.lastCheck),
    latestImport: record.latestImport === null ? null : parseImport(record.latestImport),
    issues: record.issues.map(parseIssue),
  }
}

function parseStored(value: unknown): StoredSnapshot {
  const record = object(value)
  return {
    snapshotDate: text(record, 'snapshotDate', DATE),
    sourceUrl: sourceUrl(record.sourceUrl),
    downloadChecksum: text(record, 'downloadChecksum', CHECKSUM),
    importerVersion: text(record, 'importerVersion', VERSION),
    recordCount: count(record, 'recordCount'),
    importedAt: instant(record, 'importedAt'),
  }
}

function parseCheck(value: unknown): UpdateCheck {
  const record = object(value)
  if (typeof record.updateAvailable !== 'boolean') invalid()
  return {
    checkedAt: instant(record, 'checkedAt'),
    remoteSnapshotDate: text(record, 'remoteSnapshotDate', DATE),
    remoteBytes: count(record, 'remoteBytes'),
    updateAvailable: record.updateAvailable,
  }
}

function parseImport(value: unknown): CorpusImport {
  const record = object(value)
  const phase = record.phase
  if (typeof phase !== 'string' || !PHASES.includes(phase as CorpusPhase)) invalid()
  if (typeof record.sourceDeleted !== 'boolean') invalid()
  if (!(record.failureCode === null || (typeof record.failureCode === 'string' && CODE.test(record.failureCode)))) {
    invalid()
  }
  const parsed: CorpusImport = {
    id: count(record, 'id'),
    phase: phase as CorpusPhase,
    failureCode: record.failureCode as string | null,
    snapshotDate: text(record, 'snapshotDate', DATE),
    sourceBytes: count(record, 'sourceBytes'),
    downloadedBytes: count(record, 'downloadedBytes'),
    consumedBytes: count(record, 'consumedBytes'),
    processedRecords: count(record, 'processedRecords'),
    insertedPuzzles: count(record, 'insertedPuzzles'),
    updatedPuzzles: count(record, 'updatedPuzzles'),
    unchangedPuzzles: count(record, 'unchangedPuzzles'),
    rejectedRecords: count(record, 'rejectedRecords'),
    conflictingRecords: count(record, 'conflictingRecords'),
    removedPuzzles: count(record, 'removedPuzzles'),
    retainedPuzzles: count(record, 'retainedPuzzles'),
    sourceDeleted: record.sourceDeleted,
    startedAt: instant(record, 'startedAt'),
    updatedAt: instant(record, 'updatedAt'),
    completedAt: record.completedAt === null ? null : instant(record, 'completedAt'),
  }
  if (parsed.downloadedBytes > parsed.sourceBytes || parsed.consumedBytes > parsed.sourceBytes) invalid()
  return parsed
}

function parseIssue(value: unknown): CorpusIssue {
  const record = object(value)
  if (record.kind !== 'rejected' && record.kind !== 'conflict') invalid()
  if (!(record.externalId === null ||
    (typeof record.externalId === 'string' && EXTERNAL_ID.test(record.externalId)))) {
    invalid()
  }
  return {
    recordNumber: count(record, 'recordNumber'),
    kind: record.kind,
    externalId: record.externalId as string | null,
    reason: text(record, 'reason', CODE),
  }
}
