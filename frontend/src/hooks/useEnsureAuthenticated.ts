import { useCallback } from 'react'

import { useAuth } from '../auth/useAuth'

/**
 * Ensures a session exists, running the Auth0 login popup when needed. The page never
 * navigates away, so whatever the caller was doing survives.
 *
 * Extracted from `useFavourites`, which was its only consumer until the article summary
 * button needed the identical sequence. The token confirmation below is the whole point of
 * the extraction: it is a trap that is easy to reimplement wrongly.
 *
 * @returns a callback resolving true when authenticated, false when the popup was dismissed
 *     or failed
 */
export function useEnsureAuthenticated(): () => Promise<boolean> {
  const { isAuthenticated, getAccessToken, loginWithPopup } = useAuth()

  /**
   * Resolves true when authenticated; false when the popup was dismissed or failed.
   * auth0-react swallows popup errors (it resolves even on cancel), so a session is
   * confirmed by actually obtaining a token afterwards.
   */
  return useCallback(async (): Promise<boolean> => {
    if (isAuthenticated) return true
    try {
      await loginWithPopup()
      await getAccessToken()
      return true
    } catch {
      return false
    }
  }, [getAccessToken, isAuthenticated, loginWithPopup])
}
