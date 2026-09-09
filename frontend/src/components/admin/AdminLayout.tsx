import { useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import {
  Briefcase,
  CircuitBoard,
  Code,
  Database,
  FileCode,
  FileText,
  GraduationCap,
  Image,
  LayoutDashboard,
  Link2,
  LogOut,
  Menu,
  Newspaper,
  Plus,
  Route,
  Rss,
  Tag,
  User,
  X,
} from 'lucide-react'
import { useAdminRole } from '../../auth/useAdminRole'
import { useAuth } from '../../auth/useAuth'
import { ForbiddenScreen } from './ForbiddenScreen'

const navItems = [
  { path: '/admin/dashboard', label: 'Dashboard', icon: <LayoutDashboard size={18} /> },
  { path: '/admin/blogs', label: 'Content', icon: <FileText size={18} /> },
  { path: '/admin/profile', label: 'Profile', icon: <User size={18} /> },
  { path: '/admin/skills', label: 'Skills', icon: <Code size={18} /> },
  { path: '/admin/jobs', label: 'Jobs', icon: <Briefcase size={18} /> },
  { path: '/admin/tags', label: 'Tags', icon: <Tag size={18} /> },
  { path: '/admin/code-examples', label: 'Code Examples', icon: <FileCode size={18} /> },
  { path: '/admin/tour-steps', label: 'Tour Steps', icon: <Route size={18} /> },
  { path: '/admin/media', label: 'Media', icon: <Image size={18} /> },
  { path: '/admin/aggregated-content', label: 'News & Events', icon: <Newspaper size={18} /> },
  { path: '/admin/content-sources', label: 'Content Sources', icon: <Rss size={18} /> },
  { path: '/admin/short-links', label: 'Share Links', icon: <Link2 size={18} /> },
  {
    path: '/admin/school',
    label: 'Term Time',
    icon: <GraduationCap size={18} />,
    // Term Time is the only admin area with enough distinct views to need sub-navigation.
    // Rendered as an indented group under its parent rather than as four more top-level
    // entries, which would bury the rest of the menu.
    children: [
      { path: '/admin/school', label: 'Overview' },
      { path: '/admin/school/approvals', label: 'Approvals' },
      { path: '/admin/school/documents', label: 'Documents' },
      { path: '/admin/school/events', label: 'Events' },
    ],
  },
  { path: '/admin/data-operations', label: 'Data Ops', icon: <Database size={18} /> },
  { path: '/admin/software-factory', label: 'Software Factory', icon: <CircuitBoard size={18} /> },
]

export function AdminLayout() {
  const { isAuthenticated, isLoading, login, logout } = useAuth()
  const isAdminRole = useAdminRole()
  const location = useLocation()
  const [sidebarOpen, setSidebarOpen] = useState(false)

  if (isLoading) {
    return <div className="admin-loading">Loading...</div>
  }

  if (!isAuthenticated) {
    login()
    return <div className="admin-loading">Redirecting to login...</div>
  }

  if (!isAdminRole) {
    return <ForbiddenScreen />
  }

  const isActive = (path: string) => location.pathname.startsWith(path)

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
            <div key={item.path}>
              <Link
                to={item.path}
                className={`admin-nav__link${isActive(item.path) ? ' admin-nav__link--active' : ''}`}
                onClick={() => setSidebarOpen(false)}
              >
                <span className="admin-nav__icon">{item.icon}</span>
                <span className="admin-nav__label">{item.label}</span>
              </Link>
              {/*
                Children render only while their section is active, so the sidebar stays the
                same length as before for every other area.
              */}
              {item.children && location.pathname.startsWith(item.path) && (
                <div className="admin-nav__children">
                  {item.children.map((child) => (
                    <Link
                      key={child.path}
                      to={child.path}
                      className={`admin-nav__child${
                        location.pathname === child.path ? ' admin-nav__child--active' : ''
                      }`}
                      onClick={() => setSidebarOpen(false)}
                    >
                      {child.label}
                    </Link>
                  ))}
                </div>
              )}
            </div>
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
        <main className="admin-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
