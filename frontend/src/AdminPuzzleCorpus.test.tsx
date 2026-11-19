import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { AdminPuzzleCorpus } from './AdminPuzzleCorpus'
import { parseCorpusStatus, type CorpusImport, type CorpusStatus } from './puzzleCorpus'

function ok(body: unknown): Partial<Response> {
  return { ok: true, status: 200, json: async () => body }
}

function failed(status: number, error: string): Partial<Response> {
  return { ok: false, status, json: async () => ({ error }) }
}

function stubFetch(...responses: Array<Partial<Response>>) {
  const fetchMock = vi.fn()
  for (const response of responses) fetchMock.mockResolvedValueOnce(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const checksum = 'a'.repeat(64)

function status(overrides: Partial<CorpusStatus> = {}): CorpusStatus {
  return {
    zstdAvailable: true,
    storage: { databaseBytes: 1_200_000, usableBytes: 60_000_000_000, requiredFreeBytes: 6_442_450_944 },
    stored: null,
    lastCheck: null,
    latestImport: null,
    issues: [],
    ...overrides,
  }
}

function importOf(overrides: Partial<CorpusImport> = {}): CorpusImport {
  return {
    id: 1,
    phase: 'downloading',
    failureCode: null,
    snapshotDate: '2026-11-01',
    sourceBytes: 309_751_173,
    downloadedBytes: 100_000_000,
    consumedBytes: 0,
    processedRecords: 0,
    insertedPuzzles: 0,
    updatedPuzzles: 0,
    unchangedPuzzles: 0,
    rejectedRecords: 0,
    conflictingRecords: 0,
    removedPuzzles: 0,
    retainedPuzzles: 0,
    sourceDeleted: false,
    startedAt: '2026-11-19T10:00:00Z',
    updatedAt: '2026-11-19T10:00:05Z',
    completedAt: null,
    ...overrides,
  }
}

const availableCheck = {
  checkedAt: '2026-11-19T10:00:00Z',
  remoteSnapshotDate: '2026-11-01',
  remoteBytes: 309_751_173,
  updateAvailable: true,
}

describe('AdminPuzzleCorpus', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('requires a manual check before the explicit download and shows progress with cancel', async () => {
    const fetchMock = stubFetch(
      ok(status()),
      ok(status({ lastCheck: availableCheck })),
      ok(status({ lastCheck: availableCheck, latestImport: importOf() })),
      ok(status({ lastCheck: availableCheck, latestImport: importOf({ phase: 'importing', consumedBytes: 160_000_000 }) })),
      ok(status({ lastCheck: availableCheck, latestImport: importOf({ phase: 'paused', failureCode: 'cancelled' }) })),
    )

    render(<AdminPuzzleCorpus onSessionFailure={vi.fn()} />)

    expect(await screen.findByText('None yet')).toBeInTheDocument()
    const start = screen.getByRole('button', { name: 'Download and import' })
    expect(start).toBeDisabled()
    expect(screen.getByText('Check for update first.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Check for update' }))
    expect(await screen.findByText(/An update is available\./)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/admin/puzzle-corpus/update-checks',
      expect.objectContaining({ method: 'POST', credentials: 'same-origin' }),
    )
    expect(fetchMock).toHaveBeenCalledTimes(2)

    fireEvent.click(screen.getByRole('button', { name: 'Download and import' }))
    expect(await screen.findByText('Downloading 100 MB of 309.8 MB')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/admin/puzzle-corpus/imports',
      expect.objectContaining({ method: 'POST' }),
    )
    expect(screen.getByRole('button', { name: 'Check for update' })).toBeDisabled()

    expect(await screen.findByText(/51% of the file read/, {}, { timeout: 2_500 })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Cancel import' }))
    expect(await screen.findByText(/You cancelled the import\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Resume import' })).toBeEnabled()
  })

  it('explains a missing zstd prerequisite and disables importing', async () => {
    stubFetch(ok(status({ zstdAvailable: false, lastCheck: availableCheck })))

    render(<AdminPuzzleCorpus onSessionFailure={vi.fn()} />)

    expect(await screen.findByText('brew install zstd')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download and import' })).toBeDisabled()
    expect(screen.getByText('Install zstd before importing.')).toBeInTheDocument()
  })

  it('shows the stored snapshot provenance and the records that were not imported', async () => {
    stubFetch(ok(status({
      stored: {
        snapshotDate: '2026-11-01',
        sourceUrl: 'https://database.lichess.org/lichess_db_puzzle.csv.zst',
        downloadChecksum: checksum,
        importerVersion: 'puzzle-corpus-v1',
        recordCount: 6_193_530,
        importedAt: '2026-11-19T10:10:00Z',
      },
      lastCheck: { ...availableCheck, updateAvailable: false },
      latestImport: importOf({
        phase: 'verified',
        processedRecords: 6_193_531,
        insertedPuzzles: 6_193_518,
        unchangedPuzzles: 12,
        conflictingRecords: 1,
        sourceDeleted: true,
        completedAt: '2026-11-19T10:10:00Z',
      }),
      issues: [{ recordNumber: 42, kind: 'conflict', externalId: 'xrHJJ', reason: 'immutable_conflict' }],
    })))

    render(<AdminPuzzleCorpus onSessionFailure={vi.fn()} />)

    expect(await screen.findByText('6,193,530')).toBeInTheDocument()
    expect(screen.getByText(checksum)).toBeInTheDocument()
    expect(screen.getByText(/The downloaded file was deleted\./)).toBeInTheDocument()
    expect(screen.getByText('The stored snapshot is already current.')).toBeInTheDocument()
    const table = screen.getByRole('table')
    expect(within(table).getByText('xrHJJ')).toBeInTheDocument()
    expect(within(table).getByText('Different position or solution for a stored ID; stored Puzzle kept'))
      .toBeInTheDocument()
  })

  it('reports refused starts and failed checks without losing the current status', async () => {
    stubFetch(
      ok(status({ lastCheck: availableCheck })),
      failed(409, 'insufficient_disk'),
      failed(502, 'update_check_failed'),
    )

    render(<AdminPuzzleCorpus onSessionFailure={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Download and import' }))
    expect(await screen.findByText(/There is not enough free disk space\. The import needs the download plus about 6\.4 GB free\./))
      .toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Check for update' }))
    expect(await screen.findByText('Lichess could not be reached for the file details. Nothing changed; try again later.'))
      .toBeInTheDocument()
    expect(screen.getByText(/An update is available\./)).toBeInTheDocument()
  })

  it('explains a failed snapshot without offering resume', async () => {
    stubFetch(ok(status({ latestImport: importOf({ phase: 'failed', failureCode: 'source_changed' }) })))

    render(<AdminPuzzleCorpus onSessionFailure={vi.fn()} />)

    expect(await screen.findByText(/Lichess published a different file during the download/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resume import' })).not.toBeInTheDocument()
  })

  it('hands session failures to the shell', async () => {
    stubFetch({ ok: false, status: 401, json: async () => ({ error: 'not_connected' }) })
    const onSessionFailure = vi.fn()

    render(<AdminPuzzleCorpus onSessionFailure={onSessionFailure} />)

    await vi.waitFor(() => expect(onSessionFailure).toHaveBeenCalledWith('not_connected'))
  })
})

describe('parseCorpusStatus', () => {
  it('rejects unsafe or inconsistent payloads', () => {
    expect(() => parseCorpusStatus({ ...status(), issues: 'x' })).toThrow()
    expect(() => parseCorpusStatus({ ...status(), latestImport: { ...importOf(), phase: 'deleting' } })).toThrow()
    expect(() => parseCorpusStatus({
      ...status(), latestImport: importOf({ downloadedBytes: 400_000_000 }),
    })).toThrow()
    expect(() => parseCorpusStatus({
      ...status(),
      stored: {
        snapshotDate: '2026-11-01',
        sourceUrl: 'javascript:alert(1)',
        downloadChecksum: checksum,
        importerVersion: 'puzzle-corpus-v1',
        recordCount: 1,
        importedAt: '2026-11-19T10:10:00Z',
      },
    })).toThrow()
    expect(() => parseCorpusStatus({
      ...status(),
      issues: [{ recordNumber: 1, kind: 'conflict', externalId: '<script>', reason: 'x' }],
    })).toThrow()
    expect(parseCorpusStatus(status()).stored).toBeNull()
  })
})
