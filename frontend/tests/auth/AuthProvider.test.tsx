import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

const providerProps = vi.hoisted(() => ({ current: null as Record<string, unknown> | null }))

vi.mock('@auth0/auth0-react', () => ({
  Auth0Provider: ({ children, ...props }: { children: ReactNode } & Record<string, unknown>) => {
    providerProps.current = props
    return children
  },
}))
vi.mock('../../src/analytics/AnalyticsIdentity', () => ({ AnalyticsIdentity: () => null }))

import { AuthProvider } from '../../src/auth/AuthProvider'

/**
 * The session must survive a full page load. With the SDK's default in-memory cache it did
 * not: every new tab or reopened phone page asked Auth0 again through an iframe Safari blocks,
 * and the visitor appeared signed out.
 */
describe('AuthProvider', () => {
  it('remembers the device with rotating refresh tokens kept in local storage', () => {
    render(<AuthProvider><span>child</span></AuthProvider>)

    expect(providerProps.current).toMatchObject({
      cacheLocation: 'localstorage',
      useRefreshTokens: true,
      useRefreshTokensFallback: true,
      authorizationParams: expect.objectContaining({
        scope: expect.stringContaining('offline_access'),
        audience: 'https://api.simonrowe.dev',
      }),
    })
  })
})
