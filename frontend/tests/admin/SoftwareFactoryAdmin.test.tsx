import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SoftwareFactoryAdmin } from '../../src/pages/admin/SoftwareFactoryAdmin'

vi.mock('../../src/services/softwareFactoryApi', () => ({
  fetchSoftwareFactoryStatus: vi.fn(),
  fetchRunProgress: vi.fn(),
  startCodeReview: vi.fn(),
  startFeedback: vi.fn(),
  startVulnerabilityScan: vi.fn(),
  startPlatformBackup: vi.fn(),
  startDeploy: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  fetchRunProgress,
  fetchSoftwareFactoryStatus,
  startCodeReview,
  startPlatformBackup,
  startVulnerabilityScan,
  type FactoryModuleStatus,
  type SoftwareFactoryStatus,
} from '../../src/services/softwareFactoryApi'
import { useAuth } from '../../src/auth/useAuth'

const mockFetchStatus = vi.mocked(fetchSoftwareFactoryStatus)
const mockFetchProgress = vi.mocked(fetchRunProgress)
const mockStartReview = vi.mocked(startCodeReview)
const mockStartScan = vi.mocked(startVulnerabilityScan)
const mockStartBackup = vi.mocked(startPlatformBackup)
const mockUseAuth = vi.mocked(useAuth)

const getAccessToken = vi.fn().mockResolvedValue('test-token')

const SHA = '0123456789abcdef0123456789abcdef01234567'

function module(
  key: FactoryModuleStatus['key'],
  overrides: Partial<FactoryModuleStatus> = {},
): FactoryModuleStatus {
  return {
    key,
    displayName: key,
    configured: true,
    taskQueue: key,
    workflowPollers: 1,
    activityPollers: 1,
    trigger: 'manual',
    schedule: null,
    missingPrerequisites: [],
    ready: true,
    diagnostic: null,
    ...overrides,
  }
}

function status(overrides: Partial<SoftwareFactoryStatus> = {}): SoftwareFactoryStatus {
  return {
    fetchedAt: '2026-08-28T09:00:00Z',
    backendCommit: SHA,
    factoryReachable: true,
    deployerReachable: true,
    modules: [
      module('codereview'),
      module('feedback'),
      module('cvefix'),
      module('deploy'),
      module('linear'),
      module('platformbackup'),
    ],
    ...overrides,
  }
}

describe('SoftwareFactoryAdmin', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAuth.mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    mockFetchStatus.mockResolvedValue(status())
  })

  it('lists every module', async () => {
    render(<SoftwareFactoryAdmin />)

    await waitFor(() => expect(screen.getByRole('list', {
      name: 'Software Factory modules',
    })).toBeInTheDocument())
    expect(screen.getAllByRole('listitem')).toHaveLength(6)
  })

  it('reports each container reachability in text, not only colour', async () => {
    mockFetchStatus.mockResolvedValue(status({ deployerReachable: false }))

    render(<SoftwareFactoryAdmin />)

    expect(await screen.findByText(/Factory reachable/)).toBeInTheDocument()
    expect(screen.getByText(/Deployer unreachable/)).toBeInTheDocument()
  })

  it('shows why an enabled module still cannot work', async () => {
    // Enabled with no credential is neither off nor healthy, and it was invisible before.
    mockFetchStatus.mockResolvedValue(status({
      modules: [module('cvefix', {
        ready: false,
        missingPrerequisites: ['Dependency-Track API key is not set'],
        diagnostic: 'Enabled but not usable: Dependency-Track API key is not set',
      })],
    }))

    render(<SoftwareFactoryAdmin />)

    expect(await screen.findByText('Dependency-Track API key is not set')).toBeInTheDocument()
  })

  it('disables an action whose module is not ready', async () => {
    mockFetchStatus.mockResolvedValue(status({
      modules: [module('cvefix', { ready: false, diagnostic: 'Disabled by configuration' })],
    }))

    render(<SoftwareFactoryAdmin />)

    expect(await screen.findByRole('button', { name: /Scan now/ })).toBeDisabled()
  })

  it('offers a code review trigger rather than status only', async () => {
    // The webhook cannot replay a review — the workflow id embeds the head SHA under
    // REJECT_DUPLICATE — so this is the only way to re-drive one that failed or never arrived.
    // Linear stays status-only, because a sink is not something you can sensibly run by itself.
    render(<SoftwareFactoryAdmin />)

    expect(await screen.findByRole('button', { name: /Review and comment/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Dry-run review/ })).toBeInTheDocument()
    expect(screen.getByLabelText(/Pull request to review/)).toBeInTheDocument()

    const codereview = screen.getAllByRole('listitem')[0]
    expect(codereview).toHaveTextContent('Review a PR')
    expect(codereview).not.toHaveTextContent('Status only')
  })

  it('keeps both review buttons disabled until a pull request is named', async () => {
    render(<SoftwareFactoryAdmin />)

    expect(await screen.findByRole('button', { name: /Review and comment/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Dry-run review/ })).toBeDisabled()
  })

  it('publishes only on the explicit review button', async () => {
    mockStartReview.mockResolvedValue({
      workflowId: 'code-review-130-uuid', runId: null, detail: 'accepted',
    })
    render(<SoftwareFactoryAdmin />)

    await userEvent.type(await screen.findByLabelText(/Pull request to review/), '130')
    await userEvent.click(screen.getByRole('button', { name: /Dry-run review/ }))
    expect(mockStartReview).toHaveBeenCalledWith(getAccessToken, 130, false)

    await userEvent.click(screen.getByRole('button', { name: /Review and comment/ }))
    expect(mockStartReview).toHaveBeenCalledWith(getAccessToken, 130, true)
  }, 10000)

  it('disables the review trigger when nothing polls the review queue', async () => {
    mockFetchStatus.mockResolvedValue(status({
      modules: [module('codereview', {
        ready: false, diagnostic: 'Required Temporal poller is missing',
      })],
    }))
    render(<SoftwareFactoryAdmin />)

    expect(await screen.findByRole('button', { name: /Review and comment/ })).toBeDisabled()
  })

  it('starts a scan and then follows it to completion', async () => {
    mockStartScan.mockResolvedValue({
      workflowId: 'cve-scan-manual-1',
      runId: 'run-1',
      detail: 'Vulnerability scan accepted',
    })
    mockFetchProgress.mockResolvedValue({
      workflowId: 'cve-scan-manual-1',
      runId: 'run-1',
      executionStatus: 'WORKFLOW_EXECUTION_STATUS_COMPLETED',
      phase: 'COMPLETED',
      detail: 'Filed one consolidated report',
      terminal: true,
    })
    render(<SoftwareFactoryAdmin />)

    await userEvent.click(await screen.findByRole('button', { name: /Scan now/ }))

    // Accepted is all the POST can prove; everything else arrives from polling.
    expect(await screen.findByText('Accepted')).toBeInTheDocument()
    expect(await screen.findByText('Completed', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText('Filed one consolidated report')).toBeInTheDocument()
  }, 10000)

  it('reports a failed run as failed rather than leaving it running', async () => {
    mockStartScan.mockResolvedValue({
      workflowId: 'cve-scan-manual-1', runId: 'run-1', detail: 'accepted',
    })
    mockFetchProgress.mockResolvedValue({
      workflowId: 'cve-scan-manual-1',
      runId: 'run-1',
      executionStatus: 'WORKFLOW_EXECUTION_STATUS_FAILED',
      phase: null,
      detail: null,
      terminal: true,
    })
    render(<SoftwareFactoryAdmin />)

    await userEvent.click(await screen.findByRole('button', { name: /Scan now/ }))

    expect(await screen.findByText('Failed', {}, { timeout: 5000 })).toBeInTheDocument()
  }, 10000)

  it('shows a safe message when an action is refused', async () => {
    mockStartScan.mockRejectedValue(new Error('That run is already in progress'))
    render(<SoftwareFactoryAdmin />)

    await userEvent.click(await screen.findByRole('button', { name: /Scan now/ }))

    expect(await screen.findByText('That run is already in progress')).toBeInTheDocument()
  })

  it('requires a second click before a real backup', async () => {
    // A dry run is one click; spending a Google Drive archive slot is two.
    mockStartBackup.mockResolvedValue({
      workflowId: 'platform-backup-manual', runId: 'run-2', detail: 'accepted',
    })
    render(<SoftwareFactoryAdmin />)

    await userEvent.click(await screen.findByRole('button', { name: 'Back up now' }))
    expect(mockStartBackup).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Confirm real backup' }))
    expect(mockStartBackup).toHaveBeenCalledWith(getAccessToken, false)
  })

  it('starts a dry run on the first click', async () => {
    mockStartBackup.mockResolvedValue({
      workflowId: 'platform-backup-manual', runId: 'run-2', detail: 'Dry run accepted',
    })
    render(<SoftwareFactoryAdmin />)

    await userEvent.click(await screen.findByRole('button', { name: 'Dry run' }))

    expect(mockStartBackup).toHaveBeenCalledWith(getAccessToken, true)
  })

  it('keeps the redeploy button disabled until the phrase matches exactly', async () => {
    render(<SoftwareFactoryAdmin />)

    const button = await screen.findByRole('button', { name: /Redeploy 0123456/ })
    expect(button).toBeDisabled()

    await userEvent.type(screen.getByLabelText(/Confirmation phrase/), 'REDEPLOY 0123456')

    // Still disabled here, because this bundle reports no commit in a test build, and the two
    // sides disagreeing is exactly when a redeploy must not be offered.
    expect(button).toBeDisabled()
  })

  it('surfaces a status failure without rendering a broken page', async () => {
    mockFetchStatus.mockRejectedValue(new Error('Software Factory is unavailable'))

    render(<SoftwareFactoryAdmin />)

    expect(await screen.findByText('Software Factory is unavailable')).toBeInTheDocument()
  })
})
