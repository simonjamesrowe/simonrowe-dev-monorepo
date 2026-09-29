import { Compass, Menu, Moon, Sparkles, Sun, UserCircle } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useLocation } from 'react-router-dom'

import { useAdminRole } from '../../auth/useAdminRole'
import { useChat } from '../../contexts/ChatContext'
import { useTheme } from '../../contexts/ThemeContext'
import { usePortfolio } from '../../hooks/usePortfolio'
import { useTour } from '../../hooks/useTour'
import { SiteSearch } from '../search/SiteSearch'
import { HeaderMenu } from './HeaderMenu'
import { MobileNavSheet } from './MobileNavSheet'
import { groupIsActive, navGroupsWithPortfolio, visibleGroups, type NavGroup } from './navModel'

/** True when a key press belongs to whatever the user is typing into, not to the page. */
function isTypingTarget(target: EventTarget | null): boolean {
  return target instanceof Element
    && !!target.closest('input, textarea, select, [contenteditable="true"]')
}

interface SiteHeaderProps {
  groups?: NavGroup[]
}

/**
 * The public site's header: a floating capsule with four grouped menus, the site search, the
 * theme toggle and "Ask Simon anything". Below 900px it collapses to the mark, an Ask button
 * and a menu button that opens {@link MobileNavSheet}.
 *
 * Two class names are load-bearing for the guided tour and must survive any restyle:
 * `top-nav__ask-ai` on the Ask button (`tourActions.ts`, `TourOverlay.tsx`), and `tour-search`,
 * which `SiteSearch` carries itself — the tour's search step types into that very input, which
 * is why search stays a visible field here rather than an icon that opens one.
 */
export function SiteHeader({ groups }: SiteHeaderProps) {
  const [openGroup, setOpenGroup] = useState<string | null>(null)
  const [sheetOpen, setSheetOpen] = useState(false)
  const [sheetFocusesSearch, setSheetFocusesSearch] = useState(false)
  const [scrolled, setScrolled] = useState(false)
  const headerRef = useRef<HTMLElement>(null)
  const location = useLocation()
  const { openChat } = useChat()
  const { theme, toggleTheme } = useTheme()
  const { start: startTour } = useTour()
  const isAdmin = useAdminRole()
  const portfolio = usePortfolio()
  const menus = visibleGroups(groups ?? navGroupsWithPortfolio(portfolio.projects, portfolio.error !== null))

  useEffect(() => {
    setOpenGroup(null)
    setSheetOpen(false)
  }, [location.pathname, location.hash])

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpenGroup(null)
        return
      }
      const isShortcut = (event.key === '/' && !event.metaKey && !event.ctrlKey && !event.altKey)
        || (event.key.toLowerCase() === 'k' && (event.metaKey || event.ctrlKey))
      if (!isShortcut || isTypingTarget(event.target)) return
      const input = headerRef.current?.querySelector<HTMLInputElement>('.site-header__search input')
      event.preventDefault()
      // offsetParent is null while the desktop field is display:none, i.e. at phone widths,
      // where search lives at the top of the menu sheet instead.
      if (input && input.offsetParent !== null) {
        input.focus()
      } else {
        setSheetFocusesSearch(true)
        setSheetOpen(true)
      }
    }
    const onPointerDown = (event: PointerEvent) => {
      if (!headerRef.current?.contains(event.target as Node)) setOpenGroup(null)
    }
    window.addEventListener('keydown', onKeyDown)
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [])

  const tourButton = (
    <button
      className="header-menu__footer-button"
      onClick={() => {
        setOpenGroup(null)
        void startTour()
      }}
      type="button"
    >
      <Compass aria-hidden="true" size={15} /> Take a tour
    </button>
  )

  return (
    <>
      <header className={`site-header${scrolled ? ' site-header--scrolled' : ''}`} ref={headerRef}>
        <nav aria-label="Main navigation" className="site-header__capsule">
          <Link aria-label="Simon Rowe homepage" className="site-header__brand" to="/">
            <span className="site-header__brand-mark">SR</span>
            <span className="site-header__brand-name">Simon Rowe</span>
          </Link>

          <ul className="site-header__menus">
            {menus.map(group => (
              <HeaderMenu
                active={groupIsActive(group, location.pathname)}
                footer={group.key === 'about' ? tourButton : undefined}
                group={group}
                key={group.key}
                onOpenChange={open => setOpenGroup(current => {
                  if (open) return group.key
                  return current === group.key ? null : current
                })}
                open={openGroup === group.key}
              />
            ))}
          </ul>

          <div className="site-header__actions">
            <div className="site-header__search">
              <SiteSearch compact onChatStart={openChat} />
              <kbd aria-hidden="true" className="site-header__search-hint">/</kbd>
            </div>
            <button
              aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
              className="site-header__icon-button site-header__desktop-only"
              onClick={toggleTheme}
              type="button"
            >
              {theme === 'dark' ? <Sun size={19} /> : <Moon size={19} />}
            </button>
            {isAdmin ? (
              <NavLink aria-label="Admin" className="site-header__icon-button site-header__desktop-only" to="/admin">
                <UserCircle size={22} />
              </NavLink>
            ) : null}
            <button
              aria-label="Ask Simon anything"
              className="site-header__ask top-nav__ask-ai"
              data-testid="open-chat"
              onClick={() => openChat()}
              type="button"
            >
              <Sparkles aria-hidden="true" size={16} />
              <span className="site-header__ask-label">Ask Simon anything</span>
            </button>
            <button
              aria-expanded={sheetOpen}
              aria-label="Open menu"
              className="site-header__icon-button site-header__mobile-only"
              onClick={() => {
                setSheetFocusesSearch(false)
                setSheetOpen(true)
              }}
              type="button"
            >
              <Menu size={22} />
            </button>
          </div>
        </nav>
      </header>

      <MobileNavSheet
        focusSearch={sheetFocusesSearch}
        groups={menus}
        isAdmin={isAdmin}
        onClose={() => setSheetOpen(false)}
        open={sheetOpen}
      />
    </>
  )
}
