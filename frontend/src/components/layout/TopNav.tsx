import { NavLink } from 'react-router-dom'
import { UserCircle } from 'lucide-react'
import { SiteSearch } from '../search/SiteSearch'

export function TopNav() {
  return (
    <nav className="top-nav glass-panel elevation-ambient">
      <div className="top-nav__links">
        <NavLink to="/" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`} end>Home</NavLink>
        <NavLink to="/experience" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Experience</NavLink>
        <NavLink to="/blogs" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Blog</NavLink>
      </div>
      <div className="top-nav__actions">
        <SiteSearch />
        <NavLink to="/admin" className="top-nav__user-btn">
          <UserCircle size={24} />
        </NavLink>
      </div>
    </nav>
  )
}
