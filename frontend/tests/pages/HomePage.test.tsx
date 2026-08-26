import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ThemeProvider } from '../../src/contexts/ThemeContext'
import { TopNav } from '../../src/components/layout/TopNav'
import { HomePage } from '../../src/pages/HomePage'
import type { BlogSummary } from '../../src/types/blog'
import type { IJob } from '../../src/types/job'
import type { Profile } from '../../src/types/Profile'

const mockUseProfile = vi.fn()
const openChat = vi.fn()

vi.mock('../../src/hooks/useProfile', () => ({
  useProfile: () => mockUseProfile(),
}))

vi.mock('../../src/services/analytics', () => ({
  trackPageView: vi.fn(),
}))

vi.mock('../../src/services/jobsApi', () => ({
  fetchJobs: vi.fn(),
}))

vi.mock('../../src/services/blogApi', () => ({
  fetchLatestBlogs: vi.fn(),
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

import { DrawerProvider } from '../../src/hooks/useDrawer'
import { fetchLatestBlogs } from '../../src/services/blogApi'
import { fetchJobs } from '../../src/services/jobsApi'
import { NarrationAudioStub } from '../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../testUtils/narrationAudioValue'

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

const jobs: IJob[] = [
  {
    id: 'current',
    title: 'Head of Engineering',
    company: 'Global',
    companyImage: { url: '/uploads/global.png' },
    startDate: '2021-08-01',
    endDate: null,
    location: 'London',
    shortDescription: 'Leading the engineering function.',
    isEducation: false,
    includeOnResume: true,
  },
  {
    id: 'previous',
    title: 'CTO',
    company: 'Y-Tree',
    companyImage: { url: '/uploads/ytree.png' },
    startDate: '2019-01-01',
    endDate: '2021-07-01',
    location: 'London',
    shortDescription: 'Built the platform.',
    isEducation: false,
    includeOnResume: true,
  },
]

const posts: BlogSummary[] = [
  {
    id: 'b-1',
    title: 'Event Sourcing With Kafka',
    shortDescription: 'On streaming',
    createdDate: '2024-06-01T10:00:00Z',
    tags: [{ name: 'Kafka' }],
    contentType: 'ENGINEERING',
  },
]

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
      <NarrationAudioStub value={narrationAudioStub()}>
        <DrawerProvider>
          <HomePage />
        </DrawerProvider>
      </NarrationAudioStub>
    </MemoryRouter>,
  )
}

function renderLandingShell() {
  return render(
    <MemoryRouter>
      <NarrationAudioStub value={narrationAudioStub()}>
        <ThemeProvider>
          <DrawerProvider>
            <TopNav />
            <HomePage />
          </DrawerProvider>
        </ThemeProvider>
      </NarrationAudioStub>
    </MemoryRouter>,
  )
}

function loadedProfile() {
  mockUseProfile.mockReturnValue({
    profile,
    loading: false,
    error: null,
    retry: vi.fn(),
  })
}

describe('HomePage', () => {
  beforeEach(() => {
    mockUseProfile.mockReset()
    openChat.mockReset()
    vi.mocked(fetchJobs).mockReset().mockResolvedValue(jobs)
    vi.mocked(fetchLatestBlogs).mockReset().mockResolvedValue(posts)
    setMatchMedia(false)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('renders loading state', async () => {
    mockUseProfile.mockReturnValue({
      profile: null,
      loading: true,
      error: null,
      retry: vi.fn(),
    })

    renderHomePage()

    expect(screen.getByText('Loading profile...')).toBeInTheDocument()
    // Let the section fetches settle so their state updates stay inside act().
    await waitFor(() => expect(vi.mocked(fetchLatestBlogs)).toHaveBeenCalled())
  })

  it('renders the page-level error state when the profile fetch fails', async () => {
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
    expect(document.querySelector('.hero')).not.toBeInTheDocument()
  })

  it('sets the bare site title', async () => {
    loadedProfile()

    renderHomePage()

    await waitFor(() => {
      expect(document.title).toBe('Simon Rowe | Software Engineering Leader')
    })
  })

  it('requests the ten latest engineering posts for the carousel', async () => {
    loadedProfile()

    renderHomePage()

    await waitFor(() => {
      expect(vi.mocked(fetchLatestBlogs)).toHaveBeenCalledWith(10, 'ENGINEERING')
    })
    expect(vi.mocked(fetchJobs)).toHaveBeenCalledTimes(1)
  })

  it('renders the four data-driven sections in order below the hero', async () => {
    loadedProfile()

    const { container } = renderHomePage()

    await waitFor(() => {
      expect(container.querySelector('.featured-writing')).toBeInTheDocument()
    })

    const order = Array.from(
      container.querySelectorAll(
        '.hero, .currently-strip, .employer-logo-strip, .featured-writing, .cta-section',
      ),
    ).map((element) => element.classList[0])

    expect(order).toEqual([
      'hero',
      'currently-strip',
      'employer-logo-strip',
      'featured-writing',
      'cta-section',
    ])

    // Real content, sourced from the mocked jobs and blog data.
    expect(screen.getByText('Leading the engineering function.')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Global' })).toBeInTheDocument()
    expect(screen.getByText('Event Sourcing With Kafka')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Get in touch' })).toHaveAttribute(
      'href',
      '/profile#contact',
    )
    expect(screen.getByRole('link', { name: /Download CV/i })).toBeInTheDocument()
  })

  it('drops the jobs-backed sections without erroring the page when fetchJobs rejects', async () => {
    loadedProfile()
    vi.mocked(fetchJobs).mockRejectedValue(new Error('Unable to load jobs data.'))

    const { container } = renderHomePage()

    await waitFor(() => {
      expect(container.querySelector('.featured-writing')).toBeInTheDocument()
    })

    expect(container.querySelector('.currently-strip')).not.toBeInTheDocument()
    expect(container.querySelector('.employer-logo-strip')).not.toBeInTheDocument()
    expect(container.querySelector('.hero')).toBeInTheDocument()
    expect(container.querySelector('.cta-section')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/Unable to load jobs data/i)).not.toBeInTheDocument()
  })

  it('drops only the writing section when fetchLatestBlogs rejects', async () => {
    loadedProfile()
    vi.mocked(fetchLatestBlogs).mockRejectedValue(new Error('Unable to load blog data.'))

    const { container } = renderHomePage()

    await waitFor(() => {
      expect(container.querySelector('.currently-strip')).toBeInTheDocument()
    })

    expect(container.querySelector('.featured-writing')).not.toBeInTheDocument()
    expect(container.querySelector('.employer-logo-strip')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('renders hero identity, role, AI chat entry, and prompt chips without the profile about section', async () => {
    loadedProfile()

    renderHomePage()

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
    })
    expect(screen.getByText('Engineering Leader')).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/Ask me anything about Simon/i)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /What Spring Boot and Kafka patterns/i }),
    ).toBeInTheDocument()
    expect(document.querySelector('.tour-home-chat')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /About Simon/i })).not.toBeInTheDocument()
    expect(screen.queryByText('About copy')).not.toBeInTheDocument()
  })

  it('opens chat from prompt chips, hero composer, and top navigation search', async () => {
    const user = userEvent.setup()
    loadedProfile()

    renderLandingShell()

    await user.click(screen.getByRole('button', { name: /What is he blogging about recently/i }))
    expect(openChat).toHaveBeenCalledWith('What is he blogging about recently?')

    await user.type(
      screen.getByPlaceholderText(/Ask me anything about Simon/i),
      'How does Simon lead teams?',
    )
    await user.click(screen.getAllByRole('button', { name: /send/i })[0])
    expect(openChat).toHaveBeenCalledWith('How does Simon lead teams?')

    await user.type(screen.getByRole('searchbox', { name: /search or ask a question/i }), 'Kafka')
    fireEvent.keyDown(screen.getByRole('searchbox', { name: /search or ask a question/i }), {
      key: 'Enter',
    })
    expect(openChat).toHaveBeenCalledWith('Kafka')
  })

  it('renders landing navigation and keyboard-accessible prompts', async () => {
    const user = userEvent.setup()
    loadedProfile()

    renderLandingShell()

    expect(screen.getAllByRole('link', { name: /Experience/i })[0]).toHaveAttribute(
      'href',
      '/experience',
    )
    expect(screen.getAllByRole('link', { name: /Blog/i })[0]).toHaveAttribute('href', '/blogs')
    expect(screen.getAllByRole('link', { name: /News & Events/i })[0]).toHaveAttribute(
      'href',
      '/news-events',
    )
    expect(screen.getAllByRole('link', { name: /Profile/i })[0]).toHaveAttribute('href', '/profile')

    // The footer lives in the layout, not the page, so there is no footer landmark here.
    expect(screen.queryByRole('contentinfo')).not.toBeInTheDocument()

    const prompt = screen.getByRole('button', { name: /How big are the teams he's led/i })
    prompt.focus()
    await user.keyboard('{Enter}')
    expect(openChat).toHaveBeenCalledWith("How big are the teams he's led?")
  })
})
