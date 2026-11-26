import { ExternalLink } from 'lucide-react'

import type { DayStatus, DayUptime, TargetUptime, UptimeReport } from '../../../services/dashboardApi'
import { TooltipRow } from './ChartTooltip'
import { useChartTooltip } from './useChartTooltip'
import { dateTime, minutes, minutesBetween, percent, shortDate, weekdayDate } from './format'

const STATUS_LABELS: Record<DayStatus, string> = {
  UP: 'Up all day',
  DEGRADED: 'Some failed probes',
  DOWN: 'Down an hour or more',
  GAPS: 'Not fully probed',
  NO_DATA: 'No data',
}

/** The heartbeat prefixes every outage issue title with this; the panel's heading says it. */
const TITLE_PREFIX = 'Production heartbeat failing: '

/** One row per public hostname, one cell per day, then the heartbeat's outage issues. */
export function UptimePanel({ report }: { report: UptimeReport }) {
  const { show, hide, tooltip } = useChartTooltip()

  const cellTooltip = (target: TargetUptime, day: DayUptime) => (
    <>
      <div className="admin-dash__tooltip-title">{target.name} · {weekdayDate(day.date)}</div>
      <TooltipRow label="Status" value={STATUS_LABELS[day.status]} />
      {day.probes > 0 && <TooltipRow label="Probes" value={day.probes.toLocaleString('en-GB')} />}
      {day.failures > 0 && <TooltipRow label="Failed minutes" value={day.failures} />}
      {day.unprobedMinutes > 0 && <TooltipRow label="Not probed" value={minutes(day.unprobedMinutes)} />}
      {day.lastFailure && <TooltipRow label="Last failure" value={day.lastFailure} />}
    </>
  )

  return (
    <section className="admin-dash__card admin-dash__card--wide" aria-labelledby="dash-uptime">
      <header className="admin-dash__card-head">
        <div>
          <h2 id="dash-uptime">Public hostnames</h2>
          <p className="admin-dash__sub">
            One cell per day ({report.timeZone}). Probed from the backend once a minute, out
            through the internet and back in through the tunnel.
          </p>
        </div>
        <span className="admin-dash__source">In-stack probe · prod-down issues</span>
      </header>

      <ProbingNote report={report} />

      <div className="admin-dash__uptime" onMouseLeave={hide}>
        <div className="admin-dash__uptime-row admin-dash__uptime-row--head" aria-hidden="true">
          <span />
          <span className="admin-dash__uptime-axis">
            <span>{shortDate(report.days[0])}</span>
            <span>{shortDate(report.days[report.days.length - 1])}</span>
          </span>
          <span className="admin-dash__num">Uptime</span>
          <span className="admin-dash__num">Avg</span>
        </div>
        {report.targets.map(target => (
          <div className="admin-dash__uptime-row" key={target.name}>
            <span className="admin-dash__uptime-name">{target.name}</span>
            <span
              className="admin-dash__cells"
              style={{ gridTemplateColumns: `repeat(${target.days.length}, 1fr)` }}
              role="img"
              aria-label={rowSummary(target)}
            >
              {target.days.map(day => (
                <i
                  className={`admin-dash__cell admin-dash__cell--${day.status.toLowerCase()}`}
                  data-testid={`cell-${target.name}-${day.date}`}
                  key={day.date}
                  onMouseMove={event => show(event, cellTooltip(target, day))}
                />
              ))}
            </span>
            <span className="admin-dash__num">{percent(target.uptimePercent)}</span>
            <span className="admin-dash__num admin-dash__muted">
              {target.averageMillis === null ? '—' : `${target.averageMillis}ms`}
            </span>
          </div>
        ))}
      </div>

      <ul className="admin-dash__legend">
        {(['UP', 'DEGRADED', 'DOWN', 'GAPS'] as DayStatus[]).map(status => (
          <li key={status}>
            <i className={`admin-dash__cell admin-dash__cell--${status.toLowerCase()}`} />
            {STATUS_LABELS[status]}
          </li>
        ))}
      </ul>

      <h3 className="admin-dash__subhead">Outages the off-Pi heartbeat reported</h3>
      <Incidents report={report} />
      {tooltip}
    </section>
  )
}

function ProbingNote({ report }: { report: UptimeReport }) {
  if (!report.probing && !report.probingSince) {
    return (
      <p className="admin-dash__note">
        The uptime probe is off on this server (<code>DASHBOARD_UPTIME_ENABLED</code>). It runs in
        production only.
      </p>
    )
  }
  if (!report.probingSince) {
    return <p className="admin-dash__note">Probing has started; the first results land within a minute.</p>
  }
  return (
    <p className="admin-dash__note">
      History since {dateTime(report.probingSince)}. Hatched days are ones the probe could not
      see because the backend or the Pi was down: unknown, not up.
    </p>
  )
}

function Incidents({ report }: { report: UptimeReport }) {
  if (!report.incidents.available) {
    return <p className="admin-dash__note">GitHub could not be read, so the outage list is unavailable.</p>
  }
  if (report.incidents.items.length === 0) {
    return <p className="admin-dash__note">None in this window.</p>
  }
  return (
    <ul className="admin-dash__incidents">
      {report.incidents.items.map(incident => (
        <li key={incident.number}>
          <span className="admin-dash__incident-when">{dateTime(incident.openedAt)}</span>
          <span className="admin-dash__incident-title">
            {incident.title.startsWith(TITLE_PREFIX)
              ? incident.title.slice(TITLE_PREFIX.length)
              : incident.title}
          </span>
          <span className="admin-dash__incident-length">
            {incident.closedAt
              ? minutes(minutesBetween(incident.openedAt, incident.closedAt))
              : <span className="admin-dash__pill admin-dash__pill--bad">Open</span>}
          </span>
          <a href={incident.url} target="_blank" rel="noreferrer" aria-label={`Issue #${incident.number} on GitHub`}>
            #{incident.number} <ExternalLink aria-hidden="true" size={12} />
          </a>
        </li>
      ))}
    </ul>
  )
}

/** What a screen reader hears for a row, since the cells themselves are not focusable. */
function rowSummary(target: TargetUptime): string {
  const count = (status: DayStatus) => target.days.filter(day => day.status === status).length
  const parts = [`${target.name}: ${percent(target.uptimePercent)} uptime`]
  if (count('DOWN')) parts.push(`${count('DOWN')} days down`)
  if (count('DEGRADED')) parts.push(`${count('DEGRADED')} days with failures`)
  if (count('GAPS')) parts.push(`${count('GAPS')} days not fully probed`)
  return parts.join(', ')
}
