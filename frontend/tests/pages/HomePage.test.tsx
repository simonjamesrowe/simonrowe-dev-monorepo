import { render, screen, waitFor } from '@testing-library/react'
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

vi.mock('../../src/components/home/AboutSection', () => ({
  AboutSection: ({ onContact }: { onContact: () => void }) => (
    <div data-testid="about-section"><button onClick={onContact}>Contact</button></div>
  ),
}))

vi.mock('../../src/components/home/CTASection', () => ({
  CTASection: () => <div data-testid="cta-section">CTA</div>,
}))

vi.mock('../../src/components/contact/ContactDrawer', () => ({
  ContactDrawer: ({ open }: { open: boolean }) => (
    open ? <div data-testid="contact-drawer">Drawer</div> : null
  ),
}))

vi.mock('../../src/components/chat/ChatPanel', () => ({
  ChatPanel: () => <div data-testid="chat-panel">Chat</div>,
}))

vi.mock('../../src/components/chat/RecaptchaGate', () => ({
  RecaptchaGate: ({ onVerified }: { onVerified: () => void }) => (
    <div data-testid="recaptcha-gate"><button onClick={onVerified}>Verify</button></div>
  ),
}))

vi.mock('../../src/components/home/HeroSection', () => ({
  HeroSection: ({ name, onChatOpen }: { name: string; onChatOpen: (q: string) => void }) => (
    <div data-testid="hero-section">
      <h1>{name}</h1>
      <button onClick={() => onChatOpen('test query')}>Open Chat</button>
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

  it('renders error state', async () => {
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

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('boom')
    })
  })

  it('renders hero, about, and CTA when profile loads', async () => {
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

    await waitFor(() => {
      expect(screen.getByTestId('hero-section')).toBeInTheDocument()
    })
    expect(screen.getByTestId('about-section')).toBeInTheDocument()
    expect(screen.getByTestId('cta-section')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
  })
})
