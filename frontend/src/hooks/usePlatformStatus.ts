import { useCallback, useEffect, useState } from 'react'

import { fetchPlatformStatus } from '../services/platformApi'
import type { PlatformStatus } from '../types/platform'

interface UsePlatformStatusResult {
  status: PlatformStatus | null
  loading: boolean
  error: string | null
  retry: () => void
}

export function usePlatformStatus(): UsePlatformStatusResult {
  const [status, setStatus] = useState<PlatformStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const fetched = await fetchPlatformStatus()
        if (!cancelled) setStatus(fetched)
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Unable to load platform status.')
          setStatus(null)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()

    return () => {
      cancelled = true
    }
  }, [attempt])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  return { status, loading, error, retry }
}
