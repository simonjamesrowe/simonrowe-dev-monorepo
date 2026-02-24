import { renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { TourProvider } from '../../src/components/tour/TourProvider'
import { useTour } from '../../src/hooks/useTour'

vi.mock('../../src/services/tourApi', () => ({
  fetchTourSteps: vi.fn(),
}))

// TourProvider uses window.matchMedia for responsive exit behaviour.
// jsdom does not implement matchMedia, so we provide a minimal stub.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(() => ({
    matches: true,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })),
})

describe('useTour', () => {
  it('returns context values when used inside TourProvider', () => {
    const { result } = renderHook(() => useTour(), {
      wrapper: TourProvider,
    })

    expect(result.current.isActive).toBe(false)
    expect(result.current.currentStepIndex).toBe(0)
    expect(result.current.steps).toEqual([])
    expect(result.current.searchValue).toBe('')
    expect(typeof result.current.start).toBe('function')
    expect(typeof result.current.next).toBe('function')
    expect(typeof result.current.prev).toBe('function')
    expect(typeof result.current.exit).toBe('function')
    expect(typeof result.current.setSearchValue).toBe('function')
  })

  it('throws an error when used outside TourProvider', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    expect(() => {
      renderHook(() => useTour())
    }).toThrow('useTour must be used within a TourProvider')

    consoleError.mockRestore()
  })
})
