import { API_BASE_URL } from '../config/api'
import { authFetch, handleResponse, type GetAccessToken } from './adminApi'

const DASHBOARD_URL = `${API_BASE_URL}/api/admin/dashboard`

export type DayStatus = 'UP' | 'DEGRADED' | 'DOWN' | 'GAPS' | 'NO_DATA'

export interface DayUptime {
  date: string
  probes: number
  failures: number
  unprobedMinutes: number
  status: DayStatus
  lastFailure: string | null
}

export interface TargetUptime {
  name: string
  uptimePercent: number | null
  probes: number
  failures: number
  averageMillis: number | null
  days: DayUptime[]
}

export interface Incident {
  number: number
  title: string
  openedAt: string
  closedAt: string | null
  url: string
}

export interface UptimeReport {
  probing: boolean
  probingSince: string | null
  timeZone: string
  days: string[]
  targets: TargetUptime[]
  incidents: { available: boolean; items: Incident[] }
  generatedAt: string
}

export type VulnerabilityStatus = 'OK' | 'NOT_CONFIGURED' | 'UNAVAILABLE'

export interface ProjectRow {
  name: string
  version: string | null
  components: number
  critical: number
  high: number
  medium: number
  low: number
  unassigned: number
  suppressed: number
  riskScore: number
  lastBomImport: string | null
}

export interface TrendPoint {
  date: string
  critical: number
  high: number
  medium: number
  low: number
}

export interface VulnerabilityReport {
  status: VulnerabilityStatus
  detail: string | null
  projects: ProjectRow[]
  trend: TrendPoint[]
  generatedAt: string
}

export interface ContentFlowWeek {
  weekStart: string
  arrived: number
  summarised: number
}

export interface ContentFlowReport {
  timeZone: string
  weeks: ContentFlowWeek[]
  generatedAt: string
}

async function get<T>(getAccessToken: GetAccessToken, path: string): Promise<T> {
  const token = await getAccessToken()
  return handleResponse<T>(await authFetch(`${DASHBOARD_URL}${path}`, token))
}

export function fetchUptime(getAccessToken: GetAccessToken, days: number): Promise<UptimeReport> {
  return get(getAccessToken, `/uptime?days=${days}`)
}

export function fetchVulnerabilities(getAccessToken: GetAccessToken): Promise<VulnerabilityReport> {
  return get(getAccessToken, '/vulnerabilities')
}

export function fetchContentFlow(
  getAccessToken: GetAccessToken,
  weeks: number,
): Promise<ContentFlowReport> {
  return get(getAccessToken, `/content-flow?weeks=${weeks}`)
}
