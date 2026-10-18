interface TogglePillProps {
  checked: boolean
  onChange: (checked: boolean) => void
  activeLabel?: string
  inactiveLabel?: string
}

export function TogglePill({
  checked,
  onChange,
  activeLabel = 'Published',
  inactiveLabel = 'Draft',
}: TogglePillProps) {
  return (
    <label className="toggle-pill">
      <input
        type="checkbox"
        role="switch"
        aria-checked={checked}
        className="toggle-pill__checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className={`toggle-pill__track${checked ? ' toggle-pill__track--active' : ''}`}>
        <span className="toggle-pill__thumb" />
      </span>
      <span className={`toggle-pill__label${checked ? ' toggle-pill__label--active' : ''}`}>
        {checked ? activeLabel : inactiveLabel}
      </span>
    </label>
  )
}
