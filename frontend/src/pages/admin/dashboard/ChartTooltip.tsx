import type { ReactNode } from 'react'

/** A label/value line inside a tooltip, with an optional colour swatch for the series. */
export function TooltipRow({ label, value, swatch }: {
  label: string
  value: ReactNode
  swatch?: string
}) {
  return (
    <div className="admin-dash__tooltip-row">
      <span>
        {swatch && <i className="admin-dash__swatch" style={{ background: swatch }} />}
        {label}
      </span>
      <strong>{value}</strong>
    </div>
  )
}
