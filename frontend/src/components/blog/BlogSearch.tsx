import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Search } from 'lucide-react'

import { blogSearch, type BlogSearchResult } from '../../services/searchApi'
import { formatDate } from '../../utils/dateFormat'
import { API_BASE_URL } from '../../config/api'

const DEBOUNCE_MS = 300
const MIN_QUERY_LENGTH = 2

export function BlogSearch() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<BlogSearchResult[]>([])
  const [open, setOpen] = useState(false)
  const [noResults, setNoResults] = useState(false)
  const [activeIndex, setActiveIndex] = useState(-1)
  const navigate = useNavigate()
  const containerRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (query.length < MIN_QUERY_LENGTH) {
      setResults([])
      setOpen(false)
      setNoResults(false)
      return
    }

    const timer = setTimeout(() => {
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller

      blogSearch(query, controller.signal)
        .then((data) => {
          setResults(data)
          setNoResults(data.length === 0)
          setOpen(true)
          setActiveIndex(-1)
        })
        .catch(() => {
          // Silently ignore aborted/failed requests
        })
    }, DEBOUNCE_MS)

    return () => clearTimeout(timer)
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

  function handleSelect(result: BlogSearchResult) {
    navigate(result.url)
    setOpen(false)
    setQuery('')
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (!open) return

    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveIndex((prev) => Math.min(prev + 1, results.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((prev) => Math.max(prev - 1, -1))
    } else if (e.key === 'Enter' && activeIndex >= 0) {
      e.preventDefault()
      handleSelect(results[activeIndex])
    } else if (e.key === 'Escape') {
      setOpen(false)
    }
  }

  return (
    <div className="blog-search" ref={containerRef}>
      <div className="blog-search__input-wrap">
        <Search size={16} className="blog-search__icon" />
        <input
          aria-autocomplete="list"
          aria-controls="blog-search-results"
          aria-expanded={open}
          aria-haspopup="listbox"
          aria-label="Search blog posts"
          className="blog-search__input"
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Search blog posts..."
          role="combobox"
          type="search"
          value={query}
        />
      </div>
      {open && (
        <ul
          className="blog-search__results"
          id="blog-search-results"
          role="listbox"
        >
          {noResults ? (
            <li className="blog-search__no-results" role="option" aria-selected={false}>
              No results found
            </li>
          ) : (
            results.map((result, index) => {
              const imgSrc = result.image ? `${API_BASE_URL}${result.image}` : null

              return (
                <li
                  aria-selected={index === activeIndex}
                  className={`blog-search__result${index === activeIndex ? ' blog-search__result--active' : ''}`}
                  key={result.url}
                  onMouseDown={() => handleSelect(result)}
                  role="option"
                >
                  {imgSrc && (
                    <img
                      alt=""
                      className="blog-search__thumbnail"
                      src={imgSrc}
                    />
                  )}
                  <div className="blog-search__result-info">
                    <span className="blog-search__result-title">{result.title}</span>
                    <time className="blog-search__result-date" dateTime={result.publishedDate}>
                      {formatDate(result.publishedDate)}
                    </time>
                  </div>
                </li>
              )
            })
          )}
        </ul>
      )}
    </div>
  )
}
