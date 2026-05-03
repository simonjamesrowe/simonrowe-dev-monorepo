import { renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

vi.mock('@auth0/auth0-react', () => ({
  useAuth0: vi.fn(),
}))

import { useAuth0 } from '@auth0/auth0-react'
import { ADMIN_ROLE, ROLES_CLAIM, useAdminRole } from '../../src/auth/useAdminRole'

const mockUseAuth0 = vi.mocked(useAuth0)

function setUser(user: Record<string, unknown> | undefined) {
  mockUseAuth0.mockReturnValue({
    user,
  } as unknown as ReturnType<typeof useAuth0>)
}

describe('useAdminRole', () => {
  it('returns true when roles claim contains DEV_PORTAL_ADMIN', () => {
    setUser({ [ROLES_CLAIM]: [ADMIN_ROLE, 'EDITOR'] })
    const { result } = renderHook(() => useAdminRole())
    expect(result.current).toBe(true)
  })

  it('returns false when roles claim is missing', () => {
    setUser({})
    const { result } = renderHook(() => useAdminRole())
    expect(result.current).toBe(false)
  })

  it('returns false when roles claim does not include DEV_PORTAL_ADMIN', () => {
    setUser({ [ROLES_CLAIM]: ['VIEWER'] })
    const { result } = renderHook(() => useAdminRole())
    expect(result.current).toBe(false)
  })

  it('returns false when user is undefined', () => {
    setUser(undefined)
    const { result } = renderHook(() => useAdminRole())
    expect(result.current).toBe(false)
  })

  it('returns false when roles claim is not an array', () => {
    setUser({ [ROLES_CLAIM]: 'DEV_PORTAL_ADMIN' })
    const { result } = renderHook(() => useAdminRole())
    expect(result.current).toBe(false)
  })
})
