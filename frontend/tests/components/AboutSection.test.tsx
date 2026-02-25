import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { AboutSection } from '../../src/components/profile/AboutSection'

const defaultProps = {
  description: '**Hello** [Docs](https://example.com)\n\n- Item',
  profileImageUrl: '/profile.jpg',
  profileName: 'Simon Rowe',
  location: 'London',
  primaryEmail: 'test@example.com',
  phoneNumber: '+440000',
}

describe('AboutSection', () => {
  it('renders markdown text and external links', () => {
    render(<AboutSection {...defaultProps} />)

    expect(screen.getByText('Hello')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: 'Docs' })
    expect(link).toHaveAttribute('href', 'https://example.com')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('renders profile image', () => {
    render(<AboutSection {...defaultProps} />)

    const img = screen.getByAltText('Simon Rowe')
    expect(img).toHaveAttribute('src', '/profile.jpg')
  })

  it('renders contact details with about text', () => {
    render(<AboutSection {...defaultProps} />)

    expect(screen.getByText('Hello')).toBeInTheDocument()
    expect(screen.getByText('London')).toBeInTheDocument()
    expect(screen.getByText('test@example.com')).toBeInTheDocument()
    expect(screen.getByText('+440000')).toBeInTheDocument()
  })

  it('renders social links when provided', () => {
    render(
      <AboutSection
        {...defaultProps}
        socialLinks={[
          { type: 'github', name: 'GitHub', url: 'https://github.com/test' },
        ]}
      />
    )

    const link = screen.getByRole('link', { name: /GitHub/i })
    expect(link).toHaveAttribute('href', 'https://github.com/test')
  })
})
