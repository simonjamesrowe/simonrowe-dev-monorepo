import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { SearchSimulation } from '../../../src/components/tour/SearchSimulation'

const mockSetSearchValue = vi.fn()

vi.mock('../../../src/hooks/useTour', () => ({
  useTour: () => ({
    setSearchValue: mockSetSearchValue,
    isActive: true,
    currentStepIndex: 0,
    steps: [],
    searchValue: '',
    start: vi.fn(),
    next: vi.fn(),
    prev: vi.fn(),
    exit: vi.fn(),
  }),
}))

describe('SearchSimulation', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mockSetSearchValue.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders nothing (returns null)', () => {
    const { container } = render(<SearchSimulation />)

    expect(container).toBeEmptyDOMElement()
  })

  it('types the first query character by character at 50ms intervals', async () => {
    render(<SearchSimulation />)

    // "spring boot" = 11 characters, each after 50ms
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50)
    })
    expect(mockSetSearchValue).toHaveBeenCalledWith('s')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(50)
    })
    expect(mockSetSearchValue).toHaveBeenCalledWith('sp')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(50 * 9)
    })
    expect(mockSetSearchValue).toHaveBeenCalledWith('spring boot')
  })

  it('pauses 1500ms between queries before typing the next one', async () => {
    render(<SearchSimulation />)

    // Complete first query: "spring boot" = 11 chars * 50ms
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50 * 11)
    })

    const callCountAfterFirstQuery = mockSetSearchValue.mock.calls.length

    // During the 1500ms pause, no new setSearchValue calls should be made
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(mockSetSearchValue.mock.calls.length).toBe(callCountAfterFirstQuery)

    // After the pause, typing resumes: first char of second query = "s"
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500 + 50)
    })
    expect(mockSetSearchValue).toHaveBeenCalledWith('s')
  })

  it('types all three queries in sequence', async () => {
    render(<SearchSimulation />)

    const query1 = 'spring boot'
    const query2 = 'spring boot kubernetes'
    const query3 = 'spring boot kubernetes jenkins'

    // query1: 11 chars * 50ms + 1500ms pause
    const query1Time = query1.length * 50 + 1500
    // query2: 22 chars * 50ms + 1500ms pause
    const query2Time = query2.length * 50 + 1500
    // query3: 30 chars * 50ms (no pause after last query)
    const query3Time = query3.length * 50

    await act(async () => {
      await vi.advanceTimersByTimeAsync(query1Time + query2Time + query3Time)
    })

    const allValues = mockSetSearchValue.mock.calls.map((call) => call[0] as string)

    expect(allValues).toContain('spring boot')
    expect(allValues).toContain('spring boot kubernetes')
    expect(allValues).toContain('spring boot kubernetes jenkins')
  })

  it('resets search value to empty string on cleanup', async () => {
    const { unmount } = render(<SearchSimulation />)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(50 * 3)
    })

    mockSetSearchValue.mockReset()

    act(() => {
      unmount()
    })

    expect(mockSetSearchValue).toHaveBeenCalledWith('')
  })

  it('aborts in-progress simulation when component unmounts mid-sequence', async () => {
    const { unmount } = render(<SearchSimulation />)

    // Advance partway through first query
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50 * 5)
    })

    const callCountBeforeUnmount = mockSetSearchValue.mock.calls.length
    mockSetSearchValue.mockReset()

    act(() => {
      unmount()
    })

    // Only the cleanup call (empty string) should happen after unmount
    expect(mockSetSearchValue).toHaveBeenCalledTimes(1)
    expect(mockSetSearchValue).toHaveBeenCalledWith('')

    // No further typing calls should happen after unmount
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50 * 100)
    })

    expect(mockSetSearchValue).toHaveBeenCalledTimes(1)

    // Suppress unused variable warning
    void callCountBeforeUnmount
  })
})
