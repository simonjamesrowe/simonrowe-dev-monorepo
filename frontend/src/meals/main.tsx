import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Auth0Provider, type AppState } from '@auth0/auth0-react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'

import { AUTH0_AUDIENCE, AUTH0_CLIENT_ID, AUTH0_DOMAIN } from '../config/auth'
import { App } from './App'
import '../styles.css'

/**
 * Meals' entry point (spec 053), served at meals.simonrowe.dev.
 *
 * It signs in with the MAIN site's Auth0 application, deliberately: that application's access
 * token already carries the owner's DEV_PORTAL_ADMIN role, which is all /api/meals checks, so
 * no second application, secret or Action change is needed. Its Allowed Callback URLs must list
 * this origin.
 *
 * It remembers the device: tokens are kept in local storage and renewed with a rotating refresh
 * token (`offline_access`), so the installed app stays signed in across launches without the
 * third-party-cookie iframe Safari blocks. That needs Refresh Token Rotation on the Auth0
 * application and Allow Offline Access on the API; until both are on, Auth0 issues no refresh
 * token and the iframe fallback below keeps today's behaviour.
 */
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } })

function returnTo(appState?: AppState) {
  const target = appState?.returnTo
  const safe = typeof target === 'string' && target.startsWith('/') && !target.startsWith('//') ? target : '/'
  window.history.replaceState({}, '', safe)
  // BrowserRouter already mounted on the callback URL; popstate is how it hears of the change.
  window.dispatchEvent(new PopStateEvent('popstate'))
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Auth0Provider
      domain={AUTH0_DOMAIN}
      clientId={AUTH0_CLIENT_ID}
      authorizationParams={{
        redirect_uri: `${window.location.origin}/`,
        audience: AUTH0_AUDIENCE,
        scope: 'openid profile email offline_access',
      }}
      cacheLocation="localstorage"
      useRefreshTokens
      useRefreshTokensFallback
      onRedirectCallback={returnTo}
    >
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <div className="meals-app">
            <App />
          </div>
        </BrowserRouter>
      </QueryClientProvider>
    </Auth0Provider>
  </StrictMode>,
)
