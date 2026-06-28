import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ThemeProvider } from '../../src/contexts/ThemeContext'
import { TopNav } from '../../src/components/layout/TopNav'
import { HomePage } from '../../src/pages/HomePage'
import type { Profile } from '../../src/types/Profile'

const mockUseProfile = vi.fn()
const openChat = vi.fn()

vi.mock('../../src/hooks/useProfile', () => ({
  useProfile: () => mockUseProfile(),
}))

vi.mock('../../src/services/analytics', () => ({
  trackPageView: vi.fn(),
}))

vi.mock('../../src/contexts/ChatContext', () => ({
  ChatProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useChat: () => ({
    openChat,
    closeChat: vi.fn(),
    chatOpen: false,
    chatQuery: null,
    recaptchaVerified: false,
    showRecaptcha: false,
    handleRecaptchaVerified: vi.fn(),
    cancelRecaptcha: vi.fn(),
  }),
}))

vi.mock('../../src/hooks/useTour', () => ({
  useTour: () => ({
    isActive: false,
    currentStepIndex: 0,
    steps: [],
    searchValue: '',
  }),
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
    { type: 'linkedin', name: 'LinkedIn', url: 'https://linkedin.com/in/simonrowe' },
  ],
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

function renderHomePage() {
  return render(
    <MemoryRouter>
      <HomePage />
    </MemoryRouter>,
  )
}

function renderLandingShell() {
  return render(
    <MemoryRouter>
      <ThemeProvider>
        <TopNav />
        <HomePage />
      </ThemeProvider>
    </MemoryRouter>,
  )
}

describe('HomePage', () => {
  beforeEach(() => {
    mockUseProfile.mockReset()
    openChat.mockReset()
    setMatchMedia(false)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('renders loading state', () => {
    mockUseProfile.mockReturnValue({
      profile: null,
      loading: true,
      error: null,
      retry: vi.fn(),
    })

    renderHomePage()

    expect(screen.getByText('Loading profile...')).toBeInTheDocument()
  })

  it('renders error state', async () => {
    mockUseProfile.mockReturnValue({
      profile: null,
      loading: false,
      error: 'boom',
      retry: vi.fn(),
    })

    renderHomePage()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('boom')
    })
  })

  it('renders hero identity, role, AI chat entry, and prompt chips without homepage profile or contact sections', async () => {
    mockUseProfile.mockReturnValue({
      profile,
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    renderHomePage()

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
    })
    expect(screen.getByText('Engineering Leader')).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/Ask me anything about Simon/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /What Spring Boot and Kafka patterns/i })).toBeInTheDocument()
    expect(document.querySelector('.tour-home-chat')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /About Simon/i })).not.toBeInTheDocument()
    expect(screen.queryByText('About copy')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Download CV/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Get In Touch/i })).not.toBeInTheDocument()
  })

  it('opens chat from prompt chips, hero composer, and top navigation search', async () => {
    const user = userEvent.setup()
    mockUseProfile.mockReturnValue({
      profile,
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    renderLandingShell()

    await user.click(screen.getByRole('button', { name: /What is he blogging about recently/i }))
    expect(openChat).toHaveBeenCalledWith('What is he blogging about recently?')

    await user.type(screen.getByPlaceholderText(/Ask me anything about Simon/i), 'How does Simon lead teams?')
    await user.click(screen.getAllByRole('button', { name: /send/i })[0])
    expect(openChat).toHaveBeenCalledWith('How does Simon lead teams?')

    await user.type(screen.getByRole('searchbox', { name: /search or ask a question/i }), 'Kafka')
    fireEvent.keyDown(screen.getByRole('searchbox', { name: /search or ask a question/i }), { key: 'Enter' })
    expect(openChat).toHaveBeenCalledWith('Kafka')
  })

  it('renders landing navigation and keyboard-accessible prompts, with no footer', async () => {
    const user = userEvent.setup()
    mockUseProfile.mockReturnValue({
      profile,
      loading: false,
      error: null,
      retry: vi.fn(),
    })

    renderLandingShell()

    expect(screen.getAllByRole('link', { name: /Experience/i })[0]).toHaveAttribute('href', '/experience')
    expect(screen.getAllByRole('link', { name: /Blog/i })[0]).toHaveAttribute('href', '/blogs')
    expect(screen.getAllByRole('link', { name: /News & Events/i })[0]).toHaveAttribute('href', '/news-events')
    expect(screen.getAllByRole('link', { name: /Profile/i })[0]).toHaveAttribute('href', '/profile')

    // Footer is removed everywhere, so no footer landmark on the landing page
    expect(screen.queryByRole('contentinfo')).not.toBeInTheDocument()

    const prompt = screen.getByRole('button', { name: /How big are the teams he's led/i })
    prompt.focus()
    await user.keyboard('{Enter}')
    expect(openChat).toHaveBeenCalledWith("How big are the teams he's led?")
  })
})
