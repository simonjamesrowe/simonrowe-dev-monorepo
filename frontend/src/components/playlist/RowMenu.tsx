import { MoreHorizontal } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'

export interface RowMenuAction {
  label: string
  onSelect: () => void
  disabled?: boolean
}

interface RowMenuProps {
  label: string
  actions: RowMenuAction[]
}

/**
 * A row's "more" menu. Also how a row is moved without dragging, which is the only way on a
 * touch screen and the accessible way everywhere.
 */
export function RowMenu({ label, actions }: RowMenuProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const menuId = useId()

  useEffect(() => {
    if (!open) return undefined
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  return (
    <div className="row-menu" ref={rootRef}>
      <button
        aria-controls={menuId}
        aria-expanded={open}
        aria-label={label}
        className="row-menu__trigger"
        onClick={() => setOpen((value) => !value)}
        type="button"
      >
        <MoreHorizontal aria-hidden="true" size={18} />
      </button>
      {open && (
        <ul className="row-menu__list" id={menuId}>
          {actions.map((action) => (
            <li key={action.label}>
              <button
                className="row-menu__item"
                disabled={action.disabled}
                onClick={() => {
                  setOpen(false)
                  action.onSelect()
                }}
                type="button"
              >
                {action.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
