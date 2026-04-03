import { NavLink } from 'react-router-dom'
import { UserCircle } from 'lucide-react'

export function TopNav() {
  return (
    <nav className="top-nav glass-panel elevation-ambient">
      <div className="top-nav__brand">The Digital Architect</div>
      <div className="top-nav__links">
        <NavLink to="/experience" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Experience</NavLink>
        <NavLink to="/experience" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Skills</NavLink>
        <NavLink to="/blogs" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Blog</NavLink>
        <NavLink to="/admin" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Admin</NavLink>
      </div>
      <div className="top-nav__actions">
        <button className="top-nav__user-btn" type="button">
          <UserCircle size={24} />
        </button>
      </div>
    </nav>
  )
}
