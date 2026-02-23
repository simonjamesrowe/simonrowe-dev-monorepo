import { useState } from 'react'

import type { NavigationItem } from './Sidebar'

interface MobileMenuProps {
  aboutImageUrl: string
  items: NavigationItem[]
  onNavigate?: (section: string) => void
}

export function MobileMenu({ aboutImageUrl, items, onNavigate }: MobileMenuProps) {
  const [isMenuOpen, setIsMenuOpen] = useState(false)

  const toggleMenu = () => {
    setIsMenuOpen((value) => !value)
  }

  const navigateTo = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })
    onNavigate?.(id)
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
                <button onClick={() => navigateTo(item.id)} type="button">
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
