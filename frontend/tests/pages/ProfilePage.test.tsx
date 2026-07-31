import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
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

// The drawer's real form pulls in reCAPTCHA and react-hook-form; the page's contract is
// that the drawer is present and openable, not how the form itself behaves.
vi.mock('../../src/components/contact/ContactForm', () => ({
  ContactForm: () => <h2>Contact form</h2>,
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

function renderProfilePage(initialPath = '/profile') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <ProfilePage />
    </MemoryRouter>,
  )
}

function loaded() {
  mockUseProfile.mockReturnValue({
    profile,
    loading: false,
    error: null,
    retry: vi.fn(),
  })
}

describe('ProfilePage', () => {
  beforeEach(() => {
    mockUseProfile.mockReset()
    setMatchMedia(false)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('renders the real About content, with no full-width Connect section', async () => {
    loaded()

    renderProfilePage()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /About Simon/i })).toBeInTheDocument()
    })

    expect(screen.getByText('Profile biography copy')).toBeInTheDocument()
    expect(document.querySelector('.tour-profile')).toBeInTheDocument()

    // The Connect section was replaced by a drawer, so the page itself no longer carries
    // a Connect heading or an in-page contact anchor.
    expect(screen.queryByRole('heading', { name: /^Connect$/ })).not.toBeInTheDocument()
    expect(document.querySelector('.profile-page__connect')).toBeNull()
    expect(document.querySelector('#contact')).toBeNull()
  })

  it('keeps the CV and social links, moved into the contact drawer', async () => {
    loaded()

    // Opened first: a closed drawer is aria-hidden, so its links are correctly absent
    // from the accessibility tree.
    renderProfilePage('/profile#contact')

    await waitFor(() => {
      expect(document.querySelector('.contact-drawer--open')).not.toBeNull()
    })

    expect(screen.getByRole('link', { name: /Download CV/i })).toHaveAttribute(
      'href',
      `${API_BASE_URL}/api/resume`,
    )
    expect(screen.getByRole('link', { name: /GitHub profile/i })).toHaveAttribute(
      'href',
      'https://github.com/simonrowe',
    )
    // The raw URL is no longer printed under each label.
    expect(screen.queryByText('https://github.com/simonrowe')).not.toBeInTheDocument()
  })

  it('opens the contact drawer from the About section call to action', async () => {
    loaded()

    renderProfilePage()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /About Simon/i })).toBeInTheDocument()
    })

    expect(document.querySelector('.contact-drawer--open')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: /Get in touch/i }))

    expect(document.querySelector('.contact-drawer--open')).not.toBeNull()
    expect(screen.getByRole('heading', { name: /Contact form/i })).toBeInTheDocument()
  })

  it('opens the drawer straight away when arriving at /profile#contact', async () => {
    loaded()

    // This is the path the footer bar and the home CTA band both link to.
    renderProfilePage('/profile#contact')

    await waitFor(() => {
      expect(document.querySelector('.contact-drawer--open')).not.toBeNull()
    })
  })

  it('closes the drawer from its close button', async () => {
    loaded()

    renderProfilePage('/profile#contact')

    await waitFor(() => {
      expect(document.querySelector('.contact-drawer--open')).not.toBeNull()
    })

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(document.querySelector('.contact-drawer--open')).toBeNull()
  })

  it('does not show the fabricated headline or invented statistics', async () => {
    loaded()

    renderProfilePage()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /About Simon/i })).toBeInTheDocument()
    })

    expect(screen.queryByText(/Architect of Precision/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Years Leadership/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Scale Managed/i)).not.toBeInTheDocument()
  })
})
