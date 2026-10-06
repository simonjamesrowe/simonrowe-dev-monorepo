import { useCallback, useEffect, useState } from 'react'

import type {
  AdminListingQuery,
  AdminVisibilityFilter,
  PageResponse,
  SortDirection,
} from '../../services/adminApi'

/** Long enough to skip the keystrokes of a word being typed, short enough to feel live. */
export const SEARCH_DELAY_MS = 300

export const PAGE_SIZES = [20, 50, 100] as const

/**
 * Columns that start ascending when first clicked. Text reads A to Z; anything else is a
 * date, where the interesting end is the newest.
 */
const ASCENDING_FIRST = new Set(['title', 'sourceName'])

export interface AdminListing<T extends { id: string }, S extends string> {
  data: PageResponse<T> | null
  setData: (update: (prev: PageResponse<T> | null) => PageResponse<T> | null) => void
  loading: boolean
  error: string | null
  setError: (message: string | null) => void
  page: number
  setPage: (update: number | ((prev: number) => number)) => void
  search: string
  setSearch: (value: string) => void
  source: string
  setSource: (value: string) => void
  visibility: AdminVisibilityFilter
  setVisibility: (value: AdminVisibilityFilter) => void
  size: number
  setSize: (value: number) => void
  sort: S
  direction: SortDirection
  sortBy: (key: S) => void
  selected: ReadonlySet<string>
  toggleSelected: (id: string) => void
  toggleAllOnPage: () => void
  clearSelection: () => void
  reload: () => Promise<PageResponse<T> | null>
  /** Reloads, then steps back to the last page if the current one no longer exists. */
  reloadClamped: () => Promise<void>
}

/**
 * State for one server-filtered, server-sorted, paged admin table: the filters, the
 * debounced free-text search, the page, and the row selection for bulk actions.
 *
 * Every filter change goes back to page 0, because page 4 of a narrower result is usually
 * empty. The selection is cleared whenever what is listed changes, so a bulk action can
 * never reach rows that are no longer on screen.
 */
export function useAdminListing<T extends { id: string }, S extends string>(
  load: (query: AdminListingQuery<S>) => Promise<PageResponse<T>>,
  defaultSort: S,
  failureMessage: string,
): AdminListing<T, S> {
  const [data, setData] = useState<PageResponse<T> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [source, setSourceState] = useState('')
  const [visibility, setVisibilityState] = useState<AdminVisibilityFilter>('all')
  const [size, setSizeState] = useState<number>(PAGE_SIZES[0])
  const [sort, setSort] = useState<S>(defaultSort)
  const [direction, setDirection] = useState<SortDirection>('desc')
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())

  useEffect(() => {
    const next = search.trim()
    if (next === query) return
    const timer = setTimeout(() => {
      setQuery(next)
      setPage(0)
    }, SEARCH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [search, query])

  const fetchPage = useCallback(
    async (signal?: { cancelled: boolean }): Promise<PageResponse<T> | null> => {
      try {
        setLoading(true)
        setError(null)
        const result = await load({
          page,
          size,
          q: query || undefined,
          sources: source ? [source] : undefined,
          visibility,
          sort,
          direction,
        })
        if (signal?.cancelled) return null
        setData(result)
        return result
      } catch (err) {
        if (!signal?.cancelled) setError(err instanceof Error ? err.message : failureMessage)
        return null
      } finally {
        if (!signal?.cancelled) setLoading(false)
      }
    },
    [load, page, size, query, source, visibility, sort, direction, failureMessage],
  )

  useEffect(() => {
    const signal = { cancelled: false }
    fetchPage(signal)
    return () => {
      signal.cancelled = true
    }
  }, [fetchPage])

  useEffect(() => {
    setSelected(new Set())
  }, [page, size, query, source, visibility, sort, direction])

  const reload = useCallback(() => fetchPage(), [fetchPage])

  const reloadClamped = useCallback(async () => {
    const reloaded = await fetchPage()
    if (reloaded && page > 0 && page > reloaded.totalPages - 1) {
      setPage(Math.max(0, reloaded.totalPages - 1))
    }
  }, [fetchPage, page])

  const sortBy = useCallback(
    (key: S) => {
      if (key === sort) {
        setDirection((prev) => (prev === 'desc' ? 'asc' : 'desc'))
      } else {
        setSort(key)
        setDirection(ASCENDING_FIRST.has(key) ? 'asc' : 'desc')
      }
      setPage(0)
    },
    [sort],
  )

  const pageIds = data?.content.map((item) => item.id) ?? []

  const toggleSelected = useCallback((id: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const allOnPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))
  const toggleAllOnPage = () => {
    setSelected(allOnPageSelected ? new Set() : new Set(pageIds))
  }

  return {
    data,
    setData,
    loading,
    error,
    setError,
    page,
    setPage,
    search,
    setSearch,
    source,
    setSource: (value: string) => {
      setSourceState(value)
      setPage(0)
    },
    visibility,
    setVisibility: (value: AdminVisibilityFilter) => {
      setVisibilityState(value)
      setPage(0)
    },
    size,
    setSize: (value: number) => {
      setSizeState(value)
      setPage(0)
    },
    sort,
    direction,
    sortBy,
    selected,
    toggleSelected,
    toggleAllOnPage,
    clearSelection: () => setSelected(new Set()),
    reload,
    reloadClamped,
  }
}
