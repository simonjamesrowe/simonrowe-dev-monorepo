import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import { Menu, X } from 'lucide-react'

const navItems = [
  { label: 'Home', to: '/' },
  { label: 'Experience', to: '/experience' },
  { label: 'Blog', to: '/blogs' },
  { label: 'Admin', to: '/admin' },
]

export function MobileMenu() {
  const [isOpen, setIsOpen] = useState(false)

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
        </ul>
      </nav>
    </div>
  )
}
