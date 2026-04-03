import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { HomePage } from '../../src/pages/HomePage'
import type { Profile } from '../../src/types/Profile'

const mockUseProfile = vi.fn()

vi.mock('../../src/hooks/useProfile', () => ({
  useProfile: () => mockUseProfile(),
}))

vi.mock('../../src/services/analytics', () => ({
  trackPageView: vi.fn(),
}))

vi.mock('../../src/components/home/AIChatModule', () => ({
  AIChatModule: () => <div data-testid="ai-chat-module">Chat</div>,
}))

vi.mock('../../src/components/home/StatsGrid', () => ({
  StatsGrid: () => <div data-testid="stats-grid">Stats</div>,
}))

vi.mock('../../src/components/home/CTASection', () => ({
  CTASection: () => <div data-testid="cta-section">CTA</div>,
}))

vi.mock('../../src/components/home/HeroSection', () => ({
  HeroSection: ({ name, children }: { name: string; children: React.ReactNode }) => (
    <div data-testid="hero-section">
      <h1>{name}</h1>
      {children}
    </div>
  ),
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

    render(
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>,
    )

    expect(screen.getByText('Loading profile...')).toBeInTheDocument()
  })

  it('renders error state', () => {
    mockUseProfile.mockReturnValue({
      profile: null,
      loading: false,
      error: 'boom',
      retry: vi.fn(),
    })

    render(
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('boom')
  })

  it('renders hero, stats, and CTA when profile loads', () => {
    mockUseProfile.mockReturnValue({
      profile,
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    render(
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>,
    )

    expect(screen.getByTestId('hero-section')).toBeInTheDocument()
    expect(screen.getByTestId('ai-chat-module')).toBeInTheDocument()
    expect(screen.getByTestId('stats-grid')).toBeInTheDocument()
    expect(screen.getByTestId('cta-section')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
  })
})
