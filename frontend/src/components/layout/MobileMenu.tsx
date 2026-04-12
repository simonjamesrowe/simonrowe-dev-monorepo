import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { Menu, Moon, Sun, X } from 'lucide-react'
import { useTheme } from '../../contexts/ThemeContext'

const navItems = [
  { label: 'Home', to: '/' },
  { label: 'Experience', to: '/experience' },
  { label: 'Blog', to: '/blogs' },
  { label: 'News & Events', to: '/news-events' },
  { label: 'Admin', to: '/admin' },
]

export function MobileMenu() {
  const [isOpen, setIsOpen] = useState(false)
  const { theme, toggleTheme } = useTheme()

  return (
    <div className="mobile-menu">
      <button
        aria-expanded={isOpen}
        aria-label={isOpen ? 'Close menu' : 'Open menu'}
        className="mobile-menu__trigger"
        onClick={() => setIsOpen(v => !v)}
        type="button"
      >
        {isOpen ? <X size={24} /> : <Menu size={24} />}
      </button>
      {isOpen && <div className="mobile-menu__backdrop" onClick={() => setIsOpen(false)} />}
      <nav className={`mobile-menu__panel${isOpen ? ' is-open' : ''}`} aria-label="Mobile navigation">
        <ul className="mobile-menu__list">
          {navItems.map(item => (
            <li key={item.to}>
              <NavLink
                to={item.to}
                className={({ isActive }) => `mobile-menu__link${isActive ? ' mobile-menu__link--active' : ''}`}
                onClick={() => setIsOpen(false)}
                end={item.to === '/'}
              >
                {item.label}
              </NavLink>
            </li>
          ))}
          <li>
            <button
              className="mobile-menu__link mobile-menu__theme-toggle"
              onClick={toggleTheme}
              type="button"
            >
              {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
              <span>{theme === 'dark' ? 'Light Mode' : 'Dark Mode'}</span>
            </button>
          </li>
        </ul>
      </nav>
    </div>
  )
}
