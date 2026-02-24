import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { TourProvider } from '../../../src/components/tour/TourProvider'
import { useTour } from '../../../src/hooks/useTour'
import type { TourStep } from '../../../src/types/tour'

vi.mock('../../../src/services/tourApi', () => ({
  fetchTourSteps: vi.fn(),
}))

import { fetchTourSteps } from '../../../src/services/tourApi'

// TourProvider uses window.matchMedia for responsive exit behaviour.
// jsdom does not implement matchMedia, so we provide a minimal stub.
const matchMediaStub = () => ({
  matches: true,
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
})

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(matchMediaStub),
})

const stepOne: TourStep = {
  id: 'step-1',
  order: 1,
  targetSelector: '.about-section',
  title: 'About',
  titleImage: null,
  description: 'About section description.',
  position: 'bottom',
}

const stepTwo: TourStep = {
  id: 'step-2',
  order: 2,
  targetSelector: '.skills-section',
  title: 'Skills',
  titleImage: null,
  description: 'Skills section description.',
  position: 'right',
}

function TourStateDisplay() {
  const { isActive, currentStepIndex, steps, searchValue, start, next, prev, exit, setSearchValue } = useTour()

  return (
    <div>
      <div data-testid="is-active">{String(isActive)}</div>
      <div data-testid="step-index">{currentStepIndex}</div>
      <div data-testid="step-count">{steps.length}</div>
      <div data-testid="search-value">{searchValue}</div>
      <button data-testid="btn-start" onClick={() => void start()} type="button">
        Start
      </button>
      <button data-testid="btn-next" onClick={next} type="button">
        Next
      </button>
      <button data-testid="btn-prev" onClick={prev} type="button">
        Prev
      </button>
      <button data-testid="btn-exit" onClick={exit} type="button">
        Exit
      </button>
      <button
        data-testid="btn-set-search"
        onClick={() => setSearchValue('hello')}
        type="button"
      >
        Set Search
      </button>
    </div>
  )
}

describe('TourProvider', () => {
  beforeEach(() => {
    vi.mocked(fetchTourSteps).mockReset()
  })

  it('starts in an inactive state with no steps', () => {
    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    expect(screen.getByTestId('is-active')).toHaveTextContent('false')
    expect(screen.getByTestId('step-count')).toHaveTextContent('0')
    expect(screen.getByTestId('step-index')).toHaveTextContent('0')
    expect(screen.getByTestId('search-value')).toHaveTextContent('')
  })

  it('start() fetches steps and activates the tour', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([stepOne, stepTwo])

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    act(() => {
      screen.getByTestId('btn-start').click()
    })

    await waitFor(() => {
      expect(screen.getByTestId('is-active')).toHaveTextContent('true')
    })

    expect(screen.getByTestId('step-count')).toHaveTextContent('2')
    expect(screen.getByTestId('step-index')).toHaveTextContent('0')
  })

  it('next() advances to the next step', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([stepOne, stepTwo])

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    act(() => {
      screen.getByTestId('btn-start').click()
    })

    await waitFor(() => {
      expect(screen.getByTestId('is-active')).toHaveTextContent('true')
    })

    act(() => {
      screen.getByTestId('btn-next').click()
    })

    expect(screen.getByTestId('step-index')).toHaveTextContent('1')
  })

  it('next() on the last step resets the tour', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([stepOne, stepTwo])

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    act(() => {
      screen.getByTestId('btn-start').click()
    })

    await waitFor(() => {
      expect(screen.getByTestId('is-active')).toHaveTextContent('true')
    })

    act(() => {
      screen.getByTestId('btn-next').click()
    })

    act(() => {
      screen.getByTestId('btn-next').click()
    })

    expect(screen.getByTestId('is-active')).toHaveTextContent('false')
    expect(screen.getByTestId('step-count')).toHaveTextContent('0')
  })

  it('prev() moves to the previous step', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([stepOne, stepTwo])

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    act(() => {
      screen.getByTestId('btn-start').click()
    })

    await waitFor(() => {
      expect(screen.getByTestId('is-active')).toHaveTextContent('true')
    })

    act(() => {
      screen.getByTestId('btn-next').click()
    })

    expect(screen.getByTestId('step-index')).toHaveTextContent('1')

    act(() => {
      screen.getByTestId('btn-prev').click()
    })

    expect(screen.getByTestId('step-index')).toHaveTextContent('0')
  })

  it('prev() on the first step does not change step index', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([stepOne, stepTwo])

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    act(() => {
      screen.getByTestId('btn-start').click()
    })

    await waitFor(() => {
      expect(screen.getByTestId('is-active')).toHaveTextContent('true')
    })

    act(() => {
      screen.getByTestId('btn-prev').click()
    })

    expect(screen.getByTestId('step-index')).toHaveTextContent('0')
  })

  it('exit() resets the tour to initial state', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([stepOne, stepTwo])

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    act(() => {
      screen.getByTestId('btn-start').click()
    })

    await waitFor(() => {
      expect(screen.getByTestId('is-active')).toHaveTextContent('true')
    })

    act(() => {
      screen.getByTestId('btn-exit').click()
    })

    expect(screen.getByTestId('is-active')).toHaveTextContent('false')
    expect(screen.getByTestId('step-count')).toHaveTextContent('0')
    expect(screen.getByTestId('step-index')).toHaveTextContent('0')
  })

  it('setSearchValue() updates the search value in context', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([stepOne])

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    act(() => {
      screen.getByTestId('btn-start').click()
    })

    await waitFor(() => {
      expect(screen.getByTestId('is-active')).toHaveTextContent('true')
    })

    act(() => {
      screen.getByTestId('btn-set-search').click()
    })

    expect(screen.getByTestId('search-value')).toHaveTextContent('hello')
  })

  it('start() silently handles API errors and leaves tour inactive', async () => {
    vi.mocked(fetchTourSteps).mockRejectedValue(new Error('Network error'))

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    await act(async () => {
      screen.getByTestId('btn-start').click()
    })

    expect(screen.getByTestId('is-active')).toHaveTextContent('false')
    expect(screen.getByTestId('step-count')).toHaveTextContent('0')
  })

  it('start() does not activate when API returns empty steps', async () => {
    vi.mocked(fetchTourSteps).mockResolvedValue([])

    render(
      <TourProvider>
        <TourStateDisplay />
      </TourProvider>,
    )

    await act(async () => {
      screen.getByTestId('btn-start').click()
    })

    expect(screen.getByTestId('is-active')).toHaveTextContent('false')
  })
})
