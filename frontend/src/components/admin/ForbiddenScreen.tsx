import { ArrowLeft, Lock, LogOut } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'

export function ForbiddenScreen() {
  const { user, logout } = useAuth()
  const email = typeof user?.email === 'string' ? user.email : undefined

  return (
    <div className="admin-forbidden">
      <div className="admin-forbidden__card">
        <div className="admin-forbidden__icon" aria-hidden>
          <Lock size={32} />
        </div>
        <h1 className="admin-forbidden__title">Access denied</h1>
        <p className="admin-forbidden__message">
          {email
            ? `${email} does not have permission to use the admin panel.`
            : 'You do not have permission to use the admin panel.'}
        </p>
        <p className="admin-forbidden__hint">
          The <code>DEV_PORTAL_ADMIN</code> role is required.
        </p>
        <div className="admin-forbidden__actions">
          <button
            type="button"
            onClick={logout}
            className="admin-forbidden__button admin-forbidden__button--primary"
          >
            <LogOut size={16} />
            <span>Sign out</span>
          </button>
          <Link to="/" className="admin-forbidden__button">
            <ArrowLeft size={16} />
            <span>Back to site</span>
          </Link>
        </div>
      </div>
    </div>
  )
}
