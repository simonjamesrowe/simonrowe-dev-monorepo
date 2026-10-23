import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SoftwareFactoryAdmin } from '../../src/pages/admin/SoftwareFactoryAdmin'

vi.mock('../../src/services/softwareFactoryApi', async (importOriginal) => ({
  // Pure, so the real one: the console's hint and buttons are only as right as this parse.
  parseTicketReference: (await importOriginal<
    typeof import('../../src/services/softwareFactoryApi')>()).parseTicketReference,
  startTicketAgent: vi.fn(),
  stopTicketAgent: vi.fn(),
  setTicketIntake: vi.fn(),
  fetchSoftwareFactoryStatus: vi.fn(),
  fetchFactoryFlow: vi.fn(),
  fetchFactoryFlowDetail: vi.fn(),
  fetchRunProgress: vi.fn(),
  startCodeReview: vi.fn(),
  startFeedback: vi.fn(),
  startVulnerabilityScan: vi.fn(),
  startPlatformBackup: vi.fn(),
  startLogWatchScan: vi.fn(),
  startStaticAnalysisScan: vi.fn(),
  startDeploy: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

// Mutable so a test can stand in for a production bundle; a test build reports no commit.
const bundle = vi.hoisted(() => ({
  commit: 'unknown',
  buildTime: null as string | null,
}))

vi.mock('../../src/config/version', () => ({
  get FRONTEND_COMMIT() {
    return bundle.commit
  },
  get FRONTEND_BUILD_TIME() {
    return bundle.buildTime
  },
}))

import {
  fetchFactoryFlow,
  fetchFactoryFlowDetail,
  fetchRunProgress,
  fetchSoftwareFactoryStatus,
  setTicketIntake,
  startCodeReview,
  startLogWatchScan,
  startStaticAnalysisScan,
  startTicketAgent,
  stopTicketAgent,
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
const mockStartSonarScan = vi.mocked(startStaticAnalysisScan)
const mockStartTicket = vi.mocked(startTicketAgent)
const mockStopTicket = vi.mocked(stopTicketAgent)
const mockSetIntake = vi.mocked(setTicketIntake)
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

function intakeSchedule(paused: boolean) {
  return {
    scheduleId: 'ticket-agent-intake', exists: true, paused, overlapPolicy: 'SKIP',
    previousActionAt: null, nextActionAt: null, runningActions: 0, diagnostic: null,
  }
}

function status(overrides: Partial<SoftwareFactoryStatus> = {}): SoftwareFactoryStatus {
  return {
    fetchedAt: '2026-08-28T09:00:00Z',
    backendCommit: SHA,
    serviceCommits: [
      { service: 'backend', commit: SHA, commitTime: '2026-10-01T09:00:00Z' },
      { service: 'software-factory', commit: SHA, commitTime: '2026-10-01T09:00:00Z' },
    ],
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
      module('staticanalysis'),
      module('ticketagent', { schedule: intakeSchedule(true) }),
    ],
    ...overrides,
  }
}

/** Labels chosen so each node's accessible name is unambiguous in the tests below. */
const NODE_LABELS: Record<string, string> = {
  linear: 'Linear',
  build: 'Ticket agent',
  'pull-request': 'Pull request',
  codereview: 'Code review',
  main: 'Main',
  deploy: 'Deploy',
  production: 'Production',
  logwatch: 'Log watch',
  cvefix: 'Vulnerability scan',
  staticanalysis: 'Static analysis',
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

  it('shows an error, not a permanent spinner, when the recent-work fetch is rejected', async () => {
    // A failed fetch must render as a failure: leaving the drawer on "Loading…" forever would
    // be indistinguishable from a fetch that is merely slow.
    mockFetchFlowDetail.mockRejectedValue(new Error('Request failed (500).'))
    renderConsoleWithFlow()

    const drawer = await openDrawer(/Log watch/)

    expect(await within(drawer).findByText('Request failed (500).')).toBeInTheDocument()
    expect(within(drawer).queryByText(/^Loading/i)).not.toBeInTheDocument()
  })

  it('marks the page content inert while a drawer is open, and live again once it closes', async () => {
    // aria-modal="true" on the drawer is honest for the keyboard Tab trap, but a screen reader's
    // own virtual cursor does not go through Tab and can still browse into the background, and a
    // sighted keyboard user who clicks into that background can Tab their way back out of the
    // trap. `inert` on the rest of the page closes both residuals in one attribute.
    const { container } = render(<SoftwareFactoryAdmin />)
    await screen.findByRole('button', { name: /Log watch/ })
    const body = container.querySelector('.factory-console__body')
    expect(body).not.toHaveAttribute('inert')

    const drawer = await openDrawer(/Log watch/)
    expect(body).toHaveAttribute('inert')

    await userEvent.click(within(drawer).getByRole('button', { name: /close/i }))
    expect(body).not.toHaveAttribute('inert')
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

  it('says that publishing a review can arm auto-merge, and a dry run only reports it', async () => {
    // A manual review now does more than comment: it is the fallback when the webhook never
    // arrived, so it arms auto-merge exactly as the webhook's review would. The panel has to say
    // so before the button is pressed, not after.
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Code review/)

    expect(within(drawer).getByText(/reports whether it would arm auto-merge/)).toBeInTheDocument()
    expect(within(drawer).getByText(/arms auto-merge when every changed path allows it/))
      .toBeInTheDocument()
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

  it('starts a dry static-analysis scan without filing anything', async () => {
    mockStartSonarScan.mockResolvedValue({
      workflowId: 'static-analysis-manual-1', runId: 'run-11', detail: 'Dry-run Sonar scan accepted',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Static analysis/)

    await userEvent.click(within(drawer).getByRole('button', { name: 'Dry run Sonar scan' }))

    expect(mockStartSonarScan).toHaveBeenCalledWith(getAccessToken, true)
  })

  it('starts a real static-analysis scan on a single click, like log watch', async () => {
    mockStartSonarScan.mockResolvedValue({
      workflowId: 'static-analysis-manual-2', runId: 'run-12', detail: 'Sonar scan accepted',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Static analysis/)

    await userEvent.click(within(drawer).getByRole('button', { name: /Read Sonar now/ }))

    expect(mockStartSonarScan).toHaveBeenCalledWith(getAccessToken, false)
  })

  it('disables both static-analysis controls when the module is not ready', async () => {
    mockFetchStatus.mockResolvedValue(status({
      modules: [
        module('codereview'), module('feedback'), module('cvefix'), module('deploy'),
        module('linear'), module('platformbackup'), module('logwatch'),
        module('staticanalysis', { ready: false, missingPrerequisites: ['SONAR_TOKEN is not set'] }),
      ],
    }))
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Static analysis/)

    expect(within(drawer).getByRole('button', { name: 'Dry run Sonar scan' })).toBeDisabled()
    expect(within(drawer).getByRole('button', { name: /Read Sonar now/ })).toBeDisabled()
    expect(mockStartSonarScan).not.toHaveBeenCalled()
  })

  it('labels the static-analysis buttons distinctly from every other module', async () => {
    // Same reason as the log-scan labels: the accessible name is all a screen reader gets.
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Static analysis/)

    expect(within(drawer).getByRole('button', { name: 'Dry run Sonar scan' })).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Read Sonar now' })).toBeInTheDocument()
    for (const taken of ['Dry run', 'Dry run scan', 'Scan now', 'Scan logs now', 'Dry run ticket']) {
      expect(within(drawer).queryByRole('button', { name: taken })).not.toBeInTheDocument()
    }
  })

  it('offers no redeploy while this bundle reports no commit', async () => {
    renderConsoleWithFlow()
    const drawer = await openDrawer(/^Deploy /)

    const button = within(drawer).getByRole('button', { name: /Redeploy unknown/ })
    await userEvent.type(within(drawer).getByLabelText(/Confirmation phrase/), 'REDEPLOY unknown')

    // A test build reports no commit, so the newest of the three cannot be worked out, and a
    // redeploy that might roll a service back must not be offered.
    expect(button).toBeDisabled()
  })

  it('redeploys the newest commit a service was built from, once the phrase names it', async () => {
    // A frontend-only merge: the bundle is newer than the backend and the factory.
    bundle.commit = 'fedcba9876543210fedcba9876543210fedcba98'
    bundle.buildTime = '2026-10-03T09:00:00Z'
    try {
      renderConsoleWithFlow()
      const drawer = await openDrawer(/^Deploy /)

      const button = within(drawer).getByRole('button', { name: /Redeploy fedcba9/ })
      expect(button).toBeDisabled()
      expect(within(drawer).getByText(/the frontend's/)).toBeInTheDocument()

      await userEvent.type(within(drawer).getByLabelText(/Confirmation phrase/), 'REDEPLOY 0123456')
      expect(button).toBeDisabled()

      await userEvent.clear(within(drawer).getByLabelText(/Confirmation phrase/))
      await userEvent.type(within(drawer).getByLabelText(/Confirmation phrase/), 'REDEPLOY fedcba9')
      expect(button).toBeEnabled()
    } finally {
      bundle.commit = 'unknown'
      bundle.buildTime = null
    }
  })

  it('surfaces a status failure without rendering a broken page', async () => {
    mockFetchStatus.mockRejectedValue(new Error('Software Factory is unavailable'))

    renderConsoleWithFlow()

    expect(await screen.findByText('Software Factory is unavailable')).toBeInTheDocument()
  })

  it('renders the full diagram with every node unavailable when the factory cannot be reached', async () => {
    // The backend degrades rather than 500s when software-factory itself is unreachable: every
    // node comes back UNAVAILABLE with null counts, borrowing only the deployer's topology shape.
    // The one page whose purpose is showing factory health must not go blank at exactly the
    // moment an operator needs it — it must still draw all twelve buttons, not an error banner.
    const everyNodeUnavailable = Object.fromEntries(
      Object.keys(NODE_LABELS).map((key) => [key, {
        counts: null,
        health: 'UNAVAILABLE' as const,
        diagnostic: 'Software Factory could not be reached',
      }]),
    )
    mockFetchFlow.mockResolvedValue(flow(everyNodeUnavailable))

    renderConsoleWithFlow()

    for (const label of Object.values(NODE_LABELS)) {
      expect(await screen.findByRole('button', { name: new RegExp(label) })).toBeInTheDocument()
    }
    expect(screen.getAllByText('counts unknown').length).toBeGreaterThan(0)
    expect(screen.queryByText('Request failed (500).')).not.toBeInTheDocument()
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

  it('hands a ticket to the agent only once the field names one', async () => {
    mockStartTicket.mockResolvedValue({
      workflowId: 'ticket-agent-SIM-107', runId: null, detail: 'Handed SIM-107 to the agent',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Ticket agent/)
    const start = within(drawer).getByRole('button', { name: 'Start agent' })
    const dryRun = within(drawer).getByRole('button', { name: 'Dry run ticket' })

    expect(start).toBeDisabled()
    expect(dryRun).toBeDisabled()
    await userEvent.type(within(drawer).getByLabelText(/Linear ticket/), 'not a ticket')
    expect(within(drawer).getByText('Not a Linear ticket identifier or URL')).toBeInTheDocument()
    expect(start).toBeDisabled()

    await userEvent.clear(within(drawer).getByLabelText(/Linear ticket/))
    await userEvent.type(
      within(drawer).getByLabelText(/Linear ticket/),
      'https://linear.app/simonrowedev/issue/SIM-107/error-in-backend')
    expect(within(drawer).getByText('SIM-107')).toBeInTheDocument()
    await userEvent.click(start)

    expect(mockStartTicket).toHaveBeenCalledWith(getAccessToken, 'SIM-107', false)
  })

  it('dry-runs a ticket without handing it over', async () => {
    mockStartTicket.mockResolvedValue({
      workflowId: 'ticket-agent-dry-run-SIM-107', runId: null, detail: 'Dry-running SIM-107',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Ticket agent/)

    await userEvent.type(within(drawer).getByLabelText(/Linear ticket/), 'sim-107')
    await userEvent.click(within(drawer).getByRole('button', { name: 'Dry run ticket' }))

    expect(mockStartTicket).toHaveBeenCalledWith(getAccessToken, 'SIM-107', true)
  })

  it('keeps the ticket buttons disabled while the agent is not ready', async () => {
    mockFetchStatus.mockResolvedValue(status({
      modules: [module('ticketagent', { ready: false, schedule: intakeSchedule(true) })],
    }))
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Ticket agent/)

    await userEvent.type(within(drawer).getByLabelText(/Linear ticket/), 'SIM-107')
    expect(within(drawer).getByRole('button', { name: 'Start agent' })).toBeDisabled()
    expect(within(drawer).getByRole('button', { name: 'Dry run ticket' })).toBeDisabled()
  })

  it('names the ticket buttons distinctly from every other dry run', async () => {
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Ticket agent/)

    expect(within(drawer).queryByRole('button', { name: 'Dry run' })).not.toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Dry run ticket' })).toBeInTheDocument()
  })

  it('offers Send to agent and Retry on tickets, and nothing on one the agent holds', async () => {
    mockFetchFlowDetail.mockResolvedValue({
      nodeKey: 'linear',
      items: [
        { id: 'SIM-1', title: 'a', status: 'TRIAGE', at: null, url: null, agent: null },
        { id: 'SIM-2', title: 'b', status: 'STARTED', at: null, url: null, agent: 'with-agent' },
        { id: 'SIM-3', title: 'c', status: 'UNSTARTED', at: null, url: null, agent: 'stuck' },
      ],
    })
    mockStartTicket.mockResolvedValue({
      workflowId: 'ticket-agent-SIM-1', runId: null, detail: 'Handed SIM-1 to the agent',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/^Linear/)

    await userEvent.click(await within(drawer).findByRole('button', { name: 'Send to agent SIM-1' }))
    expect(within(drawer).queryByRole('button', { name: /SIM-2/ })).not.toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Retry SIM-3' })).toBeInTheDocument()
    expect(mockStartTicket).toHaveBeenCalledWith(getAccessToken, 'SIM-1', false)
  })

  it('stops a live run from the agent\'s run list', async () => {
    mockFetchFlowDetail.mockResolvedValue({
      nodeKey: 'build',
      items: [
        { id: 'ticket-agent-SIM-107', title: 'SIM-107 · ERROR', status: 'Attempt 2 of 3',
          at: null, url: null, agent: 'running' },
        { id: 'ticket-agent-SIM-90', title: 'SIM-90 · WARN', status: 'Merged',
          at: null, url: null, agent: null },
      ],
    })
    mockStopTicket.mockResolvedValue({
      workflowId: 'ticket-agent-SIM-107', runId: null, detail: 'Stopping SIM-107',
    })
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Ticket agent/)

    await userEvent.click(await within(drawer).findByRole('button', { name: 'Stop SIM-107' }))
    expect(within(drawer).queryByRole('button', { name: 'Stop SIM-90' })).not.toBeInTheDocument()
    expect(mockStopTicket).toHaveBeenCalledWith(getAccessToken, 'SIM-107')
    expect(await screen.findByText('Stopping SIM-107')).toBeInTheDocument()
  })

  it('resumes a paused intake, and offers to pause a running one', async () => {
    mockSetIntake.mockResolvedValue({
      workflowId: 'ticket-agent-intake', runId: null, detail: 'Ticket intake resumed',
    })
    renderConsoleWithFlow()

    await userEvent.click(await screen.findByRole('button', { name: 'Resume intake' }))
    expect(mockSetIntake).toHaveBeenCalledWith(getAccessToken, false)

    mockFetchStatus.mockResolvedValue(status({
      modules: [module('ticketagent', { schedule: intakeSchedule(false) })],
    }))
    await userEvent.click(screen.getByRole('button', { name: /Refresh/ }))
    expect(await screen.findByRole('button', { name: 'Pause intake' })).toBeInTheDocument()
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

  it('does not queue a second poll while the first is still in flight, and releases once it settles', async () => {
    // Mutation-tested: replacing the guard with `if (false && loadInFlight.current) return`
    // must fail this test - that is the entire reason the ref exists, and nothing else in this
    // file would have caught it (verified by hand; see the task-9 fix report).
    renderConsoleWithFlow()
    await screen.findByRole('button', { name: /Log watch/ })
    const initial = mockFetchFlow.mock.calls.length

    let resolveFlow: (value: FactoryFlow) => void = () => {}
    mockFetchFlow.mockImplementation(
      () => new Promise<FactoryFlow>((resolve) => { resolveFlow = resolve }),
    )

    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('radio', { name: '15s' }))

    // Two ticks elapse while the first request is still pending. Without the guard, the second
    // tick would fire another request behind the first.
    await act(() => vi.advanceTimersByTimeAsync(30_000))
    expect(mockFetchFlow.mock.calls.length).toBe(initial + 1)

    // Let the pending request settle, which is what releases the guard.
    mockFetchFlow.mockResolvedValue(flow())
    await act(async () => {
      resolveFlow(flow())
      // Two hops: one for Promise.all's own resolution, one for the `await` in `load` that is
      // suspended on it.
      await Promise.resolve()
      await Promise.resolve()
    })

    // A further tick now fetches again - the guard is a floor on spacing, not a permanent latch.
    await act(() => vi.advanceTimersByTimeAsync(15_000))
    expect(mockFetchFlow.mock.calls.length).toBe(initial + 2)

    vi.useRealTimers()
  })

  it('re-fetches an open drawer\'s run list on the same tick as the flow refresh', async () => {
    // Previously useFlowDetail depended only on [nodeKey, getAccessToken], so during a deploy
    // with the drawer open the counts above (driven by `flow`, which DOES refresh) kept moving
    // while the run list underneath froze at the moment the drawer was opened - the primary use
    // case the refresh control exists for. Riding the same tick as `load()`, not a second timer.
    renderConsoleWithFlow()
    const drawer = await openDrawer(/Log watch/)
    const initialDetailCalls = mockFetchFlowDetail.mock.calls.length

    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('radio', { name: '15s' }))
    await act(() => vi.advanceTimersByTimeAsync(15_000))

    expect(mockFetchFlowDetail.mock.calls.length).toBe(initialDetailCalls + 1)
    // A refresh-triggered re-fetch of the SAME node must not flash "Loading…" over an
    // already-rendered run list.
    expect(within(drawer).queryByText(/^Loading/i)).not.toBeInTheDocument()

    vi.useRealTimers()
  })

  it('stops polling once the console unmounts', async () => {
    const { unmount } = render(<SoftwareFactoryAdmin />)
    await screen.findByRole('button', { name: /Log watch/ })
    const initial = mockFetchFlow.mock.calls.length

    vi.useFakeTimers()
    fireEvent.click(screen.getByRole('radio', { name: '15s' }))
    unmount()

    await act(() => vi.advanceTimersByTimeAsync(60_000))
    expect(mockFetchFlow.mock.calls.length).toBe(initial)

    vi.useRealTimers()
  })
})
