import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))
vi.mock('../../src/services/favouritesApi', () => ({
  getFavouriteIds: vi.fn(),
  addFavourite: vi.fn(),
  removeFavourite: vi.fn(),
}))

import { useAuth } from '../../src/auth/useAuth'
import {
  addFavourite,
  getFavouriteIds,
  removeFavourite,
} from '../../src/services/favouritesApi'
import { useFavourites } from '../../src/hooks/useFavourites'

const mockUseAuth = vi.mocked(useAuth)
const mockGetFavouriteIds = vi.mocked(getFavouriteIds)
const mockAddFavourite = vi.mocked(addFavourite)
const mockRemoveFavourite = vi.mocked(removeFavourite)

const getAccessToken = vi.fn().mockResolvedValue('token')
const loginWithPopup = vi.fn()

function setAuth(isAuthenticated: boolean) {
  mockUseAuth.mockReturnValue({
    isAuthenticated,
    getAccessToken,
    loginWithPopup,
  } as unknown as ReturnType<typeof useAuth>)
}

describe('useFavourites', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAccessToken.mockResolvedValue('token')
    mockGetFavouriteIds.mockResolvedValue([])
    mockAddFavourite.mockResolvedValue(undefined)
    mockRemoveFavourite.mockResolvedValue(undefined)
  })

  it('loads the id set when authenticated', async () => {
    setAuth(true)
    mockGetFavouriteIds.mockResolvedValue(['a-1', 'a-2'])

    const { result } = renderHook(() => useFavourites('news'))

    await waitFor(() => expect(result.current.isFavourite('a-1')).toBe(true))
    expect(result.current.isFavourite('a-2')).toBe(true)
    expect(result.current.isFavourite('a-3')).toBe(false)
    expect(mockGetFavouriteIds).toHaveBeenCalledWith(getAccessToken, 'news')
  })

  it('does not fetch and renders empty hearts when logged out', () => {
    setAuth(false)

    const { result } = renderHook(() => useFavourites('news'))

    expect(mockGetFavouriteIds).not.toHaveBeenCalled()
    expect(result.current.isFavourite('a-1')).toBe(false)
  })

  it('toggles optimistically and calls addFavourite for a new favourite', async () => {
    setAuth(true)
    let resolveAdd: () => void = () => {}
    mockAddFavourite.mockReturnValue(new Promise(resolve => { resolveAdd = resolve }))

    const { result } = renderHook(() => useFavourites('news'))

    let togglePromise: Promise<void>
    act(() => {
      togglePromise = result.current.toggleFavourite('a-1')
    })
    // Optimistic: marked favourite before the API call resolves.
    await waitFor(() => expect(result.current.isFavourite('a-1')).toBe(true))

    resolveAdd()
    await act(async () => togglePromise)
    expect(mockAddFavourite).toHaveBeenCalledWith(getAccessToken, 'news', 'a-1')
    expect(result.current.isFavourite('a-1')).toBe(true)
  })

  it('calls removeFavourite when toggling an existing favourite', async () => {
    setAuth(true)
    mockGetFavouriteIds.mockResolvedValue(['a-1'])

    const { result } = renderHook(() => useFavourites('news'))
    await waitFor(() => expect(result.current.isFavourite('a-1')).toBe(true))

    await act(async () => result.current.toggleFavourite('a-1'))

    expect(mockRemoveFavourite).toHaveBeenCalledWith(getAccessToken, 'news', 'a-1')
    expect(result.current.isFavourite('a-1')).toBe(false)
  })

  it('reverts the optimistic update when the API call fails', async () => {
    setAuth(true)
    mockAddFavourite.mockRejectedValue(new Error('boom'))

    const { result } = renderHook(() => useFavourites('news'))

    await act(async () => result.current.toggleFavourite('a-1'))

    expect(result.current.isFavourite('a-1')).toBe(false)
  })

  it('runs the login popup when toggling while logged out, then completes the save', async () => {
    setAuth(false)
    loginWithPopup.mockResolvedValue(undefined)
    // After the save completes the hook re-syncs ids; the server now includes the item.
    mockGetFavouriteIds.mockResolvedValue(['a-1'])

    const { result } = renderHook(() => useFavourites('news'))

    await act(async () => result.current.toggleFavourite('a-1'))

    expect(loginWithPopup).toHaveBeenCalledTimes(1)
    expect(mockAddFavourite).toHaveBeenCalledWith(getAccessToken, 'news', 'a-1')
    expect(result.current.isFavourite('a-1')).toBe(true)
  })

  it('does nothing when the login popup is dismissed', async () => {
    // auth0-react swallows the cancellation: loginWithPopup resolves but no session
    // exists, so the follow-up token fetch rejects.
    setAuth(false)
    loginWithPopup.mockResolvedValue(undefined)
    getAccessToken.mockRejectedValue(new Error('Login required'))

    const { result } = renderHook(() => useFavourites('news'))

    await act(async () => result.current.toggleFavourite('a-1'))

    expect(mockAddFavourite).not.toHaveBeenCalled()
    expect(result.current.isFavourite('a-1')).toBe(false)
  })

  it('does nothing when the login popup throws', async () => {
    setAuth(false)
    loginWithPopup.mockRejectedValue(new Error('Popup closed'))

    const { result } = renderHook(() => useFavourites('news'))

    await act(async () => result.current.toggleFavourite('a-1'))

    expect(mockAddFavourite).not.toHaveBeenCalled()
    expect(result.current.isFavourite('a-1')).toBe(false)
  })

  it('ensureAuthenticated resolves true without a popup when already logged in', async () => {
    setAuth(true)

    const { result } = renderHook(() => useFavourites('news'))

    await expect(result.current.ensureAuthenticated()).resolves.toBe(true)
    expect(loginWithPopup).not.toHaveBeenCalled()
  })

  it('ensureAuthenticated resolves false when the popup is cancelled', async () => {
    setAuth(false)
    loginWithPopup.mockResolvedValue(undefined)
    getAccessToken.mockRejectedValue(new Error('Login required'))

    const { result } = renderHook(() => useFavourites('news'))

    await expect(result.current.ensureAuthenticated()).resolves.toBe(false)
  })
})
