import { useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import {
  Bell,
  Briefcase,
  Code,
  Database,
  FileText,
  Image,
  LayoutDashboard,
  LogOut,
  Menu,
  Plus,
  Route,
  Tag,
  User,
  X,
} from 'lucide-react'
import { useAuth } from '../../auth/useAuth'

const navItems = [
  { path: '/admin/dashboard', label: 'Dashboard', icon: <LayoutDashboard size={18} /> },
  { path: '/admin/blogs', label: 'Content', icon: <FileText size={18} /> },
  { path: '/admin/profile', label: 'Profile', icon: <User size={18} /> },
  { path: '/admin/skills', label: 'Skills', icon: <Code size={18} /> },
  { path: '/admin/jobs', label: 'Jobs', icon: <Briefcase size={18} /> },
  { path: '/admin/tags', label: 'Tags', icon: <Tag size={18} /> },
  { path: '/admin/tour-steps', label: 'Tour Steps', icon: <Route size={18} /> },
  { path: '/admin/media', label: 'Media', icon: <Image size={18} /> },
  { path: '/admin/data-operations', label: 'Data Ops', icon: <Database size={18} /> },
]

export function AdminLayout() {
  const { isAuthenticated, isLoading, login, logout } = useAuth()
  const location = useLocation()
  const [sidebarOpen, setSidebarOpen] = useState(false)

  if (isLoading) {
    return <div className="admin-loading">Loading...</div>
  }

  if (!isAuthenticated) {
    login()
    return <div className="admin-loading">Redirecting to login...</div>
  }

  const isActive = (path: string) => location.pathname.startsWith(path)
  const currentPage = navItems.find(item => isActive(item.path))?.label ?? 'Dashboard'

  return (
    <div className="admin-layout">
      <button
        className="admin-mobile-toggle"
        onClick={() => setSidebarOpen(true)}
        type="button"
        aria-label="Open navigation"
      >
        <Menu size={20} />
      </button>
      {sidebarOpen && (
        <div
          className="admin-sidebar-overlay"
          onClick={() => setSidebarOpen(false)}
        />
      )}
      <aside className={`admin-sidebar${sidebarOpen ? ' admin-sidebar--open' : ''}`}>
        <div className="admin-sidebar-header">
          <div className="admin-sidebar-header__avatar">
            <User size={20} />
          </div>
          <div className="admin-sidebar-header__info">
            <span className="admin-sidebar-header__name">System Admin</span>
            <span className="admin-sidebar-header__node">SENTINEL NODE 01</span>
          </div>
          <button onClick={() => setSidebarOpen(false)} className="admin-sidebar-close" type="button" aria-label="Close navigation">
            <X size={18} />
          </button>
        </div>
        <nav className="admin-nav">
          {navItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              className={`admin-nav__link${isActive(item.path) ? ' admin-nav__link--active' : ''}`}
              onClick={() => setSidebarOpen(false)}
            >
              <span className="admin-nav__icon">{item.icon}</span>
              <span className="admin-nav__label">{item.label}</span>
            </Link>
          ))}
        </nav>
        <div className="admin-sidebar-footer">
          <Link to="/admin/blogs/new" className="admin-sidebar-footer__new-entry">
            <Plus size={16} />
            <span>New Entry</span>
          </Link>
          <button onClick={logout} className="admin-sidebar-footer__logout">
            <LogOut size={16} />
            <span>Logout</span>
          </button>
        </div>
      </aside>
      <div className="admin-content">
        <header className="admin-header">
          <div className="admin-header__title">
            <span className="admin-header__brand">Admin</span>
            <span className="admin-header__separator">/</span>
            <span className="admin-header__page">{currentPage}</span>
          </div>
          <div className="admin-header__actions">
            <button type="button" className="admin-header__bell" aria-label="Notifications">
              <Bell size={18} />
            </button>
            <span className="admin-header__live-badge">
              <span className="admin-header__live-dot" />
              LIVE NODE
            </span>
          </div>
        </header>
        <main className="admin-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
