import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import type { NavigationItem } from './Sidebar'

interface MobileMenuProps {
  aboutImageUrl: string
  items: NavigationItem[]
  onNavigate?: (section: string) => void
}

export function MobileMenu({ aboutImageUrl, items, onNavigate }: MobileMenuProps) {
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const navigate = useNavigate()

  const toggleMenu = () => {
    setIsMenuOpen((value) => !value)
  }

  const navigateTo = (item: NavigationItem) => {
    if (item.route) {
      void navigate(item.route)
    } else {
      document.getElementById(item.id)?.scrollIntoView({ behavior: 'smooth' })
    }
    onNavigate?.(item.id)
    setIsMenuOpen(false)
  }

  return (
    <div className="mobile-menu">
      <button aria-expanded={isMenuOpen} className="mobile-menu__trigger" onClick={toggleMenu} type="button">
        Menu
      </button>
      <div className={`mobile-menu__panel ${isMenuOpen ? 'is-open' : ''}`}>
        <nav aria-label="Mobile navigation">
          <ul>
            {items.map((item) => (
              <li key={item.id}>
                <button onClick={() => navigateTo(item)} type="button">
                  {item.id === 'about' ? <img alt="About" src={aboutImageUrl} /> : null}
                  <span>{item.label}</span>
                </button>
              </li>
            ))}
          </ul>
        </nav>
      </div>
    </div>
  )
}
