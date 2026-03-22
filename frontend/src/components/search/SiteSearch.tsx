import { useCallback, useEffect, useRef, useState } from 'react'
import { MessageCircle } from 'lucide-react'
import { siteSearch, type GroupedSearchResponse } from '../../services/searchApi'
import { SearchDropdown } from './SearchDropdown'

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
    } else if (e.key === 'Enter' && query.length >= 1) {
      if (onChatStart) {
        onChatStart(query)
        setOpen(false)
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
    (results.skills && results.skills.length > 0)
  )

  const handleChatClick = useCallback(() => {
    if (query.length >= 1 && onChatStart) {
      onChatStart(query)
      setOpen(false)
      setQuery('')
    }
  }, [query, onChatStart])

  return (
    <div className="site-search tour-search" ref={containerRef}>
      <div className="site-search__input-wrapper">
        <input
          aria-label="Search or ask a question"
          className="site-search__input"
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Search or ask me anything..."
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
      {onChatStart && query.length === 0 && (
        <div className="site-search__suggestions">
          {CHAT_SUGGESTIONS.map((suggestion) => (
            <button
              key={suggestion}
              className="site-search__suggestion"
              onClick={() => onChatStart(suggestion)}
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
