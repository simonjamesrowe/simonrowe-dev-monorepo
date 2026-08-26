import { useCallback, useEffect, useState } from 'react'

import { useAuth } from '../auth/useAuth'
import { addFavourite, getFavouriteIds, removeFavourite } from '../services/favouritesApi'
import type { FavouriteContentType } from '../types/favourites'
import { useEnsureAuthenticated } from './useEnsureAuthenticated'

/**
 * Owns the favourite-id set for one content type ('news' or 'events').
 *
 * Favourites are globally shared: the id set is loaded for every visitor (logged in or
 * not), so hearts render filled for everyone. Toggling a favourite still requires a
 * session — logged out, a toggle first runs the Auth0 login popup (the page never
 * navigates away) then completes the pending action. Toggles apply optimistically and
 * revert if the API call fails.
 */
export function useFavourites(type: FavouriteContentType) {
  const { getAccessToken } = useAuth()
  const [favouriteIds, setFavouriteIds] = useState<Set<string>>(new Set())
  const [loading, setLoading] = useState(false)

  const loadIds = useCallback(async () => {
    setLoading(true)
    try {
      const ids = await getFavouriteIds(type)
      setFavouriteIds(new Set(ids))
    } catch {
      // Leave the current set untouched — hearts just stay as they are.
    } finally {
      setLoading(false)
    }
  }, [type])

  useEffect(() => {
    void loadIds()
  }, [loadIds])

  // Shared with the article summary button, which needs the identical
  // popup-then-confirm-with-a-token sequence. Still re-exported below so every existing
  // caller of this hook is unchanged.
  const ensureAuthenticated = useEnsureAuthenticated()

  const isFavourite = useCallback((id: string) => favouriteIds.has(id), [favouriteIds])

  const toggleFavourite = useCallback(
    async (id: string) => {
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
    [ensureAuthenticated, favouriteIds, getAccessToken, type],
  )

  return { isFavourite, toggleFavourite, ensureAuthenticated, loading }
}
