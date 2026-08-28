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
  key: 'codereview' | 'feedback' | 'cvefix' | 'deploy' | 'linear' | 'platformbackup'
  displayName: string
  configured: boolean
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
