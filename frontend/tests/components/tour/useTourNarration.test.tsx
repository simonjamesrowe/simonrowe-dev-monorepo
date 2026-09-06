import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useTourNarration } from '../../../src/components/tour/useTourNarration'
import { fetchReadyNarrations } from '../../../src/services/narrationApi'
import type { TourStep } from '../../../src/types/tour'

vi.mock('../../../src/services/narrationApi', () => ({
  fetchReadyNarrations: vi.fn(),
}))

const mockFetch = vi.mocked(fetchReadyNarrations)

const steps: TourStep[] = [
  { id: 'step-1', title: 'One', description: 'First', targetSelector: '.a', position: 'bottom',
    order: 1, route: '/', autoAdvanceMs: null } as unknown as TourStep,
  { id: 'step-2', title: 'Two', description: 'Second', targetSelector: '.b', position: 'bottom',
    order: 2, route: '/', autoAdvanceMs: null } as unknown as TourStep,
]

/**
 * jsdom does not implement `play`, and stubbing it with `vi.spyOn` proved flaky in a full
 * suite run — the spy was intermittently already restored by the time an effect called it, so
 * the real unimplemented method ran and returned `undefined`. This installs a plain property
 * that vitest's mock lifecycle does not touch, with the behaviour swapped per test.
 */
let playBehaviour: () => Promise<void> = () => Promise.resolve()

Object.defineProperty(HTMLMediaElement.prototype, 'play', {
  configurable: true,
  writable: true,
  value: () => playBehaviour(),
})

function lastAudio(): HTMLAudioElement | null {
  return document.querySelector('audio[data-tour-narration]')
}

/**
 * This environment supplies a partial `localStorage` (no `clear`), so the test provides its
 * own in-memory implementation rather than depending on the host's.
 */
function stubLocalStorage(): Map<string, string> {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
    clear: () => store.clear(),
    key: () => null,
    length: 0,
  })
  return store
}

describe('useTourNarration', () => {
  let storage: Map<string, string>

  beforeEach(() => {
    storage = stubLocalStorage()
    mockFetch.mockResolvedValue([
      { contentId: 'step-1', audioUrl: '/uploads/narrations/a/n.mp3', durationSeconds: 6 },
      { contentId: 'step-2', audioUrl: '/uploads/narrations/b/n.mp3', durationSeconds: 7 },
    ])
    playBehaviour = () => Promise.resolve()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.clearAllMocks()
    // The hook appends its player to <body>; a leaked one would be found by `lastAudio()`
    // ahead of the next test's own element.
    document.querySelectorAll('audio').forEach((audio) => audio.remove())
  })

  it('reads what is playable once per tour run, not once per step', async () => {
    const { rerender } = renderHook(
      ({ index }) => useTourNarration(true, steps, index),
      { initialProps: { index: 0 } },
    )

    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(1))
    rerender({ index: 1 })
    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(mockFetch).toHaveBeenCalledWith('TOUR_STEP', expect.anything())
  })

  it('plays the audio belonging to the step the visitor is on', async () => {
    const { result, rerender } = renderHook(
      ({ index }) => useTourNarration(true, steps, index),
      { initialProps: { index: 0 } },
    )

    await waitFor(() => expect(result.current.available).toBe(true))
    await waitFor(() => expect(lastAudio()?.src).toContain('/uploads/narrations/a/n.mp3'))

    rerender({ index: 1 })
    await waitFor(() => expect(lastAudio()?.src).toContain('/uploads/narrations/b/n.mp3'))
  })

  it('stops speaking and remembers the choice when muted mid-tour', async () => {
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
    vi.spyOn(HTMLMediaElement.prototype, 'paused', 'get').mockReturnValue(false)
    const { result } = renderHook(() => useTourNarration(true, steps, 0))
    await waitFor(() => expect(result.current.available).toBe(true))

    act(() => result.current.toggleMuted())

    expect(result.current.muted).toBe(true)
    expect(storage.get('tour.narration.muted')).toBe('true')
    expect(pause).toHaveBeenCalled()
  })

  it('loads no audio at all when the visitor already chose silence', async () => {
    storage.set('tour.narration.muted', 'true')

    const { result } = renderHook(() => useTourNarration(true, steps, 0))
    await waitFor(() => expect(result.current.available).toBe(true))

    // Muted is not "play then silence": nothing is fetched over the wire to be played.
    expect(result.current.muted).toBe(true)
    expect(lastAudio()?.getAttribute('src')).toBeNull()
  })

  it('settles the step when its audio file cannot be loaded', async () => {
    const { result } = renderHook(() => useTourNarration(true, steps, 0))
    // Wait for the src, not just the element: until the bulk read resolves there is no audio
    // for this step, and a step with no audio is settled already — so asserting before that
    // would pass for the wrong reason.
    await waitFor(() => expect(lastAudio()?.getAttribute('src')).toBeTruthy())
    expect(result.current.settled).toBe(false)

    // A 404 or malformed file. Autoplay waits on `settled`, so leaving it false here would
    // stop the tour advancing off this step for the rest of the visit.
    act(() => { lastAudio()!.dispatchEvent(new Event('error')) })

    expect(result.current.settled).toBe(true)
    expect(result.current.speaking).toBe(false)
  })

  it('settles the step when the browser refuses to play', async () => {
    playBehaviour = () => Promise.reject(new DOMException('blocked', 'NotAllowedError'))

    const { result } = renderHook(() => useTourNarration(true, steps, 0))

    // Autoplay waits on `settled`; a browser blocking playback must not strand the tour.
    await waitFor(() => expect(result.current.settled).toBe(true))
  })

  it('runs the tour silently when audio cannot be listed', async () => {
    mockFetch.mockRejectedValue(new Error('offline'))

    const { result } = renderHook(() => useTourNarration(true, steps, 0))

    await waitFor(() => expect(mockFetch).toHaveBeenCalled())
    expect(result.current.available).toBe(false)
    expect(result.current.speaking).toBe(false)
  })

  it('reads nothing at all until the tour is running', () => {
    renderHook(() => useTourNarration(false, steps, 0))

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('removes its player from the document when the tour provider unmounts', async () => {
    const { unmount } = renderHook(() => useTourNarration(true, steps, 0))
    await waitFor(() => expect(lastAudio()).not.toBeNull())

    unmount()

    expect(lastAudio()).toBeNull()
  })
})
