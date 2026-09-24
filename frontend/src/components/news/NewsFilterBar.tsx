import { useEffect, useRef, useState } from 'react'
import { Check, ChevronDown, Heart, Search, X } from 'lucide-react'

import type { SourceSummary } from '../../types/news'

interface NewsFilterBarProps {
  /** Every source the site holds, busiest first. */
  sources: SourceSummary[]
  /** The selected source names. Empty means every source, which is the default. */
  selectedSources: string[]
  onSelectedSourcesChange: (next: string[]) => void
  /** The raw text in the box, echoed back on every keystroke. */
  query: string
  onQueryChange: (next: string) => void
  eventsOnly: boolean
  onEventsOnlyChange: (next: boolean) => void
  /** False when the feed holds no events at all, which hides the toggle entirely. */
  showEventsToggle: boolean
  favouritesOnly: boolean
  /** Favourites are globally shared, so viewing them needs no session — just flip the view. */
  onFavouritesOnlyChange: (next: boolean) => void
}

/**
 * Describes the current source selection in the space of a button.
 *
 * <p>Naming the single selected source matters more than it looks: the row this replaced
 * showed the active source as a filled pill, and the one thing a dropdown loses by default
 * is that the filter is visible without opening it.
 */
function sourcesLabel(selected: string[]): string {
  if (selected.length === 0) return 'All sources'
  if (selected.length === 1) return selected[0]
  return `${selected.length} sources`
}

/**
 * The feed's filter row: a free-text box, a multi-select of sources, and the two view
 * toggles.
 *
 * <p>It replaced a pill per source. That worked while there were four sources and stopped
 * working at sixteen — the row wrapped onto three lines, half of it lived behind a "More"
 * overflow, and only one source could be selected at a time. A dropdown costs one click to
 * open and buys the whole list, several sources at once, and room for the search box that
 * was the actual thing missing.
 */
export function NewsFilterBar({
  sources,
  selectedSources,
  onSelectedSourcesChange,
  query,
  onQueryChange,
  eventsOnly,
  onEventsOnlyChange,
  showEventsToggle,
  favouritesOnly,
  onFavouritesOnlyChange,
}: NewsFilterBarProps) {
  const [open, setOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [open])

  // The toggle advertises aria-haspopup, so Escape has to close it and hand focus back —
  // otherwise a keyboard user who opens it just tabs on into the page with it still open.
  useEffect(() => {
    if (!open) return
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        toggleRef.current?.focus()
      }
    }
    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [open])

  const toggleSource = (name: string) => {
    onSelectedSourcesChange(
      selectedSources.includes(name)
        ? selectedSources.filter(selected => selected !== name)
        : [...selectedSources, name],
    )
  }

  return (
    <div className="feed__toolbar tour-news-filters">
      <div className="feed__search">
        <Search aria-hidden="true" className="feed__search-icon" size={16} />
        <input
          aria-label="Search news and events"
          className="feed__search-input"
          onChange={event => onQueryChange(event.target.value)}
          placeholder="Search news and events"
          /* type="search" rather than text: it brings the platform's own clear affordance
             and the right virtual keyboard, and the explicit button below covers the
             browsers that render neither. */
          type="search"
          value={query}
        />
        {query && (
          <button
            aria-label="Clear search"
            className="feed__search-clear"
            onClick={() => onQueryChange('')}
            type="button"
          >
            <X aria-hidden="true" size={14} />
          </button>
        )}
      </div>

      <div className="feed__sources" ref={menuRef}>
        <button
          aria-expanded={open}
          aria-haspopup="true"
          className={`feed__pill feed__sources-toggle${
            selectedSources.length > 0 ? ' feed__pill--active' : ''
          }`}
          onClick={() => setOpen(isOpen => !isOpen)}
          ref={toggleRef}
          type="button"
        >
          <span>{sourcesLabel(selectedSources)}</span>
          <ChevronDown aria-hidden="true" size={14} />
        </button>
        {open && (
          <div aria-label="Filter by source" className="feed__sources-menu" role="group">
            <div className="feed__sources-actions">
              <span className="feed__sources-heading">Sources</span>
              <button
                className="feed__sources-clear"
                /* Disabled rather than hidden, so the panel does not reflow the instant
                   the last source is unticked and move the row under the pointer. */
                disabled={selectedSources.length === 0}
                onClick={() => onSelectedSourcesChange([])}
                type="button"
              >
                Clear
              </button>
            </div>
            <div className="feed__sources-list">
              {sources.map(({ name, count }) => {
                const checked = selectedSources.includes(name)
                return (
                  <label className="feed__sources-option" key={name}>
                    <input
                      checked={checked}
                      className="feed__sources-checkbox"
                      onChange={() => toggleSource(name)}
                      type="checkbox"
                    />
                    <span aria-hidden="true" className="feed__sources-box">
                      {checked && <Check size={12} strokeWidth={3} />}
                    </span>
                    <span className="feed__sources-name">{name}</span>
                    <span className="feed__more-count">{count}</span>
                  </label>
                )
              })}
            </div>
          </div>
        )}
      </div>

      {showEventsToggle && (
        <button
          aria-pressed={eventsOnly}
          className={`feed__pill feed__pill--events${eventsOnly ? ' feed__pill--active' : ''}`}
          onClick={() => onEventsOnlyChange(!eventsOnly)}
          type="button"
        >
          Events
        </button>
      )}

      <button
        aria-pressed={favouritesOnly}
        className={`feed__pill feed__favourites-toggle${
          favouritesOnly ? ' feed__pill--active' : ''
        }`}
        onClick={() => onFavouritesOnlyChange(!favouritesOnly)}
        type="button"
      >
        <Heart aria-hidden="true" fill={favouritesOnly ? 'currentColor' : 'none'} size={14} />
        <span>Show favourites only</span>
      </button>
    </div>
  )
}
