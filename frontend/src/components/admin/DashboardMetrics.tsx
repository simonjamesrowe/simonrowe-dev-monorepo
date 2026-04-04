import { TrendingUp } from 'lucide-react'

export function DashboardMetrics() {
  const kpis = [
    { label: 'Active Visitors', value: '1,284', change: '+12.4%', changePositive: true, detail: '' },
    { label: 'Blog Reads', value: '45.2k', change: '', changePositive: true, detail: 'Avg. time: 4m 12s' },
    { label: 'Conversion', value: '3.8%', change: '', changePositive: true, detail: 'Contact form leads' },
  ]

  const trafficBars = [35, 45, 55, 40, 60, 80, 70] // percentage heights

  return (
    <div className="dashboard-metrics">
      <div className="dashboard-metrics__kpis">
        {kpis.map((kpi) => (
          <div key={kpi.label} className="dashboard-metrics__kpi card">
            <span className="dashboard-metrics__kpi-label">{kpi.label}</span>
            <div className="dashboard-metrics__kpi-row">
              <span className="dashboard-metrics__kpi-value">{kpi.value}</span>
              {kpi.change && (
                <span className="dashboard-metrics__kpi-change dashboard-metrics__kpi-change--positive">
                  {kpi.change} <TrendingUp size={14} />
                </span>
              )}
            </div>
            {kpi.detail && <span className="dashboard-metrics__kpi-detail">{kpi.detail}</span>}
          </div>
        ))}
      </div>

      <div className="dashboard-metrics__traffic card">
        <div className="dashboard-metrics__traffic-header">
          <h3 className="dashboard-metrics__traffic-title">Traffic Distribution</h3>
          <div className="dashboard-metrics__traffic-toggle">
            <button type="button" className="dashboard-metrics__toggle-btn dashboard-metrics__toggle-btn--active">7 Days</button>
            <button type="button" className="dashboard-metrics__toggle-btn">30 Days</button>
          </div>
        </div>
        <div className="dashboard-metrics__chart">
          {trafficBars.map((height, i) => (
            <div key={i} className="dashboard-metrics__bar" style={{ height: `${height}%` }} />
          ))}
        </div>
      </div>
    </div>
  )
}
