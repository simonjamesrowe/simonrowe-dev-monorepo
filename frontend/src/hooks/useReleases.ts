import { useCallback, useEffect, useRef, useState } from 'react'

import { fetchReleases, RELEASE_PAGE_SIZE } from '../services/platformApi'
import type { Release } from '../types/platform'

/** Matches the news page: long enough to skip the keystrokes of a word being typed. */
const SEARCH_DEBOUNCE_MS = 300

export interface UseReleasesResult {
  releases: Release[]
  /** Releases matching the current type and search. */
  totalItems: number
  /** Every stored release, whatever the filters. */
  totalReleases: number
  typeCounts: Record<string, number>
  hasMore: boolean
  loading: boolean
  loadingMore: boolean
  error: string | null
  activeType: string | null
  setActiveType: (type: string | null) => void
  query: string
  setQuery: (query: string) => void
  loadMore: () => void
  retry: () => void
}

/**
 * The changelog, a page at a time, filtered by type and free text on the server.
 *
 * It used to fetch the newest 20 and filter those in the browser, so older releases could not
 * be reached and a search only ever searched 20 entries.
 *
 * Every request carries an id, and a response is dropped unless it is still the latest one, so
 * a slow page for an old query can never land on top of the results for the current one.
 */
export function useReleases(): UseReleasesResult {
  const [releases, setReleases] = useState<Release[]>([])
  const [page, setPage] = useState(0)
  const [totalItems, setTotalItems] = useState(0)
  const [totalReleases, setTotalReleases] = useState(0)
  const [typeCounts, setTypeCounts] = useState<Record<string, number>>({})
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [activeType, setActiveType] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')
  const [attempt, setAttempt] = useState(0)
  const requestId = useRef(0)

  // Cleared on every keystroke and on unmount, so a query already moved past never lands.
  useEffect(() => {
    if (query === appliedQuery) return undefined
    const timer = window.setTimeout(() => setAppliedQuery(query), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [query, appliedQuery])

  useEffect(() => {
    const id = requestId.current + 1
    requestId.current = id
    setLoading(true)
    // A "load more" this request supersedes is dropped when it lands, so its own finally
    // cannot clear the flag; this request owns it now.
    setLoadingMore(false)
    setError(null)
    fetchReleases({ page: 0, size: RELEASE_PAGE_SIZE, type: activeType, query: appliedQuery })
      .then((result) => {
        if (requestId.current !== id) return
        setReleases(result.items)
        setPage(result.page)
        setTotalItems(result.totalItems)
        setTotalReleases(result.totalReleases)
        setTypeCounts(result.typeCounts)
        setHasMore(result.page + 1 < result.totalPages)
      })
      .catch((loadError: unknown) => {
        if (requestId.current !== id) return
        setError(loadError instanceof Error ? loadError.message : 'Unable to load releases.')
        setReleases([])
        setHasMore(false)
      })
      .finally(() => {
        if (requestId.current === id) setLoading(false)
      })
  }, [activeType, appliedQuery, attempt])

  const loadMore = useCallback(() => {
    const id = requestId.current + 1
    requestId.current = id
    setLoadingMore(true)
    fetchReleases({
      page: page + 1,
      size: RELEASE_PAGE_SIZE,
      type: activeType,
      query: appliedQuery,
    })
      .then((result) => {
        if (requestId.current !== id) return
        // Append, never replace: the list stays put so the reader keeps their place.
        setReleases((previous) => [...previous, ...result.items])
        setPage(result.page)
        setTotalItems(result.totalItems)
        setHasMore(result.page + 1 < result.totalPages)
      })
      .catch((loadError: unknown) => {
        if (requestId.current !== id) return
        setError(loadError instanceof Error ? loadError.message : 'Unable to load releases.')
      })
      .finally(() => {
        if (requestId.current === id) setLoadingMore(false)
      })
  }, [page, activeType, appliedQuery])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  return {
    releases,
    totalItems,
    totalReleases,
    typeCounts,
    hasMore,
    loading,
    loadingMore,
    error,
    activeType,
    setActiveType,
    query,
    setQuery,
    loadMore,
    retry,
  }
}
