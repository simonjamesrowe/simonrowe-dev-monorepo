import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { CTASection } from '../../../src/components/home/CTASection'

function renderCta() {
  return render(
    <MemoryRouter>
      <CTASection />
    </MemoryRouter>,
  )
}

describe('CTASection', () => {
  it('keeps the tour-contact anchor on its root', () => {
    const { container } = renderCta()

    const root = container.querySelector('.cta-section')
    expect(root).not.toBeNull()
    expect(root).toHaveClass('tour-contact')
  })

  it('links "Get in touch" to the profile contact section', () => {
    renderCta()

    const contact = screen.getByRole('link', { name: 'Get in touch' })
    expect(contact).toHaveAttribute('href', '/profile#contact')
  })

  it('links "Download CV" to the resume endpoint', () => {
    renderCta()

    const cv = screen.getByRole('link', { name: /Download CV/i })
    expect(cv.getAttribute('href')).toMatch(/\/api\/resume$/)
    expect(cv).toHaveAttribute('target', '_blank')
  })

  it('no longer offers the old Explore Work link', () => {
    renderCta()

    expect(screen.queryByRole('link', { name: /Explore Work/i })).not.toBeInTheDocument()
    expect(screen.getAllByRole('link')).toHaveLength(2)
  })
})
