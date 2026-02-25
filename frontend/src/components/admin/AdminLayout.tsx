import { Link, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'

const navItems = [
  { path: '/admin', label: 'Dashboard' },
  { path: '/admin/blogs', label: 'Blogs' },
  { path: '/admin/jobs', label: 'Jobs' },
  { path: '/admin/skills', label: 'Skills' },
  { path: '/admin/profile', label: 'Profile' },
  { path: '/admin/tags', label: 'Tags' },
  { path: '/admin/tour-steps', label: 'Tour Steps' },
  { path: '/admin/media', label: 'Media' },
]

export function AdminLayout() {
  const { isAuthenticated, isLoading, login, logout, user } = useAuth()
  const location = useLocation()

  if (isLoading) {
    return <div className="admin-loading">Loading...</div>
  }

  if (!isAuthenticated) {
    return (
      <div className="admin-login">
        <h1>Admin Login</h1>
        <p>You need to sign in to access the admin panel.</p>
        <button onClick={login}>Sign In</button>
      </div>
    )
  }

  return (
    <div className="admin-layout">
      <aside className="admin-sidebar">
        <div className="admin-sidebar-header">
          <h2>CMS Admin</h2>
          <p>{user?.email}</p>
          <button onClick={logout}>Sign Out</button>
        </div>
        <nav className="admin-nav">
          {navItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              className={location.pathname === item.path ? 'active' : ''}
            >
              {item.label}
            </Link>
          ))}
        </nav>
      </aside>
      <main className="admin-main">
        <Outlet />
      </main>
    </div>
  )
}
