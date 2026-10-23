import { API_BASE_URL } from '../config/api'

const FACTORY_URL = `${API_BASE_URL}/api/admin/software-factory`

export type GetAccessToken = () => Promise<string>

export interface FactoryScheduleStatus {
  scheduleId: string
  exists: boolean
  paused: boolean | null
  overlapPolicy: string | null
  previousActionAt: string | null
  nextActionAt: string | null
  runningActions: number
  diagnostic: string | null
}

export interface FactoryModuleStatus {
  key:
    | 'codereview'
    | 'feedback'
    | 'cvefix'
    | 'deploy'
    | 'linear'
    | 'platformbackup'
    | 'logwatch'
    | 'staticanalysis'
    | 'ticketagent'
  displayName: string
  /** null when the container that owns this module could not be asked for its flag. */
  configured: boolean | null
  taskQueue: string
  workflowPollers: number | null
  activityPollers: number | null
  trigger: string
  schedule: FactoryScheduleStatus | null
  missingPrerequisites: string[]
  ready: boolean
  diagnostic: string | null
}

/** The commit one deployed service was built from. */
export interface ServiceCommit {
  service: string
  commit: string
  commitTime: string
}

export interface SoftwareFactoryStatus {
  fetchedAt: string
  backendCommit: string
  /** The backend's and software-factory's commits, each only when known. */
  serviceCommits: ServiceCommit[]
  /** The repository the pull-request actions target, fixed server-side. */
  repository: string
  factoryReachable: boolean
  deployerReachable: boolean
  modules: FactoryModuleStatus[]
}

export interface FactoryRunAccepted {
  workflowId: string
  runId: string | null
  detail: string | null
}

export interface FactoryRunProgress {
  workflowId: string
  runId: string | null
  executionStatus: string
  phase: string | null
  detail: string | null
  terminal: boolean
}

async function request<T>(
  getAccessToken: GetAccessToken,
  path: string,
  options?: RequestInit,
): Promise<T> {
  const token = await getAccessToken()
  const response = await fetch(`${FACTORY_URL}${path}`, {
    ...options,
    headers: {
      ...options?.headers,
      Authorization: `Bearer ${token}`,
      ...(options?.body ? { 'Content-Type': 'application/json' } : {}),
    },
  })
  if (!response.ok) {
    let message = `Request failed (${response.status}).`
    try {
      const payload = await response.json() as { message?: string; detail?: string }
      message = payload.message || payload.detail || message
    } catch {
      // Keep the safe status fallback when the backend did not return JSON.
    }
    throw new Error(message)
  }
  return response.json() as Promise<T>
}

export const fetchSoftwareFactoryStatus = (getAccessToken: GetAccessToken) =>
  request<SoftwareFactoryStatus>(getAccessToken, '/status')

/**
 * Reviews a pull request on demand.
 *
 * A dry run reviews and posts nothing at all — not even a failure notice — so the console's run
 * progress is the only place its outcome appears. That is safe to offer precisely because the
 * page now follows runs; it would have been a dead end before.
 */
export const startCodeReview = (
  getAccessToken: GetAccessToken,
  pullNumber: number,
  publish: boolean,
) => request<FactoryRunAccepted>(getAccessToken, '/reviews', {
  method: 'POST',
  body: JSON.stringify({ pullNumber, publish }),
})

export const startFeedback = (getAccessToken: GetAccessToken, pullNumber: number) =>
  request<FactoryRunAccepted>(getAccessToken, '/feedback', {
    method: 'POST',
    body: JSON.stringify({ pullNumber }),
  })

export const startVulnerabilityScan = (getAccessToken: GetAccessToken) =>
  request<FactoryRunAccepted>(getAccessToken, '/vulnerability-scans', { method: 'POST' })

export const startPlatformBackup = (getAccessToken: GetAccessToken, dryRun: boolean) =>
  request<FactoryRunAccepted>(getAccessToken, '/platform-backups', {
    method: 'POST',
    body: JSON.stringify({ dryRun }),
  })

/**
 * Scans production logs on demand.
 *
 * A dry run reads and groups exactly as a real run does and files nothing at all, so run progress
 * is the only place its outcome appears. That is what makes it the right way to check the
 * signature rules and occurrence thresholds against real log volume before letting the module
 * file for the first time.
 */
export const startLogWatchScan = (getAccessToken: GetAccessToken, dryRun: boolean) =>
  request<FactoryRunAccepted>(getAccessToken, '/log-scans', {
    method: 'POST',
    body: JSON.stringify({ dryRun }),
  })

/**
 * Reads SonarCloud's latest analysis of main on demand and files its new-code findings in Linear.
 *
 * A dry run reads the same analysis and reports what it would file without creating anything, so
 * run progress is the only place its outcome appears.
 */
export const startStaticAnalysisScan = (getAccessToken: GetAccessToken, dryRun: boolean) =>
  request<FactoryRunAccepted>(getAccessToken, '/static-analysis-scans', {
    method: 'POST',
    body: JSON.stringify({ dryRun }),
  })

export const startDeploy = (
  getAccessToken: GetAccessToken,
  frontendCommit: string,
  confirmation: string,
) => request<FactoryRunAccepted>(getAccessToken, '/deploys', {
  method: 'POST',
  body: JSON.stringify({ frontendCommit, confirmation }),
})

/**
 * Hands a Linear ticket to the ticket agent, or dry-runs it.
 *
 * A real run adds the `factory:agent` label in Linear before it starts, so the label stays the one
 * record of what was approved whichever door the approval came through. A dry run posts the
 * would-be diff to the ticket and pushes nothing.
 */
export const startTicketAgent = (
  getAccessToken: GetAccessToken,
  ticket: string,
  dryRun: boolean,
) => request<FactoryRunAccepted>(getAccessToken, '/ticket-agent/runs', {
  method: 'POST',
  body: JSON.stringify({ ticket, dryRun }),
})

/** Stops a live run: the ticket goes back to a person and any pull request becomes a draft. */
export const stopTicketAgent = (getAccessToken: GetAccessToken, identifier: string) =>
  request<FactoryRunAccepted>(
    getAccessToken,
    `/ticket-agent/runs/${encodeURIComponent(identifier)}/stop`,
    { method: 'POST' },
  )

/** Pauses or resumes the five-minute intake. Live runs carry on either way. */
export const setTicketIntake = (getAccessToken: GetAccessToken, paused: boolean) =>
  request<FactoryRunAccepted>(getAccessToken, '/ticket-agent/intake', {
    method: 'POST',
    body: JSON.stringify({ paused }),
  })

/**
 * The Linear identifier a pasted value names: `SIM-123`, or a Linear URL with `/issue/SIM-123`.
 * Mirrors the factory's own parse, which is the one that decides; this only lets the console say
 * what it understood before sending.
 */
export const parseTicketReference = (value: string): string | null => {
  const trimmed = value.trim()
  if (!trimmed) return null
  let candidate = trimmed
  if (trimmed.includes('/')) {
    try {
      const url = new URL(trimmed)
      if (url.protocol !== 'https:' || url.hostname !== 'linear.app') return null
      const segments = url.pathname.split('/')
      const index = segments.indexOf('issue')
      if (index < 0 || index + 1 >= segments.length) return null
      candidate = segments[index + 1]
    } catch {
      return null
    }
  }
  const upper = candidate.toUpperCase()
  return /^[A-Z][A-Z0-9]{0,15}-[0-9]{1,9}$/.test(upper) ? upper : null
}

export const fetchRunProgress = (getAccessToken: GetAccessToken, workflowId: string) =>
  request<FactoryRunProgress>(getAccessToken, `/runs/${encodeURIComponent(workflowId)}`)

export type FactoryNodeKind = 'MODULE' | 'ARTIFACT'

export type FactoryNodeBand = 'OBSERVE' | 'PLAN' | 'BUILD' | 'SHIP' | 'LEARN' | 'UTILITY'

export type FactoryLoop = 'FAST' | 'MAIN' | 'SLOW'

/**
 * IDLE and OFFLINE are separate on purpose: "nothing to do" and "nothing is listening" send an
 * operator to different places, and the build agent runs on a machine the server cannot reach.
 *
 * NOT_TRACKED is different again: this node has no owning module and no artifact source this
 * container can read (currently only `production`), so reporting any other value here would be a
 * false statement of health. Production's real state lives on the platform status page.
 */
export type FactoryNodeHealth =
  | 'READY' | 'DEGRADED' | 'DISABLED' | 'UNAVAILABLE' | 'OFFLINE' | 'IDLE' | 'NOT_TRACKED'

export interface FactoryNodeCounts {
  inFlight: number
  ok24h: number
  failed24h: number
}

export interface FactoryFlowNode {
  key: string
  kind: FactoryNodeKind
  band: FactoryNodeBand
  label: string
  /** null when the source could not be read, which is not the same as zero. */
  counts: FactoryNodeCounts | null
  health: FactoryNodeHealth
  diagnostic: string | null
}

export interface FactoryFlowEdge {
  from: string
  to: string
  label: string
  loop: FactoryLoop
}

export interface FactoryFlow {
  fetchedAt: string
  nodes: FactoryFlowNode[]
  edges: FactoryFlowEdge[]
}

export const fetchFactoryFlow = (getAccessToken: GetAccessToken) =>
  request<FactoryFlow>(getAccessToken, '/flow')

export interface FactoryFlowDetailItem {
  id: string
  title: string
  status: string
  at: string | null
  url: string | null
  /**
   * Where the ticket agent stands with this item. A Linear ticket: `with-agent` while the agent
   * holds it, `stuck` once it handed it back after failing. One of the agent's own runs: `running`
   * while it is live. Null otherwise; absent from older servers, which reads the same.
   */
  agent?: 'with-agent' | 'stuck' | 'running' | null
}

export interface FactoryFlowDetail {
  nodeKey: string
  /**
   * The work behind this node, newest first.
   *
   * Only a node with no list of its own at all (`production`, or an unrecognised key)
   * reports `[]` unconditionally — there is genuinely nothing this console could ever list there.
   * Every other node distinguishes "could not read this" from "read it and found nothing": a
   * module's Temporal query failing, an artifact node's own reader (`linear`, `pull-request`,
   * `main`, `agent-setup`) failing, and — for `deploy`/`platformbackup` — the deployer itself
   * being unreachable, all report `null` here rather than `[]`. Losing that distinction would
   * misreport a broken read as a quiet one.
   */
  items: FactoryFlowDetailItem[] | null
}

/**
 * Reads one node's recent work, for its drawer.
 *
 * See {@link FactoryFlowDetail.items} for what `null` versus `[]` means in the response body.
 * Separately, the drawer distinguishes a detail that has not loaded yet from one that has, by
 * `null` versus a `FactoryFlowDetail` object — that is a fact about this call's own lifecycle,
 * not about anything the response body reports.
 */
export const fetchFactoryFlowDetail = (getAccessToken: GetAccessToken, nodeKey: string) =>
  request<FactoryFlowDetail>(getAccessToken, `/flow/${encodeURIComponent(nodeKey)}`)
