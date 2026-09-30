import { ChevronDown, Moon, Sparkles, Sun, UserCircle, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useLocation } from 'react-router-dom'

import { useChat } from '../../contexts/ChatContext'
import { useTheme } from '../../contexts/ThemeContext'
import { SiteSearch } from '../search/SiteSearch'
import { groupIsActive, type NavGroup } from './navModel'

interface MobileNavSheetProps {
  open: boolean
  onClose: () => void
  groups: NavGroup[]
  isAdmin: boolean
  /** Put focus in the search field on open (the `/` shortcut) rather than on the close button. */
  focusSearch?: boolean
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * The phone-width menu: a full-screen sheet with search first, the header's groups as
 * expandable sections, and "Ask Simon anything" pinned to the bottom.
 *
 * There is no "Take a tour" here, deliberately: the tour is desktop-only, as it always has
 * been — its search step spotlights the header's search field, which phones do not show.
 *
 * It is only mounted while open. That is what keeps a second `SiteSearch` — and so a second
 * `.tour-search` — out of the document the rest of the time, where the tour would otherwise
 * be free to spotlight the hidden one.
 */
export function MobileNavSheet(props: MobileNavSheetProps) {
  if (!props.open) return null
  return <OpenSheet {...props} />
}

function OpenSheet({ onClose, groups, isAdmin, focusSearch }: MobileNavSheetProps) {
  const { pathname } = useLocation()
  const { openChat } = useChat()
  const { theme, toggleTheme } = useTheme()
  const sheetRef = useRef<HTMLDivElement>(null)
  // The parent passes a fresh closure every render; reading it through a ref keeps the
  // open-time effect (scroll lock, initial focus, focus restore) from re-running on each one.
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  const [expanded, setExpanded] = useState<string | null>(
    () => groups.find(group => groupIsActive(group, pathname))?.key ?? null,
  )

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'

    const sheet = sheetRef.current
    const initial = focusSearch
      ? sheet?.querySelector<HTMLElement>('input')
      : sheet?.querySelector<HTMLElement>('.nav-sheet__close')
    initial?.focus()

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab' || !sheet) return
      const focusable = Array.from(sheet.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)

    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = overflow
      previouslyFocused?.focus?.()
    }
  }, [focusSearch])

  return (
    <div aria-label="Menu" aria-modal="true" className="nav-sheet" ref={sheetRef} role="dialog">
      <div className="nav-sheet__top">
        <Link aria-label="Simon Rowe homepage" className="site-header__brand" onClick={onClose} to="/">
          <span className="site-header__brand-mark">SR</span>
          <span className="site-header__brand-name">Simon Rowe</span>
        </Link>
        <button aria-label="Close menu" className="site-header__icon-button nav-sheet__close" onClick={onClose} type="button">
          <X size={24} />
        </button>
      </div>

      <div className="nav-sheet__search">
        <SiteSearch
          onChatStart={query => {
            onClose()
            openChat(query)
          }}
        />
      </div>

      <ul className="nav-sheet__groups">
        {groups.map(group => {
          const isExpanded = expanded === group.key
          const panelId = `nav-sheet-${group.key}`
          return (
            <li key={group.key}>
              <button
                aria-controls={panelId}
                aria-expanded={isExpanded}
                className="nav-sheet__group"
                onClick={() => setExpanded(isExpanded ? null : group.key)}
                type="button"
              >
                {group.label}
                <ChevronDown aria-hidden="true" className="nav-sheet__chevron" size={20} />
              </button>
              <ul className="nav-sheet__items" hidden={!isExpanded} id={panelId}>
                {group.items.map(item => (
                  <li key={item.to + item.label}>
                    <NavLink
                      className={({ isActive }) => `nav-sheet__item${isActive ? ' nav-sheet__item--active' : ''}`}
                      end
                      onClick={onClose}
                      to={item.to}
                    >
                      {item.label}
                      {item.comingSoon ? <span className="soon-badge">Soon</span> : null}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </li>
          )
        })}
      </ul>

      <div className="nav-sheet__bottom">
        <div className="nav-sheet__rows">
          <button className="nav-sheet__row" onClick={toggleTheme} type="button">
            {theme === 'dark' ? <Sun aria-hidden="true" size={18} /> : <Moon aria-hidden="true" size={18} />}
            {theme === 'dark' ? 'Light mode' : 'Dark mode'}
          </button>
          {isAdmin ? (
            <Link className="nav-sheet__row" onClick={onClose} to="/admin">
              <UserCircle aria-hidden="true" size={18} /> Admin
            </Link>
          ) : null}
        </div>
        <button
          className="site-header__ask nav-sheet__ask"
          onClick={() => {
            onClose()
            openChat()
          }}
          type="button"
        >
          <Sparkles aria-hidden="true" size={16} /> Ask Simon anything
        </button>
      </div>
    </div>
  )
}
