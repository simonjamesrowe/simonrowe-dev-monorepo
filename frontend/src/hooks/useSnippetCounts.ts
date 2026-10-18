import { useCallback, useEffect, useRef, useState } from 'react'

import { useAuth } from '../auth/useAuth'
import { fetchSnippetCounts } from '../services/snippetsApi'

const NO_COUNTS: Record<string, number> = {}

/**
 * How many snippets each article has, for the badges on the news cards. Only fetched for the
 * admin, so a visitor's page never calls the snippets API; one request covers every card.
 */
export function useSnippetCounts(enabled: boolean) {
  const { getAccessToken } = useAuth()
  // Read through a ref, so the fetch runs once per change of role rather than once per render
  // of a caller whose token function is not stable.
  const tokenRef = useRef(getAccessToken)
  tokenRef.current = getAccessToken
  const [counts, setCounts] = useState<Record<string, number>>(NO_COUNTS)

  useEffect(() => {
    if (!enabled) {
      setCounts(NO_COUNTS)
      return
    }
    let cancelled = false
    fetchSnippetCounts(() => tokenRef.current())
      .then(found => { if (!cancelled) setCounts(found) })
      // A badge is a convenience: a failure leaves the cards as they are.
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [enabled])

  const countFor = useCallback((contentId: string) => counts[contentId] ?? 0, [counts])
  const setCount = useCallback((contentId: string, count: number) => {
    setCounts(previous => (previous[contentId] === count ? previous : { ...previous, [contentId]: count }))
  }, [])

  return { countFor, setCount }
}
