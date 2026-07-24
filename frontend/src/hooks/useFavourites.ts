import { useCallback, useEffect, useState } from 'react'

import { useAuth } from '../auth/useAuth'
import { addFavourite, getFavouriteIds, removeFavourite } from '../services/favouritesApi'
import type { FavouriteContentType } from '../types/favourites'

/**
 * Owns the favourite-id set for one content type ('news' or 'events').
 *
 * Logged out, the set is empty (hearts render unfilled) and any toggle first runs the
 * Auth0 login popup — the page never navigates away — then completes the pending action.
 * Toggles apply optimistically and revert if the API call fails.
 */
export function useFavourites(type: FavouriteContentType) {
  const { isAuthenticated, getAccessToken, loginWithPopup } = useAuth()
  const [favouriteIds, setFavouriteIds] = useState<Set<string>>(new Set())
  const [loading, setLoading] = useState(false)

  const loadIds = useCallback(async () => {
    setLoading(true)
    try {
      const ids = await getFavouriteIds(getAccessToken, type)
      setFavouriteIds(new Set(ids))
    } catch {
      // Leave the current set untouched — hearts just stay as they are.
    } finally {
      setLoading(false)
    }
  }, [getAccessToken, type])

  useEffect(() => {
    if (isAuthenticated) {
      void loadIds()
    } else {
      setFavouriteIds(new Set())
    }
  }, [isAuthenticated, loadIds])

  /**
   * Ensures a session exists, running the login popup when needed.
   * Resolves true when authenticated; false when the popup was dismissed or failed.
   * auth0-react swallows popup errors (it resolves even on cancel), so a session is
   * confirmed by actually obtaining a token afterwards.
   */
  const ensureAuthenticated = useCallback(async (): Promise<boolean> => {
    if (isAuthenticated) return true
    try {
      await loginWithPopup()
      await getAccessToken()
      return true
    } catch {
      return false
    }
  }, [getAccessToken, isAuthenticated, loginWithPopup])

  const isFavourite = useCallback((id: string) => favouriteIds.has(id), [favouriteIds])

  const toggleFavourite = useCallback(
    async (id: string) => {
      const wasAuthenticated = isAuthenticated
      if (!(await ensureAuthenticated())) return

      const removing = favouriteIds.has(id)
      setFavouriteIds(prev => {
        const next = new Set(prev)
        if (removing) {
          next.delete(id)
        } else {
          next.add(id)
        }
        return next
      })
      try {
        if (removing) {
          await removeFavourite(getAccessToken, type, id)
        } else {
          await addFavourite(getAccessToken, type, id)
        }
        if (!wasAuthenticated) {
          // Fresh login: sync with whatever this user had favourited on other devices.
          await loadIds()
        }
      } catch {
        setFavouriteIds(prev => {
          const next = new Set(prev)
          if (removing) {
            next.add(id)
          } else {
            next.delete(id)
          }
          return next
        })
      }
    },
    [ensureAuthenticated, favouriteIds, getAccessToken, isAuthenticated, loadIds, type],
  )

  return { isFavourite, toggleFavourite, ensureAuthenticated, loading }
}
