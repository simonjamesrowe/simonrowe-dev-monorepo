import { useAuth0 } from '@auth0/auth0-react'

export const ROLES_CLAIM = 'https://simonrowe.dev/roles'
export const ADMIN_ROLE = 'DEV_PORTAL_ADMIN'

export function useAdminRole(): boolean {
  const { user } = useAuth0()
  const roles = user?.[ROLES_CLAIM]
  return Array.isArray(roles) && roles.includes(ADMIN_ROLE)
}
