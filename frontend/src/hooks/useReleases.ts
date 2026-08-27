import { useCallback, useEffect, useState } from 'react'

import { fetchReleases } from '../services/platformApi'
import type { Release } from '../types/platform'

interface UseReleasesResult {
  releases: Release[]
  loading: boolean
  error: string | null
  retry: () => void
}

export function useReleases(limit?: number): UseReleasesResult {
  const [releases, setReleases] = useState<Release[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const fetched = await fetchReleases(limit)
        if (!cancelled) setReleases(fetched)
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load releases.')
          setReleases([])
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()

    return () => {
      cancelled = true
    }
  }, [attempt, limit])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  return { releases, loading, error, retry }
}
