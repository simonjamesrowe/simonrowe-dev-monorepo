import { Auth0Context, type Auth0ContextInterface, type User } from '@auth0/auth0-react';
import { useMemo, type ReactNode } from 'react';

const TEST_JWT_SECRET = 'e2e-test-secret';
const TEST_AUTH0_ID = 'auth0|e2e-test-user';
const TEST_EMAIL = 'e2e-test@coparent.dev';

function base64UrlEncode(input: Uint8Array): string {
  let binary = '';
  input.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });

  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function base64UrlEncodeJson(data: Record<string, unknown>): string {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(data)));
}

async function signHs256(data: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );

  const signature = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(data));
  return base64UrlEncode(new Uint8Array(signature));
}

async function createTestJwt(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    sub: TEST_AUTH0_ID,
    email: TEST_EMAIL,
    permissions: [],
    iat: now,
    exp: now + 60 * 60,
  };

  const header = { alg: 'HS256', typ: 'JWT' };
  const encodedHeader = base64UrlEncodeJson(header);
  const encodedPayload = base64UrlEncodeJson(payload);
  const unsignedToken = `${encodedHeader}.${encodedPayload}`;
  const signature = await signHs256(unsignedToken, TEST_JWT_SECRET);

  return `${unsignedToken}.${signature}`;
}

interface TestAuthProviderProps {
  children: ReactNode;
}

export function TestAuthProvider({ children }: TestAuthProviderProps) {
  const contextValue = useMemo(
    () =>
      ({
        isAuthenticated: true,
        isLoading: false,
        user: {
          sub: TEST_AUTH0_ID,
          name: 'E2E Test Parent',
          email: TEST_EMAIL,
          picture: '',
        },
        error: undefined,
        getAccessTokenSilently: async () => createTestJwt(),
        getAccessTokenWithPopup: async () => createTestJwt(),
        getIdTokenClaims: async () => ({ __raw: await createTestJwt() }),
        loginWithRedirect: async (options?: { appState?: { returnTo?: string } }) => {
          const returnTo = options?.appState?.returnTo ?? '/';
          window.location.assign(returnTo);
        },
        loginWithPopup: async () => undefined,
        logout: () => {
          window.location.assign('/login');
        },
        handleRedirectCallback: async () => undefined,
      }) as unknown as Auth0ContextInterface<User>,
    [],
  );

  return <Auth0Context.Provider value={contextValue}>{children}</Auth0Context.Provider>;
}
