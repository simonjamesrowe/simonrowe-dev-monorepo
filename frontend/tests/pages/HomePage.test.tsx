import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { HomePage } from '../../src/pages/HomePage'
import type { Profile } from '../../src/types/Profile'

const mockUseProfile = vi.fn()

vi.mock('../../src/hooks/useProfile', () => ({
  useProfile: () => mockUseProfile(),
}))

vi.mock('../../src/services/analytics', () => ({
  trackHomepageEvent: vi.fn(),
  trackPageView: vi.fn(),
}))

const profile: Profile = {
  name: 'Simon Rowe',
  firstName: 'Simon',
  lastName: 'Rowe',
  title: 'Engineering Leader',
  headline: 'PASSIONATE ABOUT BUILDING PRODUCTS',
  description: 'About copy',
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
  ],
}

describe('HomePage', () => {
  beforeEach(() => {
    mockUseProfile.mockReset()
  })

  it('renders loading state', () => {
    mockUseProfile.mockReturnValue({
      profile: null,
      loading: true,
      error: null,
      retry: vi.fn(),
    })

    render(<HomePage />)

    expect(screen.getByText('Loading profile...')).toBeInTheDocument()
  })

  it('renders error state', () => {
    mockUseProfile.mockReturnValue({
      profile: null,
      loading: false,
      error: 'boom',
      retry: vi.fn(),
    })

    render(<HomePage />)

    expect(screen.getByRole('alert')).toHaveTextContent('boom')
  })

  it('renders profile sections when data is available', () => {
    mockUseProfile.mockReturnValue({
      profile,
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    render(<HomePage />)

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
    expect(screen.getByRole('heading', { level: 3, name: 'About' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: 'Contact' })).toBeInTheDocument()
  })
})
