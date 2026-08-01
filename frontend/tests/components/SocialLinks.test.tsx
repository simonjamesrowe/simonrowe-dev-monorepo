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

    const linkedinLink = screen.getByRole('link', { name: /LinkedIn/i })
    expect(linkedinLink).toHaveAttribute('href', 'https://linkedin.com/in/simon')
  })

  it('returns null for an empty list', () => {
    const { container } = render(<SocialLinks links={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('prefers the link name so the two GitHub accounts are distinguishable', () => {
    render(
      <SocialLinks
        links={[
          {
            type: 'github',
            name: 'GitHub — personal',
            url: 'https://github.com/simonrowe',
          },
          {
            type: 'github',
            name: 'GitHub — this site',
            url: 'https://github.com/simonjamesrowe',
          },
        ]}
      />,
    )

    expect(screen.getByRole('link', { name: 'GitHub — personal profile' })).toHaveAttribute(
      'href',
      'https://github.com/simonrowe',
    )
    expect(screen.getByRole('link', { name: 'GitHub — this site profile' })).toHaveAttribute(
      'href',
      'https://github.com/simonjamesrowe',
    )
  })

  it('falls back to the platform label when a link has no name', () => {
    render(<SocialLinks links={[{ type: 'linkedin', name: '', url: 'https://li/in/simon' }]} />)

    expect(screen.getByRole('link', { name: 'LinkedIn profile' })).toBeInTheDocument()
  })
})
