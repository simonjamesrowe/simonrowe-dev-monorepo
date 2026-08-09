import { useEffect, useRef, useState, type JSX } from 'react'

import { ChevronDown } from 'lucide-react'

export interface AdminMenuItem {
  label: string
  title?: string
  onSelect: () => void | Promise<void>
  disabled?: boolean
}

interface AdminMenuProps {
  label: string
  items: AdminMenuItem[]
}

export function AdminMenu({ label, items }: AdminMenuProps): JSX.Element {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
      }
    }

    const handlePointerDown = (e: MouseEvent) => {
      const root = rootRef.current
      if (root && !root.contains(e.target as Node)) {
        setOpen(false)
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    document.addEventListener('mousedown', handlePointerDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.removeEventListener('mousedown', handlePointerDown)
    }
  }, [open])

  const handleSelect = (item: AdminMenuItem) => {
    setOpen(false)
    void item.onSelect()
  }

  return (
    <div className="admin-menu" ref={rootRef}>
      <button
        aria-expanded={open}
        aria-haspopup="menu"
        className="admin-btn admin-btn--secondary admin-menu__trigger"
        onClick={() => setOpen((prev) => !prev)}
        type="button"
      >
        {label}
        <ChevronDown size={14} />
      </button>

      {open && (
        <div className="admin-menu__popover" role="menu">
          {items.map((item) => (
            <button
              className="admin-menu__item"
              disabled={item.disabled}
              key={item.label}
              onClick={() => handleSelect(item)}
              role="menuitem"
              title={item.title}
              type="button"
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
