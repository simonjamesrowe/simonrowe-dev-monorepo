import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi, afterEach, beforeEach } from 'vitest'

import { AboutPage } from '../../src/pages/AboutPage'
import { API_BASE_URL } from '../../src/config/api'
import type { Profile } from '../../src/types/Profile'

const mockUseProfile = vi.fn()

vi.mock('../../src/hooks/useProfile', () => ({
  useProfile: () => mockUseProfile(),
}))

const drawerState = {
  selectedJobId: null as string | null,
  selectedGroupId: null as string | null,
  openJob: vi.fn(),
  openSkillGroup: vi.fn(),
  closeJob: vi.fn(),
  closeSkillGroup: vi.fn(),
}

vi.mock('../../src/hooks/useDrawer', () => ({
  useDrawer: () => drawerState,
}))

vi.mock('../../src/services/analytics', () => ({
  trackPageView: vi.fn(),
}))

// The drawer's real form pulls in reCAPTCHA and react-hook-form; the page's contract is
// that the drawer is present and openable, not how the form itself behaves.
vi.mock('../../src/components/contact/ContactForm', () => ({
  ContactForm: () => <h2>Contact form</h2>,
}))

vi.mock('../../src/components/experience/RoleTimeline', () => ({
  RoleTimeline: () => <div data-testid="role-timeline" />,
}))

vi.mock('../../src/components/skills/SkillGroupGrid', () => ({
  SkillGroupGrid: () => <div data-testid="skill-group-grid" />,
}))

Element.prototype.scrollIntoView = vi.fn()

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

function renderAt(initialPath = '/about') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AboutPage />
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

beforeEach(() => {
  vi.clearAllMocks()
  mockUseProfile.mockReset()
  drawerState.selectedJobId = null
  drawerState.selectedGroupId = null
  setMatchMedia(false)
})

afterEach(() => vi.unstubAllGlobals())

describe('AboutPage profile', () => {
  it('renders the real About content, with no full-width Connect section', async () => {
    loaded()

    renderAt()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /About Simon/i })).toBeInTheDocument()
    })

    expect(screen.getByText('Profile biography copy')).toBeInTheDocument()
    expect(document.querySelector('.tour-profile')).toBeInTheDocument()

    // The Connect section was replaced by a drawer, so the page itself no longer carries
    // a Connect heading or an in-page contact anchor.
    expect(screen.queryByRole('heading', { name: /^Connect$/ })).not.toBeInTheDocument()
    expect(document.querySelector('#contact')).toBeNull()
  })

  it('keeps the CV and social links, moved into the contact drawer', async () => {
    loaded()

    // Opened first: a closed drawer is aria-hidden, so its links are correctly absent
    // from the accessibility tree.
    renderAt('/about#contact')

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

    renderAt()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /About Simon/i })).toBeInTheDocument()
    })

    expect(document.querySelector('.contact-drawer--open')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: /Get in touch/i }))

    expect(document.querySelector('.contact-drawer--open')).not.toBeNull()
    expect(screen.getByRole('heading', { name: /Contact form/i })).toBeInTheDocument()
  })

  it('opens the drawer straight away when arriving at /about#contact', async () => {
    loaded()

    // This is the path the footer bar and the home CTA band both link to.
    renderAt('/about#contact')

    await waitFor(() => {
      expect(document.querySelector('.contact-drawer--open')).not.toBeNull()
    })
  })

  it('closes the drawer from its close button', async () => {
    loaded()

    renderAt('/about#contact')

    await waitFor(() => {
      expect(document.querySelector('.contact-drawer--open')).not.toBeNull()
    })

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(document.querySelector('.contact-drawer--open')).toBeNull()
  })

  it('does not show the fabricated headline or invented statistics', async () => {
    loaded()

    renderAt()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /About Simon/i })).toBeInTheDocument()
    })

    expect(screen.queryByText(/Architect of Precision/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Years Leadership/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/Scale Managed/i)).not.toBeInTheDocument()
  })

  it('sets a page-identifying document title', () => {
    loaded()

    renderAt()

    expect(document.title).toBe('About · Simon Rowe')
  })
})

describe('AboutPage experience deep links', () => {
  beforeEach(() => loaded())

  it('opens the job drawer for /about?job=<id>', () => {
    renderAt('/about?job=job-1')

    expect(drawerState.openJob).toHaveBeenCalledWith('job-1')
    expect(drawerState.openSkillGroup).not.toHaveBeenCalled()
  })

  it('opens the skill-group drawer for /about?skillGroup=<id>', () => {
    renderAt('/about?skillGroup=group-1')

    expect(drawerState.openSkillGroup).toHaveBeenCalledWith('group-1')
    expect(drawerState.openJob).not.toHaveBeenCalled()
  })

  it('does not throw for an unknown id (degrades gracefully)', () => {
    expect(() => renderAt('/about?job=does-not-exist')).not.toThrow()
    expect(drawerState.openJob).toHaveBeenCalledWith('does-not-exist')
  })

  it('renders stable section ids for hash navigation', () => {
    renderAt()

    expect(document.getElementById('roles')).not.toBeNull()
    expect(document.getElementById('skills')).not.toBeNull()
  })

  it('scrolls to the #skills section when a hash is present', () => {
    renderAt('/about#skills')

    const skills = document.getElementById('skills')
    expect(skills).not.toBeNull()
    expect(skills!.scrollIntoView).toHaveBeenCalled()
  })

  it('holds the hash scroll until the profile above the sections has settled', () => {
    mockUseProfile.mockReturnValue({ profile: null, loading: true, error: null, retry: vi.fn() })

    renderAt('/about#skills')

    expect(document.getElementById('skills')!.scrollIntoView).not.toHaveBeenCalled()
  })

  it('renders the profile before the roles, and the roles before the skills', () => {
    renderAt()

    const profileSlot = document.querySelector('.tour-profile')!
    const roles = document.getElementById('roles')!
    const skills = document.getElementById('skills')!

    expect(profileSlot.compareDocumentPosition(roles) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(roles.compareDocumentPosition(skills) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

/**
 * Each section owns its own failure: `RoleTimeline` and `SkillGroupGrid` own their
 * requests and error frames, and the profile's loading/error states render inline in its
 * own slot. These assertions pin that contract rather than a page-level error state.
 */
describe('AboutPage section independence', () => {
  it('renders both experience sections with no page-level error frame', () => {
    loaded()

    renderAt()

    expect(screen.getByTestId('role-timeline')).toBeInTheDocument()
    expect(screen.getByTestId('skill-group-grid')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('keeps roles and skills on the page when the profile fails to load', async () => {
    const retry = vi.fn()
    mockUseProfile.mockReturnValue({ profile: null, loading: false, error: 'Profile down', retry })

    renderAt()

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Profile down')
    // The error sits in the profile slot, not in place of the page.
    expect(document.querySelector('.tour-profile')).toContainElement(alert)
    expect(screen.getByTestId('role-timeline')).toBeInTheDocument()
    expect(screen.getByTestId('skill-group-grid')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(retry).toHaveBeenCalled()
  })

  it('keeps roles and skills on the page while the profile is loading', () => {
    mockUseProfile.mockReturnValue({ profile: null, loading: true, error: null, retry: vi.fn() })

    renderAt()

    expect(document.querySelector('.tour-profile')).toContainElement(screen.getByRole('status'))
    expect(screen.getByTestId('role-timeline')).toBeInTheDocument()
    expect(screen.getByTestId('skill-group-grid')).toBeInTheDocument()
  })
})
