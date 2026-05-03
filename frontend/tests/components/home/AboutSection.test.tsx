import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AboutSection } from '../../../src/components/home/AboutSection'
import type { Profile } from '../../../src/types/Profile'

const description = [
  'Para one. Driven by business value.',
  'Para two. Years of experience leading teams.',
  'Para three. Strong advocate for AI-native engineering.',
  'Para four. Toolkit includes Java, Kotlin, Spring.',
].join('\n\n')

const profile: Profile = {
  name: 'Simon Rowe',
  firstName: 'Simon',
  lastName: 'Rowe',
  title: 'Engineer',
  headline: 'Headline',
  description,
  profileImage: { url: '/img.png' },
  sidebarImage: { url: '/img.png' },
  backgroundImage: { url: '/img.png' },
  mobileBackgroundImage: { url: '/img.png' },
  location: '',
  phoneNumber: '',
  primaryEmail: '',
  socialMediaLinks: [],
}

function setMatchMedia(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  )
}

describe('AboutSection', () => {
  beforeEach(() => vi.unstubAllGlobals())
  afterEach(() => vi.unstubAllGlobals())

  it('shows full description on desktop and renders no Read more button', () => {
    setMatchMedia(false)
    render(<AboutSection profile={profile} onContact={() => {}} />)
    expect(screen.getByText(/Para one\./)).toBeInTheDocument()
    expect(screen.getByText(/Para four\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /read more/i })).not.toBeInTheDocument()
  })

  it('shows only the first two paragraphs on mobile by default', () => {
    setMatchMedia(true)
    render(<AboutSection profile={profile} onContact={() => {}} />)
    expect(screen.getByText(/Para one\./)).toBeInTheDocument()
    expect(screen.getByText(/Para two\./)).toBeInTheDocument()
    expect(screen.queryByText(/Para three\./)).not.toBeInTheDocument()
    expect(screen.queryByText(/Para four\./)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /read more/i })).toBeInTheDocument()
  })

  it('expands all paragraphs when Read more is clicked, then collapses again', () => {
    setMatchMedia(true)
    render(<AboutSection profile={profile} onContact={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /read more/i }))
    expect(screen.getByText(/Para three\./)).toBeInTheDocument()
    expect(screen.getByText(/Para four\./)).toBeInTheDocument()
    const lessButton = screen.getByRole('button', { name: /read less/i })
    fireEvent.click(lessButton)
    expect(screen.queryByText(/Para three\./)).not.toBeInTheDocument()
  })

  it('does not render a Read more button if the description has fewer than 3 paragraphs', () => {
    setMatchMedia(true)
    const shortProfile = { ...profile, description: 'Only one para.\n\nAnd a second.' }
    render(<AboutSection profile={shortProfile} onContact={() => {}} />)
    expect(screen.queryByRole('button', { name: /read more/i })).not.toBeInTheDocument()
  })
})
