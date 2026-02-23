import { useCallback, useEffect, useRef, useState } from 'react'
import { siteSearch, type GroupedSearchResponse } from '../../services/searchApi'
import { SearchDropdown } from './SearchDropdown'

const DEBOUNCE_MS = 300
const MIN_QUERY_LENGTH = 2

export function SiteSearch() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<GroupedSearchResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (query.length < MIN_QUERY_LENGTH) {
      setResults(null)
      setOpen(false)
      setLoading(false)
      return
    }

    setLoading(true)
    timerRef.current = setTimeout(() => {
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller

      siteSearch(query, controller.signal)
        .then((data) => {
          if (!controller.signal.aborted) {
            setResults(data)
            setOpen(true)
            setLoading(false)
          }
        })
        .catch(() => {
          if (!controller.signal.aborted) {
            setLoading(false)
          }
        })
    }, DEBOUNCE_MS)

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
      }
    }
  }, [query])

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      setOpen(false)
    }
  }, [])

  const handleResultClick = useCallback(() => {
    setOpen(false)
    setQuery('')
  }, [])

  const hasResults = results && (
    (results.blogs && results.blogs.length > 0) ||
    (results.jobs && results.jobs.length > 0) ||
    (results.skills && results.skills.length > 0)
  )

  return (
    <div className="site-search" ref={containerRef}>
      <input
        aria-label="Search across all content"
        className="site-search__input"
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Search..."
        type="search"
        value={query}
      />
      {open && query.length >= MIN_QUERY_LENGTH && (
        <SearchDropdown
          hasResults={!!hasResults}
          loading={loading}
          onResultClick={handleResultClick}
          results={results}
        />
      )}
    </div>
  )
}
