import { Auth0Provider } from '@auth0/auth0-react'
import type { ReactNode } from 'react'

import { AUTH0_AUDIENCE, AUTH0_CLIENT_ID, AUTH0_DOMAIN } from '../config/auth'

interface AuthProviderProps {
  children: ReactNode
}

export function AuthProvider({ children }: AuthProviderProps) {
  return (
    <Auth0Provider
      domain={AUTH0_DOMAIN}
      clientId={AUTH0_CLIENT_ID}
      authorizationParams={{
        redirect_uri: window.location.origin + '/admin',
        audience: AUTH0_AUDIENCE,
      }}
    >
      {children}
    </Auth0Provider>
  )
}
