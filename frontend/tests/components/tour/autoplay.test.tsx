import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { TourProvider } from '../../../src/components/tour/TourProvider'
import { useTour } from '../../../src/hooks/useTour'
import type { TourStep } from '../../../src/types/tour'

vi.mock('../../../src/services/tourApi', () => ({ fetchTourSteps: vi.fn() }))
vi.mock('../../../src/services/narrationApi', () => ({ fetchReadyNarrations: vi.fn() }))

import { fetchTourSteps } from '../../../src/services/tourApi'
import { fetchReadyNarrations } from '../../../src/services/narrationApi'

// jsdom implements neither of these; the tour uses both to anchor a step.
Element.prototype.scrollIntoView = vi.fn()

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(() => ({
    matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn(),
  })),
})

function step(id: string, order: number, overrides: Partial<TourStep> = {}): TourStep {
  return {
    id, order, targetSelector: `.target-${order}`, title: `Step ${order}`, titleImage: null,
    description: 'Two words.', position: 'bottom', route: '/', autoAdvanceMs: null, ...overrides,
  }
}

/** Exposes the pieces autoplay depends on, and a way to report a step's own demonstration. */
function Harness() {
  const { currentStepIndex, isActive, start, setStepActivityPending, toggleAutoplay } = useTour()
  return (
    <div>
      <div data-testid="index">{currentStepIndex}</div>
      <div data-testid="active">{String(isActive)}</div>
      <button data-testid="start" onClick={() => void start()} type="button">start</button>
      <button data-testid="busy" onClick={() => setStepActivityPending(true)} type="button">busy</button>
      <button data-testid="idle" onClick={() => setStepActivityPending(false)} type="button">idle</button>
      <button data-testid="toggle" onClick={toggleAutoplay} type="button">toggle</button>
    </div>
  )
}

async function startTour() {
  render(
    <MemoryRouter>
      <TourProvider><Harness /></TourProvider>
    </MemoryRouter>,
  )
  await act(async () => { screen.getByTestId('start').click() })
  await waitFor(() => expect(screen.getByTestId('active')).toHaveTextContent('true'))
  // Let the step anchor to its target before the clock moves: the advance timer is only
  // scheduled once it has, and scheduling it inside the same tick we advance would leave it
  // registered beyond the window we just advanced through.
  await act(async () => { await vi.advanceTimersByTimeAsync(50) })
}

describe('tour autoplay', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.mocked(fetchTourSteps).mockResolvedValue([step('a', 1), step('b', 2), step('c', 3)])
    // No narration: every step settles immediately and the reading floor governs.
    vi.mocked(fetchReadyNarrations).mockResolvedValue([])
    // Every step's target exists, so `targetReady` resolves at once.
    document.body.innerHTML = '<div class="target-1"></div><div class="target-2"></div>'
      + '<div class="target-3"></div>'
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    window.localStorage.clear?.()
  })

  it('advances on its own once a step has nothing left to present', async () => {
    await startTour()
    expect(screen.getByTestId('index')).toHaveTextContent('0')

    await act(async () => { await vi.advanceTimersByTimeAsync(6000) })

    expect(screen.getByTestId('index')).toHaveTextContent('1')
  })

  it('waits for a step running its own demonstration', async () => {
    await startTour()
    await act(async () => { screen.getByTestId('busy').click() })

    await act(async () => { await vi.advanceTimersByTimeAsync(20000) })
    // Held for far longer than the reading floor, because the step said it was still going.
    expect(screen.getByTestId('index')).toHaveTextContent('0')

    await act(async () => { screen.getByTestId('idle').click() })
    await act(async () => { await vi.advanceTimersByTimeAsync(6000) })
    expect(screen.getByTestId('index')).toHaveTextContent('1')
  })

  it('stops advancing when the visitor pauses it', async () => {
    await startTour()
    await act(async () => { screen.getByTestId('toggle').click() })

    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })

    expect(screen.getByTestId('index')).toHaveTextContent('0')
  })

  it('never closes the tour by running off the end', async () => {
    await startTour()
    for (let i = 0; i < 4; i++) {
      await act(async () => { await vi.advanceTimersByTimeAsync(8000) })
    }

    // Parked on the last step, still active, waiting for Finish.
    expect(screen.getByTestId('index')).toHaveTextContent('2')
    expect(screen.getByTestId('active')).toHaveTextContent('true')
  })

  it('holds on a step whose configured pause is zero', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([
      step('a', 1, { autoAdvanceMs: 0 }), step('b', 2),
    ])
    await startTour()

    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })

    expect(screen.getByTestId('index')).toHaveTextContent('0')
  })
})
