import { DashboardMetrics } from '../../components/admin/DashboardMetrics'
import { InquiryList } from '../../components/admin/InquiryList'
import { ProfileManagement } from '../../components/admin/ProfileManagement'
import { TopContent } from '../../components/admin/TopContent'

export function DashboardAdmin() {
  return (
    <div className="admin-dashboard">
      <div className="admin-dashboard__metrics-row">
        <DashboardMetrics />
        <div className="admin-dashboard__insights card">
          <h3 className="admin-dashboard__insights-title">Recent Insights</h3>
          <ul className="admin-dashboard__insights-list">
            <li className="admin-dashboard__insight">
              <span className="admin-dashboard__insight-dot admin-dashboard__insight-dot--info" />
              <div>
                <p className="admin-dashboard__insight-text">New interaction on &quot;AI Engineering&quot;</p>
                <span className="admin-dashboard__insight-time">2 minutes ago</span>
              </div>
            </li>
            <li className="admin-dashboard__insight">
              <span className="admin-dashboard__insight-dot admin-dashboard__insight-dot--warning" />
              <div>
                <p className="admin-dashboard__insight-text">Draft scheduled for release</p>
                <span className="admin-dashboard__insight-time">1 hour ago</span>
              </div>
            </li>
            <li className="admin-dashboard__insight">
              <span className="admin-dashboard__insight-dot admin-dashboard__insight-dot--success" />
              <div>
                <p className="admin-dashboard__insight-text">System update completed</p>
                <span className="admin-dashboard__insight-time">5 hours ago</span>
              </div>
            </li>
          </ul>
          <a href="#" className="admin-dashboard__insights-link">View Audit Log →</a>
        </div>
      </div>

      <ProfileManagement />
      <InquiryList />
      <TopContent />
    </div>
  )
}
