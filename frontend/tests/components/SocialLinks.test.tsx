import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { SocialLinks } from '../../src/components/profile/SocialLinks'

describe('SocialLinks', () => {
  it('renders platform links with icon and external target', () => {
    render(
      <SocialLinks
        links={[
          { type: 'github', name: 'GitHub', url: 'https://github.com/simonrowe' },
          { type: 'linkedin', name: 'LinkedIn', url: 'https://linkedin.com/in/simon' },
        ]}
      />,
    )

    const githubLink = screen.getByRole('link', { name: /GitHub/i })
    expect(githubLink).toHaveAttribute('href', 'https://github.com/simonrowe')
    expect(githubLink).toHaveAttribute('target', '_blank')

    expect(screen.getByTestId('github-icon')).toBeInTheDocument()
    expect(screen.getByTestId('linkedin-icon')).toBeInTheDocument()
  })

  it('returns null for an empty list', () => {
    const { container } = render(<SocialLinks links={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})
