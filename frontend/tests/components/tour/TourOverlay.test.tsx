import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { TourOverlay } from '../../../src/components/tour/TourOverlay'
import type { TourStep } from '../../../src/types/tour'

const mockExit = vi.fn()

const defaultStep: TourStep = {
  id: 'step-1',
  order: 1,
  targetSelector: '.about-section',
  title: 'About Section',
  titleImage: null,
  description: 'This is the about section.',
  position: 'bottom',
}

const mockUseTour = vi.fn()

vi.mock('../../../src/hooks/useTour', () => ({
  useTour: () => mockUseTour(),
}))

vi.mock('../../../src/components/tour/TourTooltip', () => ({
  TourTooltip: () => <div data-testid="tour-tooltip">Tooltip</div>,
}))

vi.mock('../../../src/components/tour/SearchSimulation', () => ({
  SearchSimulation: () => <div data-testid="search-simulation">Search Simulation</div>,
}))

describe('TourOverlay', () => {
  beforeEach(() => {
    mockExit.mockReset()
    mockUseTour.mockReturnValue({
      isActive: false,
      steps: [],
      currentStepIndex: 0,
      exit: mockExit,
      start: vi.fn(),
      next: vi.fn(),
      prev: vi.fn(),
      searchValue: '',
      setSearchValue: vi.fn(),
    })
  })

  it('does not render when the tour is inactive', () => {
    render(<TourOverlay />)

    expect(screen.queryByTestId('tour-overlay')).not.toBeInTheDocument()
  })

  it('renders the overlay when the tour is active', () => {
    mockUseTour.mockReturnValue({
      isActive: true,
      steps: [defaultStep],
      currentStepIndex: 0,
      exit: mockExit,
      start: vi.fn(),
      next: vi.fn(),
      prev: vi.fn(),
      searchValue: '',
      setSearchValue: vi.fn(),
    })

    render(<TourOverlay />)

    expect(screen.getByTestId('tour-overlay')).toBeInTheDocument()
  })

  it('calls exit() when the overlay background is clicked', () => {
    mockUseTour.mockReturnValue({
      isActive: true,
      steps: [defaultStep],
      currentStepIndex: 0,
      exit: mockExit,
      start: vi.fn(),
      next: vi.fn(),
      prev: vi.fn(),
      searchValue: '',
      setSearchValue: vi.fn(),
    })

    render(<TourOverlay />)

    fireEvent.click(screen.getByTestId('tour-overlay'))

    expect(mockExit).toHaveBeenCalledTimes(1)
  })

  it('renders TourTooltip when active', () => {
    mockUseTour.mockReturnValue({
      isActive: true,
      steps: [defaultStep],
      currentStepIndex: 0,
      exit: mockExit,
      start: vi.fn(),
      next: vi.fn(),
      prev: vi.fn(),
      searchValue: '',
      setSearchValue: vi.fn(),
    })

    render(<TourOverlay />)

    expect(screen.getByTestId('tour-tooltip')).toBeInTheDocument()
  })

  it('renders SearchSimulation when the current step targets .tour-search', () => {
    const searchStep: TourStep = { ...defaultStep, targetSelector: '.tour-search' }

    mockUseTour.mockReturnValue({
      isActive: true,
      steps: [searchStep],
      currentStepIndex: 0,
      exit: mockExit,
      start: vi.fn(),
      next: vi.fn(),
      prev: vi.fn(),
      searchValue: '',
      setSearchValue: vi.fn(),
    })

    render(<TourOverlay />)

    expect(screen.getByTestId('search-simulation')).toBeInTheDocument()
  })

  it('does not render SearchSimulation for non-search steps', () => {
    mockUseTour.mockReturnValue({
      isActive: true,
      steps: [defaultStep],
      currentStepIndex: 0,
      exit: mockExit,
      start: vi.fn(),
      next: vi.fn(),
      prev: vi.fn(),
      searchValue: '',
      setSearchValue: vi.fn(),
    })

    render(<TourOverlay />)

    expect(screen.queryByTestId('search-simulation')).not.toBeInTheDocument()
  })
})
