import { Auth0Provider } from '@auth0/auth0-react'
import type { ReactNode } from 'react'

import { AnalyticsIdentity } from '../analytics/AnalyticsIdentity'
import { AUTH0_AUDIENCE, AUTH0_CLIENT_ID, AUTH0_DOMAIN } from '../config/auth'

interface AuthProviderProps {
  children: ReactNode
}

/**
 * The main site's session.
 *
 * It remembers the device, exactly as Meals does: tokens are kept in local storage and renewed
 * with a rotating refresh token (`offline_access`). With the SDK's default in-memory cache, every
 * full page load (a new tab, a phone reopening a backgrounded page, the home-screen app) had to
 * ask Auth0 again through a hidden iframe, which Safari's tracking prevention blocks, so a
 * signed-in visitor appeared signed out on the next page they opened and had to sign in again
 * to change the playlist.
 *
 * Both Auth0 settings this needs are already on for Meals, which uses this same application and
 * API: Refresh Token Rotation on the SPA application and Allow Offline Access on
 * `https://api.simonrowe.dev`. If either is turned off, Auth0 issues no refresh token and
 * `useRefreshTokensFallback` falls back to the iframe, which is the old behaviour.
 *
 * The tradeoff, accepted as it was for Meals: a token in local storage is readable by script on
 * this origin, where the in-memory cache was not. Rotation bounds the damage (a stolen refresh
 * token is invalidated the first time either copy is used twice), and the only raw HTML the
 * site renders is the owner's own blog posts; model-written text goes through react-markdown
 * with no raw HTML.
 */
export function AuthProvider({ children }: AuthProviderProps) {
  return (
    <Auth0Provider
      domain={AUTH0_DOMAIN}
      clientId={AUTH0_CLIENT_ID}
      authorizationParams={{
        redirect_uri: window.location.origin + '/admin',
        audience: AUTH0_AUDIENCE,
        scope: 'openid profile email offline_access',
      }}
      cacheLocation="localstorage"
      useRefreshTokens
      useRefreshTokensFallback
    >
      <AnalyticsIdentity />
      {children}
    </Auth0Provider>
  )
}
