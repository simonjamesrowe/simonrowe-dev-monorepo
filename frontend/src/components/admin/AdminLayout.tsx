import { useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import {
  Briefcase,
  Code,
  Home,
  Image,
  LogOut,
  Menu,
  PenLine,
  Route,
  Tag,
  User,
  X,
} from 'lucide-react'
import { useAuth } from '../../auth/useAuth'

const navItems = [
  { path: '/admin/profile', label: 'Profile', icon: <User size={18} /> },
  { path: '/admin/blogs', label: 'Blogs', icon: <PenLine size={18} /> },
  { path: '/admin/jobs', label: 'Jobs', icon: <Briefcase size={18} /> },
  { path: '/admin/skills', label: 'Skills', icon: <Code size={18} /> },
  { path: '/admin/tags', label: 'Tags', icon: <Tag size={18} /> },
  { path: '/admin/tour-steps', label: 'Tour Steps', icon: <Route size={18} /> },
  { path: '/admin/media', label: 'Media', icon: <Image size={18} /> },
]

export function AdminLayout() {
  const { isAuthenticated, isLoading, login, logout, user } = useAuth()
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
          <h2>CMS Admin</h2>
          <p>{user?.email}</p>
          <button onClick={() => setSidebarOpen(false)} className="admin-sidebar-close" type="button" aria-label="Close navigation">
            <X size={18} />
          </button>
        </div>
        <nav className="admin-nav">
          {navItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              className={isActive(item.path) ? 'active' : ''}
              onClick={() => setSidebarOpen(false)}
            >
              <span className="admin-nav__icon">{item.icon}</span>
              <span>{item.label}</span>
            </Link>
          ))}
        </nav>
        <div className="admin-sidebar-footer">
          <Link to="/" className="admin-sidebar-footer__link">
            <Home size={16} />
            <span>Back to site</span>
          </Link>
          <button onClick={logout} className="admin-sidebar-footer__logout">
            <LogOut size={16} />
            <span>Sign Out</span>
          </button>
        </div>
      </aside>
      <main className="admin-main">
        <Outlet />
      </main>
    </div>
  )
}
