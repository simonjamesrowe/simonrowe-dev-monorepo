import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { TourButton } from '../../../src/components/tour/TourButton'

const mockStart = vi.fn()

vi.mock('../../../src/hooks/useTour', () => ({
  useTour: () => ({
    start: mockStart,
    isActive: false,
    currentStepIndex: 0,
    steps: [],
    searchValue: '',
    next: vi.fn(),
    prev: vi.fn(),
    exit: vi.fn(),
    setSearchValue: vi.fn(),
  }),
}))

describe('TourButton', () => {
  beforeEach(() => {
    mockStart.mockReset()
  })

  it('renders a button with the tour-button class', () => {
    render(<TourButton />)

    const button = screen.getByRole('button', { name: 'Take a Tour' })
    expect(button).toBeInTheDocument()
    expect(button.className).toContain('tour-button')
  })

  it('calls start() when clicked', () => {
    mockStart.mockResolvedValue(undefined)

    render(<TourButton />)

    fireEvent.click(screen.getByRole('button', { name: 'Take a Tour' }))

    expect(mockStart).toHaveBeenCalledTimes(1)
  })
})
