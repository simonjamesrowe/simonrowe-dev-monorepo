import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { DashboardAdmin } from '../../src/pages/admin/DashboardAdmin'

vi.mock('../../src/services/dashboardApi', () => ({
  fetchUptime: vi.fn(),
  fetchVulnerabilities: vi.fn(),
  fetchContentFlow: vi.fn(),
}))

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))

import {
  fetchContentFlow,
  fetchUptime,
  fetchVulnerabilities,
  type ContentFlowReport,
  type DayUptime,
  type UptimeReport,
  type VulnerabilityReport,
} from '../../src/services/dashboardApi'
import { useAuth } from '../../src/auth/useAuth'

const mockUptime = vi.mocked(fetchUptime)
const mockVulnerabilities = vi.mocked(fetchVulnerabilities)
const mockContent = vi.mocked(fetchContentFlow)
const getAccessToken = vi.fn().mockResolvedValue('test-token')

const DAYS = ['2026-11-24', '2026-11-25', '2026-11-26']

function day(date: string, overrides: Partial<DayUptime> = {}): DayUptime {
  return { date, probes: 1440, failures: 0, unprobedMinutes: 0, status: 'UP', lastFailure: null, ...overrides }
}

function uptime(overrides: Partial<UptimeReport> = {}): UptimeReport {
  return {
    probing: true,
    probingSince: '2026-11-24T00:00:00Z',
    timeZone: 'Europe/London',
    days: DAYS,
    targets: [
      {
        name: 'www',
        uptimePercent: 99.9967,
        probes: 4320,
        failures: 1,
        averageMillis: 210,
        days: [day(DAYS[0]), day(DAYS[1], { status: 'DEGRADED', failures: 1, lastFailure: 'HTTP 502' }), day(DAYS[2])],
      },
      {
        name: 'api',
        uptimePercent: 100,
        probes: 4320,
        failures: 0,
        averageMillis: 330,
        days: DAYS.map(date => day(date)),
      },
    ],
    incidents: {
      available: true,
      items: [{
        number: 306,
        title: 'Production heartbeat failing: logs: NOTHING received from the stack in 30m',
        openedAt: '2026-11-18T01:29:17Z',
        closedAt: '2026-11-18T17:31:55Z',
        url: 'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/issues/306',
      }],
    },
    generatedAt: '2026-11-26T12:00:00Z',
    ...overrides,
  }
}

function vulnerabilities(overrides: Partial<VulnerabilityReport> = {}): VulnerabilityReport {
  return {
    status: 'OK',
    detail: null,
    projects: [
      { name: 'simonrowe-dev/backend-image', version: null, components: 99, critical: 1, high: 4, medium: 41, low: 88, unassigned: 0, suppressed: 0, riskScore: 325, lastBomImport: null },
      { name: 'simonrowe-dev/frontend', version: null, components: 642, critical: 0, high: 2, medium: 2, low: 1, unassigned: 0, suppressed: 3, riskScore: 7, lastBomImport: '2026-11-25T09:00:00Z' },
    ],
    trend: [
      { date: '2026-11-25', critical: 1, high: 9, medium: 40, low: 80 },
      { date: '2026-11-26', critical: 1, high: 6, medium: 43, low: 90 },
    ],
    generatedAt: '2026-11-26T12:00:00Z',
    ...overrides,
  }
}

function content(): ContentFlowReport {
  return {
    timeZone: 'Europe/London',
    weeks: [
      { weekStart: '2026-11-16', arrived: 72, summarised: 18 },
      { weekStart: '2026-11-23', arrived: 84, summarised: 21 },
    ],
    generatedAt: '2026-11-26T12:00:00Z',
  }
}

describe('DashboardAdmin', () => {
  beforeEach(() => {
    mockUptime.mockReset().mockResolvedValue(uptime())
    mockVulnerabilities.mockReset().mockResolvedValue(vulnerabilities())
    mockContent.mockReset().mockResolvedValue(content())
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    vi.mocked(useAuth).mockReturnValue({ getAccessToken } as any)
  })

  it('shows the headline figures from each panel', async () => {
    render(<DashboardAdmin />)

    expect(await screen.findByText('1 failed of 4,320 probes')).toBeInTheDocument()
    // 99.9967% is not a perfect record, so it is never rounded up to 100: tile and row agree.
    expect(screen.getAllByText('99.99%')).toHaveLength(2)
    expect(screen.getByText('of 84 new articles')).toBeInTheDocument()
    expect(screen.getByText('1 · 6')).toBeInTheDocument()
    expect(screen.getByText('across 2 projects')).toBeInTheDocument()
  })

  it('draws a row per hostname and explains a day on hover', async () => {
    render(<DashboardAdmin />)

    expect(await screen.findByRole('img', { name: /www: 99.99% uptime, 1 days with failures/ })).toBeInTheDocument()
    fireEvent.mouseMove(screen.getByTestId('cell-www-2026-11-25'), { clientX: 10, clientY: 10 })

    const tooltip = screen.getByRole('tooltip')
    expect(within(tooltip).getByText('Some failed probes')).toBeInTheDocument()
    expect(within(tooltip).getByText('HTTP 502')).toBeInTheDocument()
  })

  it('lists outage issues without the heartbeat prefix, with how long they lasted', async () => {
    render(<DashboardAdmin />)

    expect(await screen.findByText('logs: NOTHING received from the stack in 30m')).toBeInTheDocument()
    expect(screen.getByText('16h 3m')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Issue #306 on GitHub' }))
      .toHaveAttribute('href', 'https://github.com/simonjamesrowe/simonrowe-dev-monorepo/issues/306')
  })

  it('says when the probe is off rather than showing an empty strip as healthy', async () => {
    mockUptime.mockResolvedValue(uptime({ probing: false, probingSince: null }))

    render(<DashboardAdmin />)

    expect(await screen.findByText(/uptime probe is off on this server/)).toBeInTheDocument()
  })

  it('refetches uptime for the chosen window', async () => {
    render(<DashboardAdmin />)
    await screen.findByText('1 failed of 4,320 probes')
    expect(mockUptime).toHaveBeenLastCalledWith(getAccessToken, 30)

    fireEvent.click(screen.getByRole('button', { name: '90 days' }))

    await waitFor(() => expect(mockUptime).toHaveBeenLastCalledWith(getAccessToken, 90))
    expect(screen.getByRole('button', { name: '90 days' })).toHaveAttribute('aria-pressed', 'true')
    expect(mockVulnerabilities).toHaveBeenCalledTimes(1)
  })

  it('names the status Dependency-Track answered with when it cannot be read', async () => {
    mockVulnerabilities.mockResolvedValue(vulnerabilities({
      status: 'UNAVAILABLE',
      detail: 'Dependency-Track answered 401 for /api/v1/project',
      projects: [],
      trend: [],
    }))

    render(<DashboardAdmin />)

    expect(await screen.findByText(/answered 401 for \/api\/v1\/project/)).toBeInTheDocument()
  })

  it('keeps the other panels when one fails', async () => {
    mockContent.mockRejectedValue(new Error('Request failed.'))

    render(<DashboardAdmin />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Request failed.')
    expect(await screen.findByText('simonrowe-dev/frontend')).toBeInTheDocument()
    expect(screen.getByText('Public hostnames')).toBeInTheDocument()
  })

  it('shows a week\'s counts on hover over the bars', async () => {
    render(<DashboardAdmin />)

    fireEvent.mouseMove(await screen.findByTestId('week-2026-11-23'), { clientX: 10, clientY: 10 })

    const tooltip = screen.getByRole('tooltip')
    expect(within(tooltip).getByText('Week of 23 Nov')).toBeInTheDocument()
    expect(within(tooltip).getByText('84')).toBeInTheDocument()
    expect(within(tooltip).getByText('25%')).toBeInTheDocument()
  })
})
