import { NavLink } from 'react-router-dom'
import { MessageSquare, Moon, Sun, UserCircle } from 'lucide-react'
import { SiteSearch } from '../search/SiteSearch'
import { useChat } from '../../contexts/ChatContext'
import { useTheme } from '../../contexts/ThemeContext'

export function TopNav() {
  const { openChat } = useChat()
  const { theme, toggleTheme } = useTheme()

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
        <button
          aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
          className="nav__theme-toggle"
          onClick={toggleTheme}
          type="button"
        >
          {theme === 'dark' ? <Sun size={20} /> : <Moon size={20} />}
        </button>
        <NavLink to="/admin" className="top-nav__user-btn">
          <UserCircle size={24} />
        </NavLink>
      </div>
    </nav>
  )
}
