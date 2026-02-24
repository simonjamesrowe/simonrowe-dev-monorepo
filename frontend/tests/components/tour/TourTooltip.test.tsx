import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { TourTooltip } from '../../../src/components/tour/TourTooltip'
import type { TourStep } from '../../../src/types/tour'

const mockNext = vi.fn()
const mockPrev = vi.fn()
const mockExit = vi.fn()

const mockUseTour = vi.fn()

vi.mock('../../../src/hooks/useTour', () => ({
  useTour: () => mockUseTour(),
}))

const stepOne: TourStep = {
  id: 'step-1',
  order: 1,
  targetSelector: '.about-section',
  title: 'About Section',
  titleImage: null,
  description: 'Welcome to the tour.',
  position: 'bottom',
}

const stepTwo: TourStep = {
  id: 'step-2',
  order: 2,
  targetSelector: '.skills-section',
  title: 'Skills Section',
  titleImage: '/images/skills-icon.png',
  description: 'Explore skills here.',
  position: 'right',
}

const stepThree: TourStep = {
  id: 'step-3',
  order: 3,
  targetSelector: '.contact-section',
  title: 'Contact Section',
  titleImage: null,
  description: 'Get in touch.',
  position: 'top',
}

describe('TourTooltip', () => {
  beforeEach(() => {
    mockNext.mockReset()
    mockPrev.mockReset()
    mockExit.mockReset()
  })

  it('renders tooltip with title and description', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    expect(screen.getByTestId('tour-tooltip')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3 })).toHaveTextContent('About Section')
  })

  it('renders markdown description content', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    expect(screen.getByText('Welcome to the tour.')).toBeInTheDocument()
  })

  it('renders progress indicator as "Step X of Y"', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo, stepThree],
      currentStepIndex: 1,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    expect(screen.getByText('Step 2 of 3')).toBeInTheDocument()
  })

  it('hides the Previous button on the first step', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    expect(screen.queryByRole('button', { name: 'Previous' })).not.toBeInTheDocument()
  })

  it('shows the Previous button on steps after the first', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo, stepThree],
      currentStepIndex: 1,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    expect(screen.getByRole('button', { name: 'Previous' })).toBeInTheDocument()
  })

  it('shows "Next" button on non-last steps', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    expect(screen.getByRole('button', { name: 'Next' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Finish' })).not.toBeInTheDocument()
  })

  it('shows "Finish" button instead of "Next" on the last step', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 1,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    expect(screen.getByRole('button', { name: 'Finish' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()
  })

  it('calls next() when the Next button is clicked', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))

    expect(mockNext).toHaveBeenCalledTimes(1)
  })

  it('calls exit() when the Finish button is clicked on the last step', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 1,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    fireEvent.click(screen.getByRole('button', { name: 'Finish' }))

    expect(mockExit).toHaveBeenCalledTimes(1)
  })

  it('calls prev() when the Previous button is clicked', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 1,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))

    expect(mockPrev).toHaveBeenCalledTimes(1)
  })

  it('calls exit() when the Exit button is clicked', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne, stepTwo],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    render(<TourTooltip />)

    fireEvent.click(screen.getByRole('button', { name: 'Exit' }))

    expect(mockExit).toHaveBeenCalledTimes(1)
  })

  it('renders titleImage when provided', () => {
    mockUseTour.mockReturnValue({
      steps: [stepTwo],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    const { container } = render(<TourTooltip />)

    // The image has alt="" (decorative), so it has role="presentation", not "img".
    // Query it via the DOM directly.
    const img = container.querySelector('img.tour-tooltip__image')
    expect(img).not.toBeNull()
    expect(img).toHaveAttribute('src', '/images/skills-icon.png')
  })

  it('does not render an image when titleImage is null', () => {
    mockUseTour.mockReturnValue({
      steps: [stepOne],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: true,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    const { container } = render(<TourTooltip />)

    expect(container.querySelector('img')).toBeNull()
  })

  it('renders nothing when there is no current step', () => {
    mockUseTour.mockReturnValue({
      steps: [],
      currentStepIndex: 0,
      next: mockNext,
      prev: mockPrev,
      exit: mockExit,
      isActive: false,
      searchValue: '',
      start: vi.fn(),
      setSearchValue: vi.fn(),
    })

    const { container } = render(<TourTooltip />)

    expect(container).toBeEmptyDOMElement()
  })
})
