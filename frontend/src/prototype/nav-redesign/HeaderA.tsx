/**
 * PROTOTYPE — Variant A: floating capsule header with small dropdown panels (adpower-style),
 * a floating "Ask Simon anything" pill, and a full-screen accordion sheet on mobile.
 */
import { ChevronDown, Compass, Menu, Moon, Search, Sparkles, Sun, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useLocation } from 'react-router-dom'

import { useChat } from '../../contexts/ChatContext'
import { useTheme } from '../../contexts/ThemeContext'
import { useTour } from '../../hooks/useTour'
import { SiteSearch } from '../../components/search/SiteSearch'
import { NAV_GROUPS, groupIsActive, type NavGroup } from './data'
import { Silhouette } from './Silhouette'

export function HeaderA() {
  const [openGroup, setOpenGroup] = useState<string | null>(null)
  const [searchOpen, setSearchOpen] = useState(false)
  const [sheetOpen, setSheetOpen] = useState(false)
  const [expanded, setExpanded] = useState<string | null>('about')
  const [scrolled, setScrolled] = useState(false)
  const headerRef = useRef<HTMLElement>(null)
  const location = useLocation()
  const { openChat } = useChat()
  const { theme, toggleTheme } = useTheme()
  const { start: startTour } = useTour()

  useEffect(() => {
    setOpenGroup(null)
    setSearchOpen(false)
    setSheetOpen(false)
  }, [location.pathname, location.hash])

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpenGroup(null)
        setSearchOpen(false)
        setSheetOpen(false)
      }
    }
    const onPointer = (event: MouseEvent) => {
      if (!headerRef.current?.contains(event.target as Node)) {
        setOpenGroup(null)
        setSearchOpen(false)
      }
    }
    window.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onPointer)
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onPointer)
    }
  }, [])

  useEffect(() => {
    document.body.style.overflow = sheetOpen ? 'hidden' : ''
    return () => { document.body.style.overflow = '' }
  }, [sheetOpen])

  const renderPanel = (group: NavGroup) => (
    <div className={`pa-panel pa-panel--${group.key}`} role="region" aria-label={`${group.label} menu`}>
      <ul className="pa-panel__list">
        {group.items.map(item => {
          const Icon = item.icon
          return (
            <li key={item.label}>
              <Link className="pa-panel__item" to={item.to}>
                {item.project
                  ? <Silhouette project={item.project} size="sm" hideBadge />
                  : <span className="pa-panel__icon"><Icon size={18} /></span>}
                <span className="pa-panel__text">
                  <span className="pa-panel__label">
                    {item.label}
                    {item.comingSoon ? <span className="proto-badge proto-badge--inline">Soon</span> : null}
                  </span>
                  <span className="pa-panel__desc">{item.description}</span>
                </span>
              </Link>
            </li>
          )
        })}
      </ul>
      {group.overview ? (
        <Link className="pa-panel__footer" to={group.overview.to}>{group.overview.label} &rarr;</Link>
      ) : null}
      {group.key === 'about' ? (
        <button className="pa-panel__footer pa-panel__footer--button" onClick={() => void startTour()} type="button">
          <Compass size={15} /> Take the guided tour
        </button>
      ) : null}
    </div>
  )

  return (
    <>
      <header
        className={`pa-header${scrolled ? ' is-scrolled' : ''}`}
        onMouseLeave={() => setOpenGroup(null)}
        ref={headerRef}
      >
        <nav className="pa-capsule" aria-label="Main navigation">
          <Link to="/" className="top-nav__brand" aria-label="Simon Rowe homepage">
            <span className="top-nav__brand-mark">SR</span>
            <span className="top-nav__brand-name">Simon Rowe</span>
          </Link>

          <ul className="pa-menus">
            {NAV_GROUPS.map(group => (
              <li className="pa-menus__item" key={group.key} onMouseEnter={() => setOpenGroup(group.key)}>
                <button
                  aria-expanded={openGroup === group.key}
                  className={`pa-menus__trigger${groupIsActive(group, location.pathname) ? ' is-active' : ''}${openGroup === group.key ? ' is-open' : ''}`}
                  onClick={() => setOpenGroup(value => (value === group.key ? null : group.key))}
                  type="button"
                >
                  {group.label}
                  <ChevronDown className="pa-menus__chevron" size={15} />
                </button>
                {openGroup === group.key ? renderPanel(group) : null}
              </li>
            ))}
          </ul>

          <div className="pa-actions">
            <button
              aria-expanded={searchOpen}
              aria-label="Search"
              className="nav__theme-toggle pa-desktop-only"
              onClick={() => setSearchOpen(value => !value)}
              type="button"
            >
              <Search size={19} />
            </button>
            <button
              aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
              className="nav__theme-toggle pa-desktop-only"
              onClick={toggleTheme}
              type="button"
            >
              {theme === 'dark' ? <Sun size={19} /> : <Moon size={19} />}
            </button>
            <button className="proto-ask-cta pa-desktop-only" onClick={() => openChat()} type="button">
              <Sparkles size={16} /> Ask Simon anything
            </button>
            <button aria-label="Ask Simon anything" className="proto-spark-btn pa-mobile-only" onClick={() => openChat()} type="button">
              <Sparkles size={18} />
            </button>
            <button aria-label="Open menu" className="nav__theme-toggle pa-mobile-only" onClick={() => setSheetOpen(true)} type="button">
              <Menu size={22} />
            </button>
          </div>
        </nav>

        {searchOpen ? (
          <div className="pa-search">
            <SiteSearch onChatStart={openChat} />
          </div>
        ) : null}
      </header>

      <div className={`pa-sheet${sheetOpen ? ' is-open' : ''}`} aria-hidden={!sheetOpen}>
        <div className="pa-sheet__top">
          <Link to="/" className="top-nav__brand" aria-label="Simon Rowe homepage">
            <span className="top-nav__brand-mark">SR</span>
            <span className="top-nav__brand-name">Simon Rowe</span>
          </Link>
          <button aria-label="Close menu" className="nav__theme-toggle" onClick={() => setSheetOpen(false)} type="button">
            <X size={24} />
          </button>
        </div>
        <div className="pa-sheet__search"><SiteSearch onChatStart={openChat} /></div>
        <ul className="pa-sheet__groups">
          {NAV_GROUPS.map(group => (
            <li key={group.key}>
              <button
                aria-expanded={expanded === group.key}
                className="pa-sheet__group"
                onClick={() => setExpanded(value => (value === group.key ? null : group.key))}
                type="button"
              >
                {group.label}
                <ChevronDown className={`pa-menus__chevron${expanded === group.key ? ' is-flipped' : ''}`} size={20} />
              </button>
              {expanded === group.key ? (
                <ul className="pa-sheet__items">
                  {group.items.map(item => (
                    <li key={item.label}>
                      <NavLink className="pa-sheet__item" to={item.to}>
                        {item.label}
                        {item.comingSoon ? <span className="proto-badge proto-badge--inline">Soon</span> : null}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          ))}
        </ul>
        <div className="pa-sheet__bottom">
          <button className="pa-sheet__row" onClick={toggleTheme} type="button">
            {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
            {theme === 'dark' ? 'Light mode' : 'Dark mode'}
          </button>
          <button className="proto-ask-cta proto-ask-cta--block" onClick={() => { setSheetOpen(false); openChat() }} type="button">
            <Sparkles size={16} /> Ask Simon anything
          </button>
        </div>
      </div>

      <FloatingAskPill />
    </>
  )
}

/** The adpower "Try our new AI Planner · Start chat" pill, shown once the hero chat has gone. */
function FloatingAskPill() {
  const { pathname } = useLocation()
  const { openChat } = useChat()
  const [visible, setVisible] = useState(pathname !== '/')

  useEffect(() => {
    if (pathname !== '/') {
      setVisible(true)
      return
    }
    const onScroll = () => setVisible(window.scrollY > window.innerHeight * 0.55)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [pathname])

  return (
    <div className={`pa-askpill${visible ? ' is-visible' : ''}`}>
      <button className="pa-askpill__desktop" onClick={() => openChat()} type="button">
        <span className="pa-askpill__lead">Ask Simon</span>
        <Sparkles size={16} />
        <span className="pa-askpill__strong">anything</span>
        <span className="pa-askpill__start">Start chat</span>
      </button>
      <button aria-label="Ask Simon anything" className="pa-askpill__mobile" onClick={() => openChat()} type="button">
        <Sparkles size={22} />
      </button>
    </div>
  )
}
