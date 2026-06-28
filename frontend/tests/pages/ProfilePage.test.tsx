import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, afterEach, beforeEach } from 'vitest'

import { ProfilePage } from '../../src/pages/ProfilePage'
import { API_BASE_URL } from '../../src/config/api'
import type { Profile } from '../../src/types/Profile'

const mockUseProfile = vi.fn()

vi.mock('../../src/hooks/useProfile', () => ({
  useProfile: () => mockUseProfile(),
}))

vi.mock('../../src/services/analytics', () => ({
  trackPageView: vi.fn(),
}))

vi.mock('../../src/components/contact/ContactSection', () => ({
  ContactSection: () => (
    <section className="contact-section tour-contact" id="contact">
      <h2>Contact form</h2>
    </section>
  ),
}))

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

const profile: Profile = {
  name: 'Simon Rowe',
  firstName: 'Simon',
  lastName: 'Rowe',
  title: 'Engineering Leader',
  headline: 'PASSIONATE ABOUT BUILDING PRODUCTS',
  description: 'Profile biography copy',
  profileImage: { url: '/profile.jpg' },
  sidebarImage: { url: '/sidebar.jpg' },
  backgroundImage: { url: '/background.jpg' },
  mobileBackgroundImage: { url: '/mobile-background.jpg' },
  location: 'London',
  phoneNumber: '+440000',
  primaryEmail: 'test@example.com',
  cvUrl: '/api/resume',
  socialMediaLinks: [
    { type: 'github', name: 'GitHub', url: 'https://github.com/simonrowe' },
    { type: 'linkedin', name: 'LinkedIn', url: 'https://linkedin.com/in/simonrowe' },
  ],
}

describe('ProfilePage', () => {
  beforeEach(() => {
    mockUseProfile.mockReset()
    setMatchMedia(false)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('renders the real About content, CV/social actions, and contact on one page', async () => {
    mockUseProfile.mockReturnValue({
      profile,
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    render(<ProfilePage />)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /About Simon/i })).toBeInTheDocument()
    })

    expect(screen.getByText('Profile biography copy')).toBeInTheDocument()
    expect(document.querySelector('.tour-profile')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Download CV/i })).toHaveAttribute('href', `${API_BASE_URL}/api/resume`)
    expect(screen.getByRole('link', { name: /GitHub profile/i })).toHaveAttribute('href', 'https://github.com/simonrowe')
    expect(document.querySelector('#contact.tour-contact')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Contact form/i })).toBeInTheDocument()
  })

  it('does not show the fabricated headline or invented statistics', async () => {
    mockUseProfile.mockReturnValue({
      profile,
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    render(<ProfilePage />)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /About Simon/i })).toBeInTheDocument()
    })

    expect(screen.queryByText(/Architect of Precision/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Years Leadership/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Scale Managed/i)).not.toBeInTheDocument()
  })
})
