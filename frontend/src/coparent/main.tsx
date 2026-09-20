import { Auth0Provider } from '@auth0/auth0-react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';

import { AUTH0_AUDIENCE, AUTH0_DOMAIN } from '../config/auth';
import App from './App';
import { ToastProvider } from './components/ui/ToastProvider';
import { TestAuthProvider } from './lib/auth/TestAuthProvider';
import '../styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60, // 1 minute
      retry: 1,
    },
  },
});

const auth0ClientId = import.meta.env.VITE_COPARENT_AUTH0_CLIENT_ID;
const auth0RedirectUri = `${window.location.origin}/auth/callback`;
const auth0Audience = import.meta.env.VITE_COPARENT_AUTH0_AUDIENCE || AUTH0_AUDIENCE;
const isE2ETestMode = import.meta.env.VITE_E2E_TEST_MODE === 'true';

function handleAuthRedirect(appState?: { returnTo?: string }) {
  const fallback = new URL('/auth/callback', window.location.origin);
  const returnTo = typeof appState?.returnTo === 'string' ? appState.returnTo : fallback.pathname;
  const requested = new URL(returnTo, window.location.origin);
  const target = requested.origin === window.location.origin ? requested : fallback;
  window.history.replaceState(null, '', `${target.pathname}${target.search}${target.hash}`);
}

if (!isE2ETestMode && !auth0ClientId) {
  throw new Error('Missing CoParent Auth0 client configuration.');
}

const authorizationParams: { redirect_uri: string; audience?: string } = {
  redirect_uri: auth0RedirectUri,
};

if (auth0Audience) {
  authorizationParams.audience = auth0Audience;
}

function AppProviders() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <BrowserRouter>
          <div className="coparent-app">
            <App />
          </div>
        </BrowserRouter>
      </ToastProvider>
    </QueryClientProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {isE2ETestMode ? (
      <TestAuthProvider>
        <AppProviders />
      </TestAuthProvider>
    ) : (
      <Auth0Provider
        domain={AUTH0_DOMAIN}
        clientId={auth0ClientId!}
        authorizationParams={authorizationParams}
        cacheLocation="localstorage"
        onRedirectCallback={handleAuthRedirect}
      >
        <AppProviders />
      </Auth0Provider>
    )}
  </React.StrictMode>,
);
