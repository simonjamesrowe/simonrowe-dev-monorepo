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
  trackHomepageEvent: vi.fn(),
  trackPageView: vi.fn(),
}))

vi.mock('../../src/components/employment/ExperienceSection', () => ({
  ExperienceSection: () => <div data-testid="experience-section">Experience</div>,
}))

vi.mock('../../src/components/skills/SkillsSection', () => ({
  SkillsSection: () => <div data-testid="skills-section">Skills</div>,
}))

vi.mock('../../src/components/skills/SkillGroupDetail', () => ({
  SkillGroupDetail: () => <div data-testid="skill-group-detail">Skill Group Detail</div>,
}))

vi.mock('../../src/components/employment/JobDetail', () => ({
  JobDetail: () => <div data-testid="job-detail">Job Detail</div>,
}))

vi.mock('../../src/components/common/ResumeDownloadButton', () => ({
  ResumeDownloadButton: () => <div data-testid="resume-download">Resume</div>,
}))

vi.mock('../../src/components/tour/TourButton', () => ({
  TourButton: () => <div data-testid="tour-button">Tour</div>,
}))

vi.mock('../../src/components/tour/TourOverlay', () => ({
  TourOverlay: () => null,
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

  it('renders profile sections when data is available', () => {
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

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
    expect(screen.getByRole('heading', { level: 3, name: 'About' })).toBeInTheDocument()
    expect(screen.getByTestId('experience-section')).toBeInTheDocument()
    expect(screen.getByTestId('skills-section')).toBeInTheDocument()
    expect(screen.getByTestId('resume-download')).toBeInTheDocument()
  })
})
