import { ChevronDown } from 'lucide-react'
import { useRef, type ReactNode } from 'react'
import { Link } from 'react-router-dom'

import type { NavGroup } from './navModel'

interface HeaderMenuProps {
  group: NavGroup
  open: boolean
  active: boolean
  onOpenChange: (open: boolean) => void
  /** Rendered under the links — the About menu's "Take a tour", for instance. */
  footer?: ReactNode
}

/**
 * One header menu: a disclosure button and the panel it controls.
 *
 * Deliberately the disclosure pattern (a button with `aria-expanded`/`aria-controls` and a
 * panel of ordinary links), not an ARIA `menu`: `role="menu"` promises arrow-key roving focus
 * and type-ahead, and a panel of four links is navigated perfectly well with Tab.
 *
 * Hover opens it for a mouse only. A touch "hover" is the same gesture as the tap that follows
 * it, so reacting to both would open the panel and immediately toggle it shut. The same is true
 * of a mouse click, which always arrives after the hover that already opened the panel — so a
 * mouse click only ever opens, and a mouse user closes by moving away, Escape or clicking
 * elsewhere. A keyboard or touch activation toggles.
 */
export function HeaderMenu({ group, open, active, onOpenChange, footer }: HeaderMenuProps) {
  const panelId = `header-menu-${group.key}`
  const lastPointerType = useRef<string | null>(null)

  return (
    <li
      className="header-menu"
      onPointerEnter={event => {
        if (event.pointerType === 'mouse') onOpenChange(true)
      }}
      onPointerLeave={event => {
        if (event.pointerType === 'mouse') onOpenChange(false)
      }}
    >
      <button
        aria-controls={panelId}
        aria-expanded={open}
        className={`header-menu__trigger${active ? ' header-menu__trigger--active' : ''}`}
        onClick={() => {
          const byMouse = lastPointerType.current === 'mouse'
          lastPointerType.current = null
          onOpenChange(byMouse ? true : !open)
        }}
        onPointerDown={event => {
          lastPointerType.current = event.pointerType
        }}
        type="button"
      >
        {group.label}
        <ChevronDown aria-hidden="true" className="header-menu__chevron" size={15} />
      </button>
      <div className="header-menu__panel" hidden={!open} id={panelId}>
        <ul className="header-menu__list">
          {group.items.map(item => {
            const Icon = item.icon
            return (
              <li key={item.to + item.label}>
                <Link className="header-menu__item" onClick={() => onOpenChange(false)} to={item.to}>
                  <span aria-hidden="true" className="header-menu__icon"><Icon size={18} /></span>
                  <span className="header-menu__text">
                    <span className="header-menu__label">
                      {item.label}
                      {item.comingSoon ? <span className="soon-badge">Soon</span> : null}
                    </span>
                    <span className="header-menu__description">{item.description}</span>
                  </span>
                </Link>
              </li>
            )
          })}
        </ul>
        {footer ? <div className="header-menu__footer">{footer}</div> : null}
      </div>
    </li>
  )
}
