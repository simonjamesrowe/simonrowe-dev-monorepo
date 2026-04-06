import { NavLink } from 'react-router-dom'
import { MessageSquare, UserCircle } from 'lucide-react'
import { SiteSearch } from '../search/SiteSearch'
import { useChat } from '../../contexts/ChatContext'

export function TopNav() {
  const { openChat } = useChat()

  return (
    <nav className="top-nav glass-panel elevation-ambient">
      <div className="top-nav__links">
        <NavLink to="/" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`} end>Home</NavLink>
        <NavLink to="/experience" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Experience</NavLink>
        <NavLink to="/blogs" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Blog</NavLink>
      </div>
      <div className="top-nav__actions">
        <button className="top-nav__ask-ai" onClick={() => openChat()} type="button">
          <MessageSquare size={18} />
          <span className="top-nav__ask-ai-label">ASK AI</span>
        </button>
        <SiteSearch />
        <NavLink to="/admin" className="top-nav__user-btn">
          <UserCircle size={24} />
        </NavLink>
      </div>
    </nav>
  )
}
