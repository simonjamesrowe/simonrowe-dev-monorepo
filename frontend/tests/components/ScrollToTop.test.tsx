import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ScrollToTop } from '../../src/components/layout/ScrollToTop'

describe('ScrollToTop', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    Object.defineProperty(window, 'scrollY', {
      configurable: true,
      writable: true,
      value: 0,
    })
  })

  it('shows button after scroll and scrolls to top when clicked', () => {
    const scrollToSpy = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)

    render(<ScrollToTop />)
    expect(screen.queryByRole('button', { name: 'Scroll to top' })).not.toBeInTheDocument()

    Object.defineProperty(window, 'scrollY', {
      configurable: true,
      writable: true,
      value: 700,
    })
    fireEvent.scroll(window)

    const button = screen.getByRole('button', { name: 'Scroll to top' })
    fireEvent.click(button)

    expect(scrollToSpy).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' })
  })
})
