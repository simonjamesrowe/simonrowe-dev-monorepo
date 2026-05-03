import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useMediaQuery } from '../../src/hooks/useMediaQuery'

type Listener = (event: MediaQueryListEvent) => void

function mockMatchMedia(matches: boolean) {
  const listeners = new Set<Listener>()
  const mql = {
    matches,
    media: '',
    onchange: null,
    addEventListener: (_: string, fn: Listener) => listeners.add(fn),
    removeEventListener: (_: string, fn: Listener) => listeners.delete(fn),
    addListener: (fn: Listener) => listeners.add(fn),
    removeListener: (fn: Listener) => listeners.delete(fn),
    dispatchEvent: () => true,
  } as unknown as MediaQueryList
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => {
      ;(mql as unknown as { media: string }).media = query
      return mql
    }),
  )
  return {
    mql,
    fire(next: boolean) {
      ;(mql as unknown as { matches: boolean }).matches = next
      listeners.forEach(fn => fn({ matches: next } as MediaQueryListEvent))
    },
  }
}

describe('useMediaQuery', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns initial match value', () => {
    mockMatchMedia(true)
    const { result } = renderHook(() => useMediaQuery('(max-width: 768px)'))
    expect(result.current).toBe(true)
  })

  it('returns false when media does not match', () => {
    mockMatchMedia(false)
    const { result } = renderHook(() => useMediaQuery('(max-width: 768px)'))
    expect(result.current).toBe(false)
  })

  it('updates when the media query changes', () => {
    const { fire } = mockMatchMedia(false)
    const { result } = renderHook(() => useMediaQuery('(max-width: 768px)'))
    expect(result.current).toBe(false)
    act(() => fire(true))
    expect(result.current).toBe(true)
  })

  it('removes its listener on unmount', () => {
    const { mql } = mockMatchMedia(true)
    const removeSpy = vi.spyOn(mql, 'removeEventListener')
    const { unmount } = renderHook(() => useMediaQuery('(max-width: 768px)'))
    unmount()
    expect(removeSpy).toHaveBeenCalled()
  })
})
