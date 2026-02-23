import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { Sidebar } from '../../src/components/layout/Sidebar'

const navigationItems = [
  { id: 'about', label: 'About' },
  { id: 'experience', label: 'Experience' },
  { id: 'skills', label: 'Skills' },
  { id: 'blog', label: 'Blog' },
  { id: 'contact', label: 'Contact' },
]

describe('Sidebar', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders navigation items and triggers smooth scroll', () => {
    const scrollIntoView = vi.fn()
    const getElementByIdSpy = vi.spyOn(document, 'getElementById').mockImplementation(() => {
      return { scrollIntoView } as unknown as HTMLElement
    })

    render(<Sidebar aboutImageUrl="/avatar.jpg" items={navigationItems} />)

    expect(screen.getByRole('button', { name: /About/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Experience/i })).toBeInTheDocument()
    expect(screen.getByTestId('experience-icon')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Experience/i }))

    expect(getElementByIdSpy).toHaveBeenCalledWith('experience')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth' })
  })
})
