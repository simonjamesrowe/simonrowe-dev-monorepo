import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { DataOperationsAdmin } from '../../src/pages/admin/DataOperationsAdmin'

vi.mock('../../src/services/dataOperationsApi', () => ({
  fetchDataOpsStatus: vi.fn(),
  startBackup: vi.fn(),
  fetchBackups: vi.fn(),
  startPlatformBackup: vi.fn(),
  fetchPlatformBackups: vi.fn(),
  startRestore: vi.fn(),
  startClear: vi.fn(),
  startRebuildIndex: vi.fn(),
  startReembed: vi.fn(),
  startRedeploy: vi.fn(),
  connectProgress: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  connectProgress,
  fetchDataOpsStatus,
  fetchPlatformBackups,
  startPlatformBackup,
  type BackupMetadata,
  type DataOperation,
  type DataOperationsStatus,
} from '../../src/services/dataOperationsApi'
import { useAuth } from '../../src/auth/useAuth'

const mockFetchStatus = vi.mocked(fetchDataOpsStatus)
const mockFetchPlatformBackups = vi.mocked(fetchPlatformBackups)
const mockStartPlatformBackup = vi.mocked(startPlatformBackup)
const mockConnectProgress = vi.mocked(connectProgress)
const mockUseAuth = vi.mocked(useAuth)

const getAccessToken = vi.fn().mockResolvedValue('test-token')

function status(overrides: Partial<DataOperationsStatus> = {}): DataOperationsStatus {
  return {
    googleDriveConnected: true,
    googleDriveError: null,
    operationInProgress: false,
    currentOperation: null,
    lastOperation: null,
    ...overrides,
  }
}

function platformArchives(): BackupMetadata[] {
  return [
    {
      fileId: 'id-1',
      fileName: 'platform-backup-20260825-020000.zip',
      createdAt: '2026-08-25T02:00:00Z',
      fileSize: 913448201,
      fileSizeFormatted: '871.1 MB',
    },
    {
      fileId: 'id-2',
      fileName: 'platform-backup-20260824-020000.zip',
      createdAt: '2026-08-24T02:00:00Z',
      fileSize: 900000000,
      fileSizeFormatted: '858.3 MB',
    },
  ]
}

function operation(overrides: Partial<DataOperation> = {}): DataOperation {
  return {
    id: 'op-1',
    type: 'PLATFORM_BACKUP',
    status: 'IN_PROGRESS',
    startedAt: '2026-08-25T02:00:00Z',
    completedAt: null,
    progressMessage: 'Exporting database: dtrack',
    progressPercent: 20,
    errorMessage: null,
    resultSummary: null,
    ...overrides,
  }
}

describe('DataOperationsAdmin — Platform Data card', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAuth.mockReturnValue({ getAccessToken } as ReturnType<typeof useAuth>)
    mockFetchStatus.mockResolvedValue(status())
    mockFetchPlatformBackups.mockResolvedValue(platformArchives())
    mockConnectProgress.mockReturnValue({ close: vi.fn() })
  })

  it('renders the card', async () => {
    render(<DataOperationsAdmin />)

    expect(await screen.findByText('Platform Data')).toBeInTheDocument()
  })

  /**
   * The card's whole purpose: the list is loaded on mount, not behind a button, so
   * a nightly job that stopped running is visible at a glance.
   */
  it('lists the retained archives with date and size on mount', async () => {
    render(<DataOperationsAdmin />)

    await waitFor(() => expect(mockFetchPlatformBackups).toHaveBeenCalled())
    expect(await screen.findByText('871.1 MB')).toBeInTheDocument()
    expect(screen.getByText('858.3 MB')).toBeInTheDocument()
  })

  it('triggers a platform backup and connects the progress stream', async () => {
    mockStartPlatformBackup.mockResolvedValue(operation())
    render(<DataOperationsAdmin />)

    fireEvent.click(await screen.findByRole('button', { name: 'Back Up Now' }))

    await waitFor(() => expect(mockStartPlatformBackup).toHaveBeenCalledWith(getAccessToken))
    expect(mockConnectProgress).toHaveBeenCalled()
  })

  it('says so when no archive exists yet', async () => {
    mockFetchPlatformBackups.mockResolvedValue([])
    render(<DataOperationsAdmin />)

    expect(await screen.findByText('No platform backups yet.')).toBeInTheDocument()
  })

  /**
   * A failure to *list* archives must not read as a failure to *back up* —
   * conflating them is how an operator concludes the nightly job is broken when it
   * is not. So it renders inside the card, not in the page-level error banner.
   */
  it('reports a listing failure inside the card', async () => {
    mockFetchPlatformBackups.mockRejectedValue(new Error('Drive unreachable'))
    render(<DataOperationsAdmin />)

    expect(await screen.findByText('Drive unreachable')).toBeInTheDocument()
  })

  it('disables the trigger while another operation is in progress', async () => {
    mockFetchStatus.mockResolvedValue(
      status({ operationInProgress: true, currentOperation: operation({ type: 'BACKUP' }) }),
    )
    render(<DataOperationsAdmin />)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Back Up Now' })).toBeDisabled(),
    )
  })

  it('disables the trigger when Google Drive is not connected', async () => {
    mockFetchStatus.mockResolvedValue(
      status({ googleDriveConnected: false, googleDriveError: 'not configured' }),
    )
    render(<DataOperationsAdmin />)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Back Up Now' })).toBeDisabled(),
    )
  })

  /**
   * `type.replace('_', ' ')` replaces only the first underscore, so a two-word
   * type renders correctly while a three-word one would not. Asserted rather than
   * assumed.
   */
  it('renders an in-progress platform backup with a readable label', async () => {
    mockFetchStatus.mockResolvedValue(
      status({ operationInProgress: true, currentOperation: operation() }),
    )
    render(<DataOperationsAdmin />)

    expect(await screen.findByText('PLATFORM BACKUP in progress')).toBeInTheDocument()
    expect(screen.getByText('Exporting database: dtrack')).toBeInTheDocument()
  })

  /**
   * Platform restore is a host shell script by design. A restore button here would
   * be the wrong tool for the scenario that motivates restore — a rebuilt host,
   * where this application is the thing being rebuilt.
   */
  it('offers no platform restore action', async () => {
    render(<DataOperationsAdmin />)

    await screen.findByText('Platform Data')
    expect(screen.queryByRole('button', { name: /restore platform/i })).toBeNull()
    expect(screen.getByText(/scripts\/restore-platform\.sh/)).toBeInTheDocument()
  })
})
