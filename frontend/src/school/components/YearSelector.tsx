interface YearSelectorProps {
  yearGroups: string[]
  selected: string[]
  onChange: (yearGroups: string[]) => void
}

/**
 * Year group picker, multi-select.
 *
 * Multi-select because a parent with children in different years wants both — filtering to one
 * would hide half of what they came for. Checkboxes rather than radios semantically, styled as
 * chips; "All years" is a clear button rather than a separate option, so there is no ambiguous
 * state where nothing and everything look the same.
 *
 * An empty selection means no filter, not "match nothing". Whole-school events always match
 * regardless, which is what stops selecting Year 3 hiding the term dates.
 */
export function YearSelector({ yearGroups, selected, onChange }: YearSelectorProps) {
  function toggle(year: string) {
    onChange(
      selected.includes(year)
        ? selected.filter((y) => y !== year)
        : [...yearGroups].filter((y) => y === year || selected.includes(y)),
    )
  }

  return (
    <fieldset className="school-years">
      <legend className="school-years__legend">
        Which year groups? <span className="school-years__hint">Pick as many as you need.</span>
      </legend>
      <div className="school-years__options">
        <button
          type="button"
          aria-pressed={selected.length === 0}
          className={`school-years__option${
            selected.length === 0 ? ' school-years__option--on' : ''
          }`}
          onClick={() => onChange([])}
        >
          All years
        </button>
        {yearGroups.map((year) => (
          <label
            key={year}
            className={`school-years__option${
              selected.includes(year) ? ' school-years__option--on' : ''
            }`}
          >
            <input
              type="checkbox"
              className="school-years__checkbox"
              checked={selected.includes(year)}
              onChange={() => toggle(year)}
            />
            {year}
          </label>
        ))}
      </div>
    </fieldset>
  )
}
