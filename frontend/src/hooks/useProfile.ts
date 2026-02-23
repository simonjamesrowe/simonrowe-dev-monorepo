import { useCallback, useEffect, useState } from 'react'

import { fetchProfile } from '../services/profileApi'
import type { Profile } from '../types/Profile'

interface UseProfileResult {
  profile: Profile | null
  loading: boolean
  error: string | null
  retry: () => void
}

export function useProfile(): UseProfileResult {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false

    const loadProfile = async () => {
      setLoading(true)
      setError(null)

      try {
        const fetchedProfile = await fetchProfile()
        if (!cancelled) {
          setProfile(fetchedProfile)
        }
      } catch (loadError) {
        if (!cancelled) {
          const message = loadError instanceof Error ? loadError.message : 'Unable to load profile data.'
          setError(message)
          setProfile(null)
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadProfile()

    return () => {
      cancelled = true
    }
  }, [attempt])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  return { profile, loading, error, retry }
}
