import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { AboutSection } from '../../src/components/profile/AboutSection'

describe('AboutSection', () => {
  it('renders markdown text and external links', () => {
    render(<AboutSection description={'**Hello** [Docs](https://example.com)\n\n- Item'} />)

    expect(screen.getByText('Hello')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: 'Docs' })
    expect(link).toHaveAttribute('href', 'https://example.com')
    expect(link).toHaveAttribute('target', '_blank')
  })
})
