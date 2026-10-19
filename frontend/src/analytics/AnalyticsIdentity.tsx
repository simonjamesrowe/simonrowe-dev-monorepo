import { useAuth0 } from '@auth0/auth0-react'
import { useEffect } from 'react'

import { ADMIN_ROLE, ROLES_CLAIM } from '../auth/useAdminRole'
import { clearIdentity, identifyUser } from './analytics'

/**
 * Tells analytics who is signed in. Rendered once inside each app's Auth0Provider; renders nothing.
 *
 * Sends the Auth0 subject and whether it is the site owner, and nothing else from the profile.
 */
export function AnalyticsIdentity() {
  const { isAuthenticated, isLoading, user } = useAuth0()
  const userId = user?.sub
  const roles: unknown = user?.[ROLES_CLAIM]
  const isOwner = Array.isArray(roles) && roles.includes(ADMIN_ROLE)

  useEffect(() => {
    if (isLoading) return
    if (isAuthenticated && userId) identifyUser(userId, isOwner)
    else clearIdentity()
  }, [isAuthenticated, isLoading, userId, isOwner])

  return null
}
