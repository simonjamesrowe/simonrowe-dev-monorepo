import { Link, NavLink } from 'react-router-dom'
import { MessageSquare, Moon, Sun, UserCircle } from 'lucide-react'
import { SiteSearch } from '../search/SiteSearch'
import { useChat } from '../../contexts/ChatContext'
import { useTheme } from '../../contexts/ThemeContext'

export function TopNav() {
  const { openChat } = useChat()
  const { theme, toggleTheme } = useTheme()

  return (
    <nav className="top-nav glass-panel elevation-ambient">
      <Link to="/" className="top-nav__brand" aria-label="Simon Rowe homepage">
        <span className="top-nav__brand-mark">SR</span>
        <span className="top-nav__brand-name">Simon Rowe</span>
      </Link>
      <div className="top-nav__links">
        <NavLink to="/" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`} end>Home</NavLink>
        <NavLink to="/profile" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Profile</NavLink>
        <NavLink to="/experience" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Experience</NavLink>
        <NavLink to="/blogs" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>Blog</NavLink>
        <NavLink to="/news-events" className={({ isActive }) => `top-nav__link${isActive ? ' top-nav__link--active' : ''}`}>News & Events</NavLink>
      </div>
      <div className="top-nav__actions">
        <button className="top-nav__ask-ai" onClick={() => openChat()} type="button" data-testid="open-chat">
          <MessageSquare size={18} />
          <span className="top-nav__ask-ai-label">ASK AI</span>
        </button>
        <SiteSearch onChatStart={openChat} />
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
