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
 * this origin. The token is kept in local storage, as CoParent does, so the installed app does
 * not ask for a sign-in on every launch.
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
      authorizationParams={{ redirect_uri: `${window.location.origin}/`, audience: AUTH0_AUDIENCE }}
      cacheLocation="localstorage"
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
