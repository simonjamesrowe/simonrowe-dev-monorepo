import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  CHAR_DELAY_MS,
  RESULTS_DWELL_MS,
  SearchSimulation,
} from '../../../src/components/tour/SearchSimulation'

const mockSetSearchValue = vi.fn()
const mockSetStepActivityPending = vi.fn()

vi.mock('../../../src/hooks/useTour', () => ({
  useTour: () => ({
    setSearchValue: mockSetSearchValue,
    setStepActivityPending: mockSetStepActivityPending,
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
    mockSetStepActivityPending.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders nothing (returns null)', () => {
    const { container } = render(<SearchSimulation />)

    expect(container).toBeEmptyDOMElement()
  })

  it('types the first query one character at a time', async () => {
    render(<SearchSimulation />)

    // "spring boot" = 11 characters, one per CHAR_DELAY_MS
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHAR_DELAY_MS)
    })
    expect(mockSetSearchValue).toHaveBeenCalledWith('s')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHAR_DELAY_MS)
    })
    expect(mockSetSearchValue).toHaveBeenCalledWith('sp')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHAR_DELAY_MS * 9)
    })
    expect(mockSetSearchValue).toHaveBeenCalledWith('spring boot')
  })

  it('dwells on the results before typing the next query', async () => {
    render(<SearchSimulation />)

    // Complete first query: "spring boot" = 11 chars
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHAR_DELAY_MS * 11)
    })

    const callCountAfterFirstQuery = mockSetSearchValue.mock.calls.length

    // The dwell exists so the real autocomplete results can arrive and be read; nothing
    // more is typed during it.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RESULTS_DWELL_MS - 600)
    })
    expect(mockSetSearchValue.mock.calls.length).toBe(callCountAfterFirstQuery)

    // After the pause, typing resumes: first char of second query = "s"
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600 + CHAR_DELAY_MS)
    })
    expect(mockSetSearchValue).toHaveBeenCalledWith('s')
  })

  it('types all three queries in sequence', async () => {
    render(<SearchSimulation />)

    const query1 = 'spring boot'
    const query2 = 'spring boot kubernetes'
    const query3 = 'spring boot kubernetes jenkins'

    // Every query now dwells on its results, the last one included.
    const query1Time = query1.length * CHAR_DELAY_MS + RESULTS_DWELL_MS
    const query2Time = query2.length * CHAR_DELAY_MS + RESULTS_DWELL_MS
    const query3Time = query3.length * CHAR_DELAY_MS + RESULTS_DWELL_MS

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
      await vi.advanceTimersByTimeAsync(CHAR_DELAY_MS * 3)
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
      await vi.advanceTimersByTimeAsync(CHAR_DELAY_MS * 5)
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
