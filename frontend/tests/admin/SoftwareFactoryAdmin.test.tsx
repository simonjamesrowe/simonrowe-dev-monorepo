import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SoftwareFactoryAdmin } from '../../src/pages/admin/SoftwareFactoryAdmin'

vi.mock('../../src/services/softwareFactoryApi', () => ({
  fetchSoftwareFactoryStatus: vi.fn(),
  fetchFactoryFlow: vi.fn(),
  fetchFactoryFlowDetail: vi.fn(),
  fetchRunProgress: vi.fn(),
  startCodeReview: vi.fn(),
  startFeedback: vi.fn(),
  startVulnerabilityScan: vi.fn(),
  startPlatformBackup: vi.fn(),
  startLogWatchScan: vi.fn(),
  startDeploy: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  fetchFactoryFlow,
  fetchFactoryFlowDetail,
  fetchRunProgress,
  fetchSoftwareFactoryStatus,
  startCodeReview,
  startLogWatchScan,
  startPlatformBackup,
  startVulnerabilityScan,
  type FactoryFlow,
  type FactoryFlowNode,
  type FactoryModuleStatus,
  type SoftwareFactoryStatus,
} from '../../src/services/softwareFactoryApi'
import { useAuth } from '../../src/auth/useAuth'

const mockFetchStatus = vi.mocked(fetchSoftwareFactoryStatus)
const mockFetchFlow = vi.mocked(fetchFactoryFlow)
const mockFetchFlowDetail = vi.mocked(fetchFactoryFlowDetail)
const mockFetchProgress = vi.mocked(fetchRunProgress)
const mockStartReview = vi.mocked(startCodeReview)
const mockStartScan = vi.mocked(startVulnerabilityScan)
const mockStartBackup = vi.mocked(startPlatformBackup)
const mockStartLogScan = vi.mocked(startLogWatchScan)
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
    repository: 'simonjamesrowe/simonrowe-dev-monorepo',
    factoryReachable: true,
    deployerReachable: true,
    modules: [
      module('codereview'),
      module('feedback'),
      module('cvefix'),
      module('deploy'),
      module('linear'),
      module('platformbackup'),
      module('logwatch'),
    ],
    ...overrides,
  }
}

/** Labels chosen so each node's accessible name is unambiguous in the tests below. */
const NODE_LABELS: Record<string, string> = {
  linear: 'Linear',
  build: 'Build agent',
  'pull-request': 'Pull request',
  codereview: 'Code review',
  main: 'Main',
  deploy: 'Deploy',
  production: 'Production',
  logwatch: 'Log watch',
  cvefix: 'Vulnerability scan',
  feedback: 'Review feedback',
  'agent-setup': 'Agent setup',
  platformbackup: 'Platform backup',
}

function flowNode(key: string, overrides: Partial<FactoryFlowNode> = {}): FactoryFlowNode {
  return {
    key,
    kind: 'MODULE',
    band: 'OBSERVE',
    label: NODE_LABELS[key] ?? key,
    counts: { inFlight: 0, ok24h: 0, failed24h: 0 },
    health: 'READY',
    diagnostic: null,
    ...overrides,
  }
}

function flow(overrides: Partial<Record<string, Partial<FactoryFlowNode>>> = {}): FactoryFlow {
  return {
    fetchedAt: '2026-09-04T10:00:00Z',
    nodes: Object.keys(NODE_LABELS).map((key) => flowNode(key, overrides[key])),
    edges: [],
  }
}

function renderConsoleWithFlow() {
  render(<SoftwareFactoryAdmin />)
}

/** Opens the drawer for the node whose accessible name matches `name`, and returns it. */
async function openDrawer(name: string | RegExp) {
  await userEvent.click(await screen.findByRole('button', { name }))
  return screen.getByRole('dialog')
}

describe('SoftwareFactoryAdmin', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAuth.mockReturnValue({ getAccessToken } as unknown as ReturnType<typeof useAuth>)
    mockFetchStatus.mockResolvedValue(status())
    mockFetchFlow.mockResolvedValue(flow())
    mockFetchFlowDetail.mockResolvedValue({ nodeKey: 'logwatch', items: [] })
  })

  it('replaces the module rail with the flow graph', async () => {
    // Two representations of the same fact on one page is what made "keep the cards" unattractive.
    renderConsoleWithFlow()
    expect(await screen.findByRole('button', { name: /Log watch/ })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Software Factory modules' })).not.toBeInTheDocument()
  })

  it('opens the drawer with that module\'s actions when a node is selected', async () => {
    renderConsoleWithFlow()
    await userEvent.click(await screen.findByRole('button', { name: /Log watch/ }))
    const drawer = screen.getByRole('dialog')
    expect(within(drawer).getByRole('button', { name: 'Scan logs now' })).toBeInTheDocument()
  })

  it('fetches the selected node\'s recent work and renders it in the drawer', async () => {
    mockFetchFlowDetail.mockResolvedValue({
      nodeKey: 'logwatch',
      items: [{ id: 'logwatch-9', title: 'logwatch-9', status: 'COMPLETED', at: null, url: null }],
    })
    renderConsoleWithFlow()

    const drawer = await openDrawer(/Log watch/)

    expect(mockFetchFlowDetail).toHaveBeenCalledWith(getAccessToken, 'logwatch')
    expect(await within(drawer).findByText('logwatch-9')).toBeInTheDocument()
  })

  it('shows the drawer as loading before the node\'s recent work arrives', async () => {
    let resolveDetail: (value: { nodeKey: string; items: [] }) => void = () => {}
    mockFetchFlowDetail.mockReturnValue(new Promise((resolve) => { resolveDetail = resolve }))
    renderConsoleWithFlow()

    const drawer = await openDrawer(/Log watch/)
    expect(within(drawer).getByText(/Loading/i)).toBeInTheDocument()

    await act(async () => resolveDetail({ nodeKey: 'logwatch', items: [] }))
    expect(await within(drawer).findByText(/No runs in the last 30 days/i)).toBeInTheDocument()
  })

  it('reports each container reachability in text, not only colour', async () => {
    mockFetchStatus.mockResolvedValue(status({ deployerReachable: false }))

    renderConsoleWithFlow()

    expect(await screen.findByText(/Factory reachable/)).toBeInTheDocument()
    expect(screen.getByText(/Deployer unreachable/)).toBeInTheDocument()
  })

  it('shows why an enabled module still cannot work', async () => {
    // Enabled with no credential is neither off nor healthy, and it was invisible before.
    // The diagnostic and the missing-prerequisite are deliberately different strings here: a
    // fixture that reuses the same text for both cannot tell whether the diagnostic itself is
    // rendered, or only the (also-rendered) missing-prerequisites list.
    mockFetchStatus.mockResolvedValue(status({
      modules: [module('cvefix', {
        ready: false,
        missingPrerequisites: ['Dependency-Track API key is not set'],
        diagnostic: 'Enabled but not usable: no Dependency-Track credential is configured',
      })],
    }))

    renderConsoleWithFlow()
    const drawer = await openDrawer(/Vulnerability scan/)

    expect(within(drawer).getByText('Dependency-Track API key is not set')).toBeInTheDocument()
    expect(within(drawer).getByText(
      'Enabled but not usable: no Dependency-Track credential is configured',
    )).toBeInTheDocument()
  })

  it('disables an action whose module is not ready', async () => {
    mockFetchStatus.mockResolvedValue(status({
      modules: [module('cvefix', { ready: false, diagnostic: 'Disabled by configuration' })],
    }))

    renderConsoleWithFlow()
    const drawer = await openDrawer(/Vulnerability scan/)

    expect(within(drawer).getByRole('button', { name: /Scan now/ })).toBeDisabled()
  })

  it('offers a code review trigger with its pull request field', async () => {
    // The webhook cannot replay a review — the workflow id embeds the head SHA under
    // REJECT_DUPLICATE — so this is the only way to re-drive one that failed or never arrived.
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Code review/)

    expect(within(drawer).getByRole('button', { name: /Review and comment/ })).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: /Dry-run review/ })).toBeInTheDocument()
    expect(within(drawer).getByLabelText(/Pull request to review/)).toBeInTheDocument()
  })

  it('says what the field wants, and which repository it targets', async () => {
    // "Is it the URL or the number?" is the first question the field provokes, and the actions
    // always target the server-configured repository regardless of what a pasted URL says.
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Code review/)

    expect(within(drawer).getByPlaceholderText('130 or a pull request URL')).toBeInTheDocument()
    expect(within(drawer).getByText(/Number or pull request URL/)).toBeInTheDocument()
    expect(within(drawer).getByText(/simonjamesrowe\/simonrowe-dev-monorepo/)).toBeInTheDocument()
  })

  it('accepts a pasted pull request URL and confirms what it read', async () => {
    mockStartReview.mockResolvedValue({
      workflowId: 'code-review-130-uuid', runId: null, detail: 'accepted',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Code review/)

    await userEvent.type(
      within(drawer).getByLabelText(/Pull request to review/),
      'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/pull/130/files',
    )

    expect(within(drawer).getByText('simonjamesrowe/simonrowe-dev-monorepo#130')).toBeInTheDocument()

    await userEvent.click(within(drawer).getByRole('button', { name: /Dry-run review/ }))
    expect(mockStartReview).toHaveBeenCalledWith(getAccessToken, 130, false)
  }, 15000)

  it('refuses input it cannot read rather than sending a wrong number', async () => {
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Code review/)

    await userEvent.type(within(drawer).getByLabelText(/Pull request to review/), 'main')

    expect(within(drawer).getByText('Not a pull request number or URL')).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: /Dry-run review/ })).toBeDisabled()
    expect(within(drawer).getByRole('button', { name: /Review and comment/ })).toBeDisabled()
  }, 10000)

  it('keeps both review buttons disabled until a pull request is named', async () => {
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Code review/)

    expect(within(drawer).getByRole('button', { name: /Review and comment/ })).toBeDisabled()
    expect(within(drawer).getByRole('button', { name: /Dry-run review/ })).toBeDisabled()
  })

  it('publishes only on the explicit review button', async () => {
    mockStartReview.mockResolvedValue({
      workflowId: 'code-review-130-uuid', runId: null, detail: 'accepted',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Code review/)

    await userEvent.type(within(drawer).getByLabelText(/Pull request to review/), '130')
    await userEvent.click(within(drawer).getByRole('button', { name: /Dry-run review/ }))
    expect(mockStartReview).toHaveBeenCalledWith(getAccessToken, 130, false)

    await userEvent.click(within(drawer).getByRole('button', { name: /Review and comment/ }))
    expect(mockStartReview).toHaveBeenCalledWith(getAccessToken, 130, true)
  }, 10000)

  it('disables the review trigger when nothing polls the review queue', async () => {
    mockFetchStatus.mockResolvedValue(status({
      modules: [module('codereview', {
        ready: false, diagnostic: 'Required Temporal poller is missing',
      })],
    }))
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Code review/)

    expect(within(drawer).getByRole('button', { name: /Review and comment/ })).toBeDisabled()
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
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Vulnerability scan/)

    await userEvent.click(within(drawer).getByRole('button', { name: /Scan now/ }))

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
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Vulnerability scan/)

    await userEvent.click(within(drawer).getByRole('button', { name: /Scan now/ }))

    expect(await screen.findByText('Failed', {}, { timeout: 5000 })).toBeInTheDocument()
  }, 10000)

  it('shows a safe message when an action is refused', async () => {
    mockStartScan.mockRejectedValue(new Error('That run is already in progress'))
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Vulnerability scan/)

    await userEvent.click(within(drawer).getByRole('button', { name: /Scan now/ }))

    expect(await screen.findByText('That run is already in progress')).toBeInTheDocument()
  })

  it('requires a second click before a real backup', async () => {
    // A dry run is one click; spending a Google Drive archive slot is two.
    mockStartBackup.mockResolvedValue({
      workflowId: 'platform-backup-manual', runId: 'run-2', detail: 'accepted',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Platform backup/)

    await userEvent.click(within(drawer).getByRole('button', { name: 'Back up now' }))
    expect(mockStartBackup).not.toHaveBeenCalled()

    await userEvent.click(within(drawer).getByRole('button', { name: 'Confirm real backup' }))
    expect(mockStartBackup).toHaveBeenCalledWith(getAccessToken, false)
  })

  it('starts a dry run on the first click', async () => {
    mockStartBackup.mockResolvedValue({
      workflowId: 'platform-backup-manual', runId: 'run-2', detail: 'Dry run accepted',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Platform backup/)

    await userEvent.click(within(drawer).getByRole('button', { name: 'Dry run' }))

    expect(mockStartBackup).toHaveBeenCalledWith(getAccessToken, true)
  })

  it('starts a dry log scan without filing anything', async () => {
    mockStartLogScan.mockResolvedValue({
      workflowId: 'logwatch-manual-1', runId: 'run-9', detail: 'Dry-run log scan accepted',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Log watch/)

    await userEvent.click(within(drawer).getByRole('button', { name: 'Dry run scan' }))

    expect(mockStartLogScan).toHaveBeenCalledWith(getAccessToken, true)
  })

  it('starts a real log scan on a single click, like the vulnerability scan', async () => {
    mockStartLogScan.mockResolvedValue({
      workflowId: 'logwatch-manual-2', runId: 'run-10', detail: 'Log scan accepted',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Log watch/)

    // No confirmation step, deliberately: filing a Linear ticket is reversible by cancelling it,
    // unlike the platform backup's upload. It matches the CVE scan, which files the same way.
    await userEvent.click(within(drawer).getByRole('button', { name: /Scan logs now/ }))

    expect(mockStartLogScan).toHaveBeenCalledWith(getAccessToken, false)
  })

  it('disables both log-scan controls when log watch is not ready', async () => {
    mockFetchStatus.mockResolvedValue(status({
      modules: [
        module('codereview'), module('feedback'), module('cvefix'), module('deploy'),
        module('linear'), module('platformbackup'),
        module('logwatch', { ready: false, missingPrerequisites: ['GRAFANA_CLOUD_API_KEY is not set'] }),
      ],
    }))
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Log watch/)

    expect(within(drawer).getByRole('button', { name: 'Dry run scan' })).toBeDisabled()
    expect(within(drawer).getByRole('button', { name: /Scan logs now/ })).toBeDisabled()
  })

  it('labels the log-scan buttons distinctly from the other modules', async () => {
    // "Dry run" alone collides with platform backup and "Scan now" with the vulnerability scan.
    // The accessible name is all a screen reader gets - the panel heading that separates them
    // visually is not part of it - so each must be unique on its own.
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Log watch/)

    expect(within(drawer).getByRole('button', { name: 'Dry run scan' })).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Scan logs now' })).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Dry run' })).not.toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: /^Scan now$/ })).not.toBeInTheDocument()
  })

  it('keeps the redeploy button disabled until the phrase matches exactly', async () => {
    renderConsoleWithFlow()
    const drawer = await openDrawer(/^Deploy /)

    const button = within(drawer).getByRole('button', { name: /Redeploy 0123456/ })
    expect(button).toBeDisabled()

    await userEvent.type(within(drawer).getByLabelText(/Confirmation phrase/), 'REDEPLOY 0123456')

    // Still disabled here, because this bundle reports no commit in a test build, and the two
    // sides disagreeing is exactly when a redeploy must not be offered.
    expect(button).toBeDisabled()
  })

  it('surfaces a status failure without rendering a broken page', async () => {
    mockFetchStatus.mockRejectedValue(new Error('Software Factory is unavailable'))

    renderConsoleWithFlow()

    expect(await screen.findByText('Software Factory is unavailable')).toBeInTheDocument()
  })

  it('shows counts unknown, never zero, for a node whose source could not be read', async () => {
    // Null means the source could not be read; zero means nothing happened. They must not read
    // the same, or an operator investigating an outage is sent to the wrong place.
    mockFetchFlow.mockResolvedValue(flow({
      logwatch: { counts: null, health: 'UNAVAILABLE' },
    }))
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Log watch/)

    expect(within(drawer).getByText(/counts unknown/i)).toBeInTheDocument()
  })

  it('explains why the build agent node has no worker of its own', async () => {
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Build agent/)

    expect(within(drawer).getByText(/not yet running/i)).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: /Dry run/ })).not.toBeInTheDocument()
  })

  it('polls the flow at the chosen interval and stops when switched off', async () => {
    // userEvent.click deadlocks under vi.useFakeTimers() in this environment even with
    // advanceTimers configured (reproduced in isolation, independent of this component) -
    // fireEvent.click is a plain, synchronous DOM dispatch and sidesteps that without weakening
    // what this test proves: a leaked interval is invisible until it is hammering an endpoint,
    // so "stops when switched off" is the assertion worth keeping.
    renderConsoleWithFlow()
    await screen.findByRole('button', { name: /Log watch/ })
    const initial = mockFetchFlow.mock.calls.length

    vi.useFakeTimers()

    fireEvent.click(screen.getByRole('radio', { name: '15s' }))
    await act(() => vi.advanceTimersByTimeAsync(15_000))
    expect(mockFetchFlow.mock.calls.length).toBe(initial + 1)

    fireEvent.click(screen.getByRole('radio', { name: 'Off' }))
    await act(() => vi.advanceTimersByTimeAsync(60_000))
    expect(mockFetchFlow.mock.calls.length).toBe(initial + 1)

    vi.useRealTimers()
  })
})
