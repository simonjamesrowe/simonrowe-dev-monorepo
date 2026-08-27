import { ChevronDown } from 'lucide-react'
import { useState } from 'react'
import type { ReactNode } from 'react'

interface CollapsibleSectionProps {
  title: string
  defaultOpen: boolean
  children: ReactNode
  /** Shown next to the title so a collapsed section still says how much is inside. */
  count?: number
}

/**
 * A section header that toggles its own content, used to let a long status page default
 * to showing only what matters most (`Recent releases`) while hiding the long tail
 * (`Platform components`) behind one click.
 */
export function CollapsibleSection({ title, defaultOpen, children, count }: CollapsibleSectionProps) {
  const [open, setOpen] = useState(defaultOpen)

  return (
    <div className="collapsible-section">
      <button
        aria-expanded={open}
        className="collapsible-section__header"
        onClick={() => setOpen((current) => !current)}
        type="button"
      >
        <h2 className="collapsible-section__title">
          {title}
          {count !== undefined ? <span className="collapsible-section__count">{count}</span> : null}
        </h2>
        <ChevronDown
          aria-hidden="true"
          className={`collapsible-section__chevron${open ? ' collapsible-section__chevron--open' : ''}`}
          size={18}
        />
      </button>
      {open ? <div className="collapsible-section__content">{children}</div> : null}
    </div>
  )
}
