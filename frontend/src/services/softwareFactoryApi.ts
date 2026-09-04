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

export interface SoftwareFactoryStatus {
  fetchedAt: string
  backendCommit: string
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

export const startDeploy = (
  getAccessToken: GetAccessToken,
  frontendCommit: string,
  confirmation: string,
) => request<FactoryRunAccepted>(getAccessToken, '/deploys', {
  method: 'POST',
  body: JSON.stringify({ frontendCommit, confirmation }),
})

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
