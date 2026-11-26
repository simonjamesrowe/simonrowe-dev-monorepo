import { useCallback, useEffect, useState, type ReactNode } from 'react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchContentFlow,
  fetchUptime,
  fetchVulnerabilities,
  type ContentFlowReport,
  type UptimeReport,
  type VulnerabilityReport,
} from '../../services/dashboardApi'
import { ContentFlowPanel } from './dashboard/ContentFlowPanel'
import { UptimePanel } from './dashboard/UptimePanel'
import { VulnerabilityPanel } from './dashboard/VulnerabilityPanel'
import { percent } from './dashboard/format'

const RANGES = [7, 30, 90] as const
const WEEKS = 12

type Panel<T> = { state: 'loading' } | { state: 'error'; message: string } | { state: 'ready'; data: T }

/** Loads one panel. Each panel loads and fails on its own, so one slow source holds up nothing. */
function usePanel<T>(load: () => Promise<T>): Panel<T> {
  const [panel, setPanel] = useState<Panel<T>>({ state: 'loading' })
  useEffect(() => {
    let current = true
    setPanel({ state: 'loading' })
    load()
      .then(data => { if (current) setPanel({ state: 'ready', data }) })
      .catch((error: unknown) => {
        if (current) {
          setPanel({ state: 'error', message: error instanceof Error ? error.message : 'Failed to load' })
        }
      })
    return () => { current = false }
  }, [load])
  return panel
}

/**
 * The admin dashboard: uptime, the reading pipeline and vulnerabilities, each from its own
 * endpoint under `/api/admin/dashboard`.
 */
export function DashboardAdmin() {
  const { getAccessToken } = useAuth()
  const [days, setDays] = useState<(typeof RANGES)[number]>(30)

  const loadUptime = useCallback(() => fetchUptime(getAccessToken, days), [getAccessToken, days])
  const loadVulnerabilities = useCallback(() => fetchVulnerabilities(getAccessToken), [getAccessToken])
  const loadContent = useCallback(() => fetchContentFlow(getAccessToken, WEEKS), [getAccessToken])

  const uptime = usePanel(loadUptime)
  const vulnerabilities = usePanel(loadVulnerabilities)
  const content = usePanel(loadContent)

  return (
    <div className="admin-dash">
      <div className="admin-page__header">
        <h1>Dashboard</h1>
        <div className="admin-dash__range" role="group" aria-label="Uptime window">
          {RANGES.map(range => (
            <button
              aria-pressed={days === range}
              key={range}
              onClick={() => setDays(range)}
              type="button"
            >
              {range} days
            </button>
          ))}
        </div>
      </div>

      <div className="admin-dash__kpis">
        <UptimeTile panel={uptime} name="www" days={days} />
        <UptimeTile panel={uptime} name="api" days={days} />
        <ReadingTile panel={content} />
        <VulnerabilityTile panel={vulnerabilities} />
      </div>

      <div className="admin-dash__grid">
        <PanelSlot panel={uptime} title="Public hostnames" wide>
          {data => <UptimePanel report={data} />}
        </PanelSlot>
        <PanelSlot panel={content} title="Articles per week">
          {data => <ContentFlowPanel report={data} />}
        </PanelSlot>
        <PanelSlot panel={vulnerabilities} title="Dependencies and vulnerabilities">
          {data => <VulnerabilityPanel report={data} />}
        </PanelSlot>
      </div>
    </div>
  )
}

function PanelSlot<T>({ panel, title, wide, children }: {
  panel: Panel<T>
  title: string
  wide?: boolean
  children: (data: T) => ReactNode
}) {
  if (panel.state === 'ready') return <>{children(panel.data)}</>
  return (
    <section className={`admin-dash__card${wide ? ' admin-dash__card--wide' : ''}`} aria-busy={panel.state === 'loading'}>
      <h2>{title}</h2>
      {panel.state === 'loading'
        ? <p className="admin-dash__note">Loading…</p>
        : <p className="admin-dash__note admin-dash__note--warn" role="alert">{panel.message}</p>}
    </section>
  )
}

function Tile({ label, value, detail }: { label: string; value: ReactNode; detail?: ReactNode }) {
  return (
    <div className="admin-dash__kpi">
      <span className="admin-dash__kpi-label">{label}</span>
      <span className="admin-dash__kpi-value">{value}</span>
      {detail && <span className="admin-dash__kpi-detail">{detail}</span>}
    </div>
  )
}

function UptimeTile({ panel, name, days }: { panel: Panel<UptimeReport>; name: string; days: number }) {
  const label = `${name} uptime · ${days}d`
  if (panel.state !== 'ready') return <Tile label={label} value="—" />
  const target = panel.data.targets.find(t => t.name === name)
  if (!target || target.probes === 0) return <Tile label={label} value="—" detail="Not probed yet" />
  return (
    <Tile
      label={label}
      value={percent(target.uptimePercent)}
      detail={`${target.failures.toLocaleString('en-GB')} failed of ${target.probes.toLocaleString('en-GB')} probes`}
    />
  )
}

function ReadingTile({ panel }: { panel: Panel<ContentFlowReport> }) {
  const label = 'Summarised this week'
  if (panel.state !== 'ready' || panel.data.weeks.length === 0) return <Tile label={label} value="—" />
  const week = panel.data.weeks[panel.data.weeks.length - 1]
  return <Tile label={label} value={week.summarised} detail={`of ${week.arrived} new articles`} />
}

function VulnerabilityTile({ panel }: { panel: Panel<VulnerabilityReport> }) {
  const label = 'Open critical · high'
  if (panel.state !== 'ready' || panel.data.status !== 'OK') return <Tile label={label} value="—" />
  const sum = (key: 'critical' | 'high') =>
    panel.data.projects.reduce((total, project) => total + project[key], 0)
  return (
    <Tile
      label={label}
      value={`${sum('critical')} · ${sum('high')}`}
      detail={`across ${panel.data.projects.length} projects`}
    />
  )
}
