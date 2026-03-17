import { useEffect, useRef, useState } from 'react'
import { X } from 'lucide-react'

interface TagInputProps {
  options: Array<{ id: string; name: string }>
  selected: string[]
  onChange: (selected: string[]) => void
  placeholder?: string
}

export function TagInput({ options, selected, onChange, placeholder = 'Type to search...' }: TagInputProps) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [highlightedIndex, setHighlightedIndex] = useState(-1)
  const containerRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const filtered = options.filter(
    (opt) => !selected.includes(opt.id) && opt.name.toLowerCase().includes(query.toLowerCase()),
  )

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const selectOption = (id: string) => {
    onChange([...selected, id])
    setQuery('')
    setHighlightedIndex(-1)
    inputRef.current?.focus()
  }

  const removeOption = (id: string) => {
    onChange(selected.filter((s) => s !== id))
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setOpen(true)
      setHighlightedIndex((i) => Math.min(i + 1, filtered.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setHighlightedIndex((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (highlightedIndex >= 0 && highlightedIndex < filtered.length) {
        selectOption(filtered[highlightedIndex].id)
      }
    } else if (e.key === 'Escape') {
      setOpen(false)
      setHighlightedIndex(-1)
    } else if (e.key === 'Backspace' && query === '' && selected.length > 0) {
      removeOption(selected[selected.length - 1])
    }
  }

  const selectedOptions = selected
    .map((id) => options.find((o) => o.id === id))
    .filter(Boolean) as Array<{ id: string; name: string }>

  return (
    <div className="tag-input" ref={containerRef}>
      <div
        className="tag-input__selected"
        onClick={() => inputRef.current?.focus()}
      >
        {selectedOptions.map((opt) => (
          <span key={opt.id} className="tag-input__pill">
            {opt.name}
            <button
              type="button"
              className="tag-input__pill-remove"
              onClick={(e) => { e.stopPropagation(); removeOption(opt.id) }}
              aria-label={`Remove ${opt.name}`}
            >
              <X size={14} />
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          type="text"
          className="tag-input__input"
          value={query}
          onChange={(e) => { setQuery(e.target.value); setOpen(true); setHighlightedIndex(-1) }}
          onFocus={() => setOpen(true)}
          onKeyDown={handleKeyDown}
          placeholder={selectedOptions.length === 0 ? placeholder : ''}
          role="combobox"
          aria-expanded={open}
        />
      </div>

      {open && filtered.length > 0 && (
        <div className="tag-input__dropdown">
          {filtered.map((opt, index) => (
            <div
              key={opt.id}
              className={`tag-input__option${index === highlightedIndex ? ' tag-input__option--highlighted' : ''}`}
              onClick={() => selectOption(opt.id)}
              role="option"
              aria-selected={index === highlightedIndex}
            >
              {opt.name}
            </div>
          ))}
        </div>
      )}

      {open && query && filtered.length === 0 && (
        <div className="tag-input__dropdown">
          <div className="tag-input__empty">No matches found</div>
        </div>
      )}
    </div>
  )
}
