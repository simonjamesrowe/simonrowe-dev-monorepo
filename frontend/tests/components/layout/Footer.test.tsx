import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { Footer } from '../../../src/components/layout/Footer'
import type { Profile } from '../../../src/types/Profile'

vi.mock('../../../src/services/profileApi', () => ({
  fetchProfile: vi.fn(),
}))

import { fetchProfile } from '../../../src/services/profileApi'

const profile: Profile = {
  name: 'Simon Rowe',
  firstName: 'Simon',
  lastName: 'Rowe',
  title: 'Head of Engineering',
  headline: 'Engineering leadership and AI-native systems.',
  description: 'About copy',
  profileImage: { url: '/profile.jpg' },
  sidebarImage: { url: '/sidebar.jpg' },
  backgroundImage: { url: '/background.jpg' },
  mobileBackgroundImage: { url: '/mobile-background.jpg' },
  location: 'London',
  phoneNumber: '+440000',
  primaryEmail: 'simon@example.com',
  socialMediaLinks: [
    { type: 'github', name: 'GitHub — personal', url: 'https://github.com/simonjamesrowe' },
    { type: 'github', name: 'GitHub — this site', url: 'https://github.com/simonrowe-dev' },
    { type: 'linkedin', name: 'LinkedIn', url: 'https://linkedin.com/in/simonrowe' },
    { type: 'twitter', name: 'Twitter', url: 'https://twitter.com/simonrowe' },
  ],
}

function renderFooter() {
  return render(
    <MemoryRouter>
      <Footer />
    </MemoryRouter>,
  )
}

describe('Footer', () => {
  beforeEach(() => {
    vi.mocked(fetchProfile).mockReset()
  })

  it('is a contentinfo landmark', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(profile)

    renderFooter()

    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    await waitFor(() => expect(vi.mocked(fetchProfile)).toHaveBeenCalled())
  })

  it('is one bar: copyright, connect icons and a single contact link', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(profile)

    renderFooter()

    await waitFor(() => expect(vi.mocked(fetchProfile)).toHaveBeenCalled())

    expect(document.querySelector('.footer__copyright')?.textContent).toContain('Simon Rowe')
    expect(screen.getByRole('link', { name: 'Get in touch' })).toBeInTheDocument()

    // The brand block, positioning statement and nav column were removed after review;
    // the nav duplicated the top navigation, which is on every page anyway.
    expect(screen.queryByRole('navigation', { name: 'Footer' })).not.toBeInTheDocument()
    expect(
      screen.queryByText('Engineering leadership and AI-native systems.'),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'MCP' })).not.toBeInTheDocument()
  })

  it('sends Get in touch to the contact drawer on the profile page', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(profile)

    renderFooter()

    // ProfilePage opens its drawer when the hash is #contact.
    expect(screen.getByRole('link', { name: 'Get in touch' })).toHaveAttribute(
      'href',
      '/profile#contact',
    )

    await waitFor(() => expect(vi.mocked(fetchProfile)).toHaveBeenCalled())
  })

  it('renders icon-only social links, keeping distinctly named duplicates', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(profile)

    renderFooter()

    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'GitHub — personal' })).toHaveAttribute(
        'href',
        'https://github.com/simonjamesrowe',
      )
    })
    expect(screen.getByRole('link', { name: 'GitHub — this site' })).toHaveAttribute(
      'href',
      'https://github.com/simonrowe-dev',
    )
    expect(screen.getByRole('link', { name: 'LinkedIn' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Twitter' })).toBeInTheDocument()

    // Icon-only: the label is the accessible name and the tooltip, not visible text.
    const github = screen.getByRole('link', { name: 'GitHub — personal' })
    expect(github).toHaveAttribute('title', 'GitHub — personal')
    expect(github.textContent).toBe('')
  })

  it('renders the contact email as an icon link', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(profile)

    renderFooter()

    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'Email simon@example.com' })).toHaveAttribute(
        'href',
        'mailto:simon@example.com',
      )
    })
  })

  it('renders a Download CV link to the resume endpoint', async () => {
    vi.mocked(fetchProfile).mockResolvedValue(profile)

    renderFooter()

    const cv = screen.getByRole('link', { name: 'Download CV' })
    expect(cv.getAttribute('href')).toMatch(/\/api\/resume$/)
    expect(cv).toHaveAttribute('target', '_blank')

    await waitFor(() => expect(vi.mocked(fetchProfile)).toHaveBeenCalled())
  })

  it('renders its static parts when the profile fetch fails, and never an error frame', async () => {
    vi.mocked(fetchProfile).mockRejectedValue(new Error('Unable to load profile data.'))

    renderFooter()

    await waitFor(() => expect(vi.mocked(fetchProfile)).toHaveBeenCalled())

    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Get in touch' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Download CV' })).toBeInTheDocument()
    expect(document.querySelector('.footer__copyright')?.textContent).toContain('Simon Rowe')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/Unable to load profile data/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/loading/i)).not.toBeInTheDocument()
  })
})
