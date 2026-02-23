import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ProfileBanner } from '../../src/components/profile/ProfileBanner'
import type { Profile } from '../../src/types/Profile'

const profile: Profile = {
  name: 'Simon Rowe',
  firstName: 'Simon',
  lastName: 'Rowe',
  title: 'Engineering Leader',
  headline: 'PASSIONATE ABOUT BUILDING PRODUCTS',
  description: 'About text',
  profileImage: { url: '/profile.jpg' },
  sidebarImage: { url: '/sidebar.jpg' },
  backgroundImage: { url: '/background.jpg' },
  mobileBackgroundImage: { url: '/mobile-background.jpg' },
  location: 'London',
  phoneNumber: '+440000',
  primaryEmail: 'test@example.com',
  cvUrl: '/api/resume',
  socialMediaLinks: [],
}

describe('ProfileBanner', () => {
  it('renders name, title, headline and background style', () => {
    render(<ProfileBanner profile={profile} />)

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Engineering Leader')
    expect(screen.getByText('PASSIONATE ABOUT BUILDING PRODUCTS')).toBeInTheDocument()

    const banner = screen.getByTestId('profile-banner')
    expect(banner).toHaveStyle('--desktop-bg: url(/background.jpg)')
  })
})
