import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ConnectStrip } from '../../../src/components/home/ConnectStrip'
import type { SocialMediaLink } from '../../../src/types/SocialMediaLink'

const links: SocialMediaLink[] = [
  { type: 'github', name: 'GitHub', url: 'https://github.com/simon' },
  { type: 'linkedin', name: 'LinkedIn', url: 'https://www.linkedin.com/in/simon' },
  { type: 'twitter', name: 'Twitter', url: 'https://twitter.com/simon' },
]

describe('ConnectStrip', () => {
  it('renders the Connect heading', () => {
    render(<ConnectStrip socialMediaLinks={links} />)
    expect(screen.getByText(/connect/i)).toBeInTheDocument()
  })

  it('renders a Download CV link pointing at the resume endpoint', () => {
    render(<ConnectStrip socialMediaLinks={links} />)
    const cv = screen.getByRole('link', { name: /download cv/i })
    expect(cv.getAttribute('href')).toMatch(/\/api\/resume$/)
    expect(cv).toHaveAttribute('target', '_blank')
  })

  it('renders a link for each social media entry, deduplicating by type', () => {
    const dup = [...links, { type: 'github', name: 'GitHub Alt', url: 'https://github.com/other' }]
    render(<ConnectStrip socialMediaLinks={dup} />)
    expect(screen.getAllByRole('link', { name: /github/i })).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: /linkedin/i })).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: /twitter/i })).toHaveLength(1)
  })

  it('renders nothing visible when no links and no CV are available', () => {
    const { container } = render(<ConnectStrip socialMediaLinks={[]} />)
    // Heading + CV button still render — only the social row should be empty
    expect(container.querySelector('.connect-strip__socials')).toBeNull()
  })
})
