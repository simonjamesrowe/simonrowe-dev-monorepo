import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { DataOperationsAdmin } from '../../src/pages/admin/DataOperationsAdmin'

vi.mock('../../src/services/dataOperationsApi', () => ({
  fetchDataOpsStatus: vi.fn(),
  startBackup: vi.fn(),
  fetchBackups: vi.fn(),
  fetchPlatformBackups: vi.fn(),
  startRestore: vi.fn(),
  startClear: vi.fn(),
  startRebuildIndex: vi.fn(),
  startReembed: vi.fn(),
  connectProgress: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  connectProgress,
  fetchBackups,
  fetchDataOpsStatus,
  fetchPlatformBackups,
  startClear,
  startRebuildIndex,
  startReembed,
  startRestore,
  type BackupMetadata,
  type DataOperation,
} from '../../src/services/dataOperationsApi'
import { useAuth } from '../../src/auth/useAuth'

const getAccessToken = vi.fn().mockResolvedValue('test-token')

const backup: BackupMetadata = {
  fileId: 'backup-1',
  fileName: 'backup-20261016-210000.zip',
  createdAt: '2026-10-16T21:00:00Z',
  fileSize: 1024,
  fileSizeFormatted: '1 KB',
}

const accepted: DataOperation = {
  id: 'op-1',
  type: 'BACKUP',
  status: 'IN_PROGRESS',
  startedAt: '2026-10-17T10:00:00Z',
  completedAt: null,
  progressMessage: 'Starting',
  progressPercent: 0,
  errorMessage: null,
  resultSummary: null,
}

/** The backdrop wrapping the dialog whose heading is `title` (dialog titles are h2; card titles are h3). */
function backdropOf(title: string): HTMLElement {
  const backdrop = screen.getByRole('heading', { level: 2, name: title }).closest('.confirm-dialog-backdrop')
  expect(backdrop).not.toBeNull()
  return backdrop as HTMLElement
}

async function renderPage() {
  render(<DataOperationsAdmin />)
  await screen.findByRole('heading', { level: 1, name: 'Data Operations' })
}

describe('DataOperationsAdmin confirmation dialogs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    vi.mocked(fetchDataOpsStatus).mockResolvedValue({
      googleDriveConnected: true,
      googleDriveError: null,
      operationInProgress: false,
      currentOperation: null,
      lastOperation: null,
    })
    vi.mocked(fetchPlatformBackups).mockResolvedValue([])
    vi.mocked(fetchBackups).mockResolvedValue([backup])
    vi.mocked(connectProgress).mockReturnValue({ close: vi.fn() })
    vi.mocked(startRestore).mockResolvedValue(accepted)
    vi.mocked(startClear).mockResolvedValue(accepted)
    vi.mocked(startRebuildIndex).mockResolvedValue(accepted)
    vi.mocked(startReembed).mockResolvedValue(accepted)
  })

  describe('backup list', () => {
    async function openBackupList() {
      await renderPage()
      fireEvent.click(screen.getByRole('button', { name: 'Choose Backup' }))
      await screen.findByRole('heading', { level: 2, name: 'Available Backups' })
    }

    it('lists the backups from Drive', async () => {
      await openBackupList()

      expect(screen.getByText(backup.fileName)).toBeInTheDocument()
      expect(screen.getByText('1 KB')).toBeInTheDocument()
    })

    it('stays open when the click lands inside the panel', async () => {
      await openBackupList()

      fireEvent.click(screen.getByRole('heading', { level: 2, name: 'Available Backups' }))
      fireEvent.click(screen.getByText(backup.fileName))

      expect(screen.getByRole('heading', { level: 2, name: 'Available Backups' })).toBeInTheDocument()
    })

    it('closes on a click on the backdrop', async () => {
      await openBackupList()

      fireEvent.click(backdropOf('Available Backups'))

      expect(screen.queryByRole('heading', { level: 2, name: 'Available Backups' })).toBeNull()
    })

    it('closes from its Close button', async () => {
      await openBackupList()

      fireEvent.click(screen.getByRole('button', { name: 'Close' }))

      expect(screen.queryByRole('heading', { level: 2, name: 'Available Backups' })).toBeNull()
    })

    it('says so when Drive holds no backups', async () => {
      vi.mocked(fetchBackups).mockResolvedValue([])
      await openBackupList()

      expect(screen.getByText('No backups found in Google Drive.')).toBeInTheDocument()
    })
  })

  describe('restore confirmation', () => {
    async function openRestoreConfirm() {
      await renderPage()
      fireEvent.click(screen.getByRole('button', { name: 'Choose Backup' }))
      await screen.findByRole('heading', { level: 2, name: 'Available Backups' })
      fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
      await screen.findByRole('heading', { level: 2, name: 'Confirm Restore' })
    }

    it('names the backup it will restore', async () => {
      await openRestoreConfirm()

      const dialog = backdropOf('Confirm Restore')
      expect(dialog).toHaveTextContent(backup.fileName)
    })

    it('stays open when the click lands inside the dialog', async () => {
      await openRestoreConfirm()

      fireEvent.click(screen.getByRole('heading', { level: 2, name: 'Confirm Restore' }))

      expect(screen.getByRole('heading', { level: 2, name: 'Confirm Restore' })).toBeInTheDocument()
      expect(startRestore).not.toHaveBeenCalled()
    })

    /**
     * The confirmation sits above the backup list as a separate backdrop: dismissing it
     * returns to the list rather than closing both.
     */
    it('closes on a click on its backdrop and leaves the backup list open', async () => {
      await openRestoreConfirm()

      fireEvent.click(backdropOf('Confirm Restore'))

      expect(screen.queryByRole('heading', { level: 2, name: 'Confirm Restore' })).toBeNull()
      expect(screen.getByRole('heading', { level: 2, name: 'Available Backups' })).toBeInTheDocument()
      expect(startRestore).not.toHaveBeenCalled()
    })

    it('closes from Cancel without restoring', async () => {
      await openRestoreConfirm()

      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

      expect(screen.queryByRole('heading', { level: 2, name: 'Confirm Restore' })).toBeNull()
      expect(startRestore).not.toHaveBeenCalled()
    })

    it('restores the chosen backup on confirm and closes both dialogs', async () => {
      await openRestoreConfirm()

      const confirm = backdropOf('Confirm Restore').querySelector('.confirm-dialog__btn--confirm')
      fireEvent.click(confirm as HTMLElement)

      await waitFor(() => expect(startRestore).toHaveBeenCalledWith(getAccessToken, 'backup-1'))
      expect(screen.queryByRole('heading', { level: 2, name: 'Confirm Restore' })).toBeNull()
      expect(screen.queryByRole('heading', { level: 2, name: 'Available Backups' })).toBeNull()
    })
  })

  describe('clear all data', () => {
    async function openClear() {
      await renderPage()
      fireEvent.click(screen.getByRole('button', { name: 'Clear All Data' }))
      await screen.findByRole('heading', { level: 2, name: 'Clear All Data' })
    }

    it('stays open when the click lands inside the dialog, including its input', async () => {
      await openClear()

      fireEvent.click(screen.getByPlaceholderText('DELETE ALL DATA'))
      fireEvent.click(screen.getByRole('heading', { level: 2, name: 'Clear All Data' }))

      expect(screen.getByRole('heading', { level: 2, name: 'Clear All Data' })).toBeInTheDocument()
    })

    it('closes on a backdrop click and forgets the phrase typed so far', async () => {
      await openClear()
      fireEvent.change(screen.getByPlaceholderText('DELETE ALL DATA'), { target: { value: 'DELETE ALL' } })

      fireEvent.click(backdropOf('Clear All Data'))
      expect(screen.queryByRole('heading', { level: 2, name: 'Clear All Data' })).toBeNull()

      fireEvent.click(screen.getByRole('button', { name: 'Clear All Data' }))
      expect(screen.getByPlaceholderText('DELETE ALL DATA')).toHaveValue('')
      expect(startClear).not.toHaveBeenCalled()
    })

    it('enables Clear only once the exact phrase is typed, then clears', async () => {
      await openClear()
      const confirm = backdropOf('Clear All Data').querySelector('.confirm-dialog__btn--confirm') as HTMLButtonElement

      fireEvent.change(screen.getByPlaceholderText('DELETE ALL DATA'), { target: { value: 'delete all data' } })
      expect(confirm).toBeDisabled()

      fireEvent.change(screen.getByPlaceholderText('DELETE ALL DATA'), { target: { value: 'DELETE ALL DATA' } })
      expect(confirm).toBeEnabled()
      fireEvent.click(confirm)

      await waitFor(() => expect(startClear).toHaveBeenCalledWith(getAccessToken, 'DELETE ALL DATA'))
      expect(screen.queryByRole('heading', { level: 2, name: 'Clear All Data' })).toBeNull()
    })
  })

  describe.each([
    {
      opener: 'Rebuild Index',
      title: 'Rebuild Search Index',
      confirm: 'Rebuild',
      action: startRebuildIndex,
    },
    {
      opener: 'Re-embed All',
      title: 'Re-embed All Content',
      confirm: 'Re-embed',
      action: startReembed,
    },
  ])('$title confirmation', ({ opener, title, confirm, action }) => {
    async function open() {
      await renderPage()
      fireEvent.click(screen.getByRole('button', { name: opener }))
      await screen.findByRole('heading', { level: 2, name: title })
    }

    it('stays open when the click lands inside the dialog', async () => {
      await open()

      fireEvent.click(screen.getByRole('heading', { level: 2, name: title }))

      expect(screen.getByRole('heading', { level: 2, name: title })).toBeInTheDocument()
      expect(action).not.toHaveBeenCalled()
    })

    it('closes on a click on the backdrop without starting anything', async () => {
      await open()

      fireEvent.click(backdropOf(title))

      expect(screen.queryByRole('heading', { level: 2, name: title })).toBeNull()
      expect(action).not.toHaveBeenCalled()
    })

    it('closes from Cancel without starting anything', async () => {
      await open()

      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

      expect(screen.queryByRole('heading', { level: 2, name: title })).toBeNull()
      expect(action).not.toHaveBeenCalled()
    })

    it('starts the operation on confirm', async () => {
      await open()

      fireEvent.click(screen.getByRole('button', { name: confirm }))

      await waitFor(() => expect(action).toHaveBeenCalledWith(getAccessToken))
      expect(screen.queryByRole('heading', { level: 2, name: title })).toBeNull()
    })
  })
})
