import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { MessageCircle } from 'lucide-react'
import { blogSearch, siteSearch, type GroupedSearchResponse } from '../../services/searchApi'
import { SearchDropdown } from './SearchDropdown'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { useTour } from '../../hooks/useTour'

const DEBOUNCE_MS = 300
const MIN_QUERY_LENGTH = 2

const CHAT_SUGGESTIONS = [
  'What is Simon\'s current role?',
  'What technical skills does Simon have?',
  'Tell me about Simon\'s career history',
  'What has Simon written about?',
  'How can I contact Simon?',
]

interface SiteSearchProps {
  onChatStart?: (query: string) => void
}

export function SiteSearch({ onChatStart }: SiteSearchProps) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<GroupedSearchResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [suggestionsOpen, setSuggestionsOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const { pathname } = useLocation()
  const isBlogPage = pathname.startsWith('/blogs')
  const isNarrow = useMediaQuery('(max-width: 768px)')

  const { isActive: tourActive, searchValue: tourSearchValue, currentStepIndex, steps } = useTour()
  const isSearchTourStep = tourActive && steps[currentStepIndex]?.targetSelector === '.tour-search'

  // Sync tour search simulation into local query state
  useEffect(() => {
    if (isSearchTourStep) {
      // The tour is a visual demonstration. It must not issue real searches or leave a
      // slow response capable of reopening the search popover after the visitor moves on.
      abortRef.current?.abort()
      setResults(null)
      setOpen(false)
      setSuggestionsOpen(false)
      setQuery(tourSearchValue)
    } else if (tourActive) {
      abortRef.current?.abort()
      setQuery('')
      // The search tour drives the input programmatically. Leaving that step must close
      // every search affordance too; otherwise a focus suggestion or stale result panel
      // remains above the next tour stop after the visitor presses Next.
      setOpen(false)
      setSuggestionsOpen(false)
    }
  }, [isSearchTourStep, tourSearchValue, tourActive])

  useEffect(() => {
    if (isSearchTourStep) {
      setResults(null)
      setOpen(false)
      setLoading(false)
      return
    }

    if (query.length < MIN_QUERY_LENGTH) {
      setResults(null)
      setOpen(false)
      setLoading(false)
      return
    }

    setLoading(true)
    let controller: AbortController | null = null
    timerRef.current = setTimeout(() => {
      abortRef.current?.abort()
      const requestController = new AbortController()
      controller = requestController
      abortRef.current = requestController

      const searchPromise = isBlogPage
        ? blogSearch(query, requestController.signal).then((blogs) => ({
            blogs: blogs.map((b) => ({ name: b.title, image: b.image, url: b.url })),
          }))
        : siteSearch(query, requestController.signal)

      searchPromise
        .then((data) => {
          if (!requestController.signal.aborted) {
            setResults(data)
            setOpen(true)
            setLoading(false)
          }
        })
        .catch(() => {
          if (!requestController.signal.aborted) {
            setLoading(false)
          }
        })
    }, DEBOUNCE_MS)

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
      }
      controller?.abort()
    }
  }, [query, isBlogPage, isSearchTourStep])

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
        setSuggestionsOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      setOpen(false)
      setSuggestionsOpen(false)
    } else if (e.key === 'Enter' && query.length >= 1) {
      if (onChatStart) {
        onChatStart(query)
        setOpen(false)
        setSuggestionsOpen(false)
        setQuery('')
      }
    }
  }, [query, onChatStart])

  const handleResultClick = useCallback(() => {
    setOpen(false)
    setQuery('')
  }, [])

  const hasResults = results && (
    (results.blogs && results.blogs.length > 0) ||
    (results.jobs && results.jobs.length > 0) ||
    (results.skills && results.skills.length > 0) ||
    (results.news && results.news.length > 0) ||
    (results.events && results.events.length > 0)
  )

  const handleChatClick = useCallback(() => {
    if (query.length >= 1 && onChatStart) {
      onChatStart(query)
      setOpen(false)
      setSuggestionsOpen(false)
      setQuery('')
    }
  }, [query, onChatStart])

  // The full placeholder clips mid-word once the nav is down to a phone width.
  const placeholder = isBlogPage
    ? (isNarrow ? 'Search posts...' : 'Search blog posts...')
    : (isNarrow ? 'Search...' : 'Search or ask me anything...')

  return (
    <div className="site-search tour-search" ref={containerRef}>
      <div className="site-search__input-wrapper">
        <input
          aria-label="Search or ask a question"
          className="site-search__input"
          onFocus={() => {
            // Tour typing is illustrative, not an invitation to select a prompt. Keeping
            // this popover closed avoids carrying hover/focus text into the following step.
            if (!isSearchTourStep) {
              setSuggestionsOpen(true)
            }
          }}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          type="search"
          value={query}
        />
        {query.length >= 1 && (
          <button
            aria-label="Start chat"
            className="site-search__chat-btn"
            onClick={handleChatClick}
            type="button"
          >
            <MessageCircle size={18} />
          </button>
        )}
      </div>
      {open && query.length >= MIN_QUERY_LENGTH && (
        <SearchDropdown
          hasResults={!!hasResults}
          loading={loading}
          onResultClick={handleResultClick}
          results={results}
        />
      )}
      {onChatStart && suggestionsOpen && query.length === 0 && (
        <div className="site-search__suggestions">
          {CHAT_SUGGESTIONS.map((suggestion) => (
            <button
              key={suggestion}
              className="site-search__suggestion"
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => {
                onChatStart(suggestion)
                setSuggestionsOpen(false)
              }}
              type="button"
            >
              {suggestion}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
