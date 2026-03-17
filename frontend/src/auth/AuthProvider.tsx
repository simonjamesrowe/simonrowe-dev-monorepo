import { Auth0Provider } from '@auth0/auth0-react'
import type { ReactNode } from 'react'

const domain = 'dev-igsu3mpz.us.auth0.com'
const clientId = 'UiV5ijJH99uVE88BZzNcrKeefVgLqYi7'
const audience = 'https://api.simonrowe.dev'

interface AuthProviderProps {
  children: ReactNode
}

export function AuthProvider({ children }: AuthProviderProps) {
  return (
    <Auth0Provider
      domain={domain}
      clientId={clientId}
      authorizationParams={{
        redirect_uri: window.location.origin + '/admin',
        audience,
      }}
    >
      {children}
    </Auth0Provider>
  )
}
