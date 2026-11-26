import type { ContentFlowReport, ContentFlowWeek } from '../../../services/dashboardApi'
import { TooltipRow } from './ChartTooltip'
import { useChartTooltip } from './useChartTooltip'
import { niceCeiling, shortDate } from './format'

const WIDTH = 640
const HEIGHT = 220
const MARGIN = { left: 36, right: 8, top: 12, bottom: 26 }
const BAR_GAP = 2

const SERIES = [
  { key: 'arrived', label: 'Arrived', className: 'admin-dash__series-1', colour: 'var(--dash-series-1)' },
  { key: 'summarised', label: 'Summarised', className: 'admin-dash__series-2', colour: 'var(--dash-series-2)' },
] as const

/** Articles arrived and summarised per week, as grouped bars on one axis. */
export function ContentFlowPanel({ report }: { report: ContentFlowReport }) {
  const { show, showAt, hide, tooltip } = useChartTooltip()
  const weeks = report.weeks
  const max = Math.max(1, ...weeks.map(w => Math.max(w.arrived, w.summarised)))
  const yMax = niceCeiling(max)
  const ticks = [0, yMax / 2, yMax]
  const plotWidth = WIDTH - MARGIN.left - MARGIN.right
  const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom
  const band = plotWidth / Math.max(1, weeks.length)
  const barWidth = Math.min(16, (band - 10) / 2)
  const y = (value: number) => MARGIN.top + (1 - value / yMax) * plotHeight
  const labelEvery = weeks.length > 8 ? 2 : 1

  const weekTooltip = (week: ContentFlowWeek) => (
    <>
      <div className="admin-dash__tooltip-title">Week of {shortDate(week.weekStart)}</div>
      {SERIES.map(series => (
        <TooltipRow key={series.key} label={series.label} value={week[series.key]} swatch={series.colour} />
      ))}
      <TooltipRow
        label="Summarised of arrived"
        value={week.arrived ? `${Math.round((week.summarised / week.arrived) * 100)}%` : '—'}
      />
    </>
  )

  return (
    <section className="admin-dash__card" aria-labelledby="dash-content">
      <header className="admin-dash__card-head">
        <div>
          <h2 id="dash-content">Articles per week</h2>
          <p className="admin-dash__sub">
            Arrived from every source, and summarised (each article counted once, in the week
            of its first summary). Listened joins once listening history is kept.
          </p>
        </div>
        <span className="admin-dash__source">aggregated_articles · article_summaries</span>
      </header>

      <svg
        className="admin-dash__chart"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={`Articles arrived and summarised per week, last ${weeks.length} weeks`}
        onMouseLeave={hide}
      >
        {ticks.map(tick => (
          <g key={tick}>
            <line className="admin-dash__gridline" x1={MARGIN.left} x2={WIDTH - MARGIN.right} y1={y(tick)} y2={y(tick)} />
            <text className="admin-dash__axis" x={MARGIN.left - 6} y={y(tick) + 4} textAnchor="end">{tick}</text>
          </g>
        ))}
        {weeks.map((week, index) => {
          const centre = MARGIN.left + band * index + band / 2
          return (
            <g key={week.weekStart}>
              {SERIES.map((series, s) => {
                const value = week[series.key]
                const x = centre - barWidth - BAR_GAP / 2 + s * (barWidth + BAR_GAP)
                return value > 0 ? (
                  <path key={series.key} className={series.className} d={roundedTop(x, y(value), barWidth, y(0))} />
                ) : null
              })}
              {(index % labelEvery === (weeks.length - 1) % labelEvery) && (
                <text className="admin-dash__axis" x={centre} y={HEIGHT - 8} textAnchor="middle">
                  {shortDate(week.weekStart)}
                </text>
              )}
              <rect
                className="admin-dash__hit"
                data-testid={`week-${week.weekStart}`}
                x={centre - band / 2}
                y={MARGIN.top}
                width={band}
                height={plotHeight}
                tabIndex={0}
                aria-label={`Week of ${shortDate(week.weekStart)}: ${week.arrived} arrived, ${week.summarised} summarised`}
                onMouseMove={event => show(event, weekTooltip(week))}
                onFocus={event => showAt(event.currentTarget, weekTooltip(week))}
                onBlur={hide}
              />
            </g>
          )
        })}
      </svg>

      <ul className="admin-dash__legend">
        {SERIES.map(series => (
          <li key={series.key}>
            <i className="admin-dash__swatch" style={{ background: series.colour }} />
            {series.label}
          </li>
        ))}
      </ul>
      {tooltip}
    </section>
  )
}

/** A bar with 4px-rounded top corners, anchored square to the baseline. */
function roundedTop(x: number, top: number, width: number, baseline: number): string {
  const radius = Math.min(4, width / 2, baseline - top)
  return `M${x} ${baseline}V${top + radius}Q${x} ${top} ${x + radius} ${top}`
    + `H${x + width - radius}Q${x + width} ${top} ${x + width} ${top + radius}V${baseline}Z`
}
