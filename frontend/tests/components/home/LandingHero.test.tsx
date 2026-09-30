import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { LandingHero } from '../../../src/components/home/LandingHero'
import { DEFAULT_HOME_PAGE, type HomePageContent } from '../../../src/types/homePage'
import type { Profile } from '../../../src/types/Profile'

const openChat = vi.fn()
const startTour = vi.fn()
vi.mock('../../../src/contexts/ChatContext', () => ({ useChat: () => ({ openChat }) }))
vi.mock('../../../src/hooks/useTour', () => ({ useTour: () => ({ start: startTour }) }))

const profile = {
  name: 'Simon Rowe',
  title: 'Software Engineering Leader',
  location: 'London',
  backgroundImage: { url: '/uploads/desktop.jpg' },
  mobileBackgroundImage: null,
} as unknown as Profile

function renderHero(content: Partial<HomePageContent> = {}, heroProfile: Profile = profile) {
  return render(
    <MemoryRouter>
      <LandingHero content={{ ...DEFAULT_HOME_PAGE, ...content }} profile={heroProfile} />
    </MemoryRouter>,
  )
}

describe('LandingHero', () => {
  beforeEach(() => {
    openChat.mockReset()
    startTour.mockReset()
  })

  it('routes a site-path call to action in-app and sends an https one off-site safely', () => {
    renderHero({
      primaryCta: { label: 'Roles', href: '/about#roles' },
      secondaryCta: { label: 'Term Time', href: 'https://term-time.simonrowe.dev' },
    })
    expect(screen.getByRole('link', { name: 'Roles' })).toHaveAttribute('href', '/about#roles')
    const external = screen.getByRole('link', { name: 'Term Time' })
    expect(external).toHaveAttribute('href', 'https://term-time.simonrowe.dev')
    expect(external).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('omits what the editor left empty', () => {
    renderHero({ secondaryCta: null, lede: null, showTourLink: false, askPill: { lead: null, label: 'Ask', buttonLabel: 'Go' } })
    expect(screen.getAllByRole('link')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: /tour/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Ask\s*Go/ })).toBeInTheDocument()
  })

  it('opens the chat from the pill and starts the tour from its link', async () => {
    const user = userEvent.setup()
    renderHero()
    const pill = screen.getByRole('button', { name: /Got a question\?\s*Ask Simon anything\s*Start chat/ })
    expect(pill).toHaveClass('tour-home-chat')
    expect(pill).toHaveAttribute('data-ask-anchor')
    await user.click(pill)
    expect(openChat).toHaveBeenCalledWith()

    await user.click(screen.getByRole('button', { name: /Take a tour/ }))
    expect(startTour).toHaveBeenCalledTimes(1)
  })

  it('uses the desktop image on phones when the profile has no mobile one', () => {
    const { container } = renderHero()
    expect(container.querySelector('source')).toBeNull()
    expect(container.querySelector('img')?.getAttribute('src')).toMatch(/\/uploads\/desktop\.jpg$/)
  })

  it('drops a phone image that fails to load, then the desktop one, never leaving a broken image', () => {
    const withMobile = { ...profile, mobileBackgroundImage: { url: '/uploads/mobile.jpg' } } as unknown as Profile
    const { container } = renderHero({}, withMobile)
    const img = container.querySelector('img')!
    const mobileSrc = container.querySelector('source')?.getAttribute('srcset') ?? ''
    expect(mobileSrc).toMatch(/mobile\.jpg$/)

    // The browser reports whichever candidate it chose as currentSrc; here, the phone image.
    Object.defineProperty(img, 'currentSrc', { configurable: true, get: () => mobileSrc })
    fireEvent.error(img)
    expect(container.querySelector('source')).toBeNull()
    expect(container.querySelector('img')).toBeInTheDocument()

    const desktop = container.querySelector('img')!
    Object.defineProperty(desktop, 'currentSrc', { configurable: true, get: () => desktop.getAttribute('src') ?? '' })
    fireEvent.error(desktop)
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('still renders, on a plain surface, when the profile has no images at all', () => {
    const { container } = renderHero({}, { ...profile, backgroundImage: null } as unknown as Profile)
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Leading engineering teams.')
  })

  it('keeps a hyphenated headline word whole so it cannot break at the hyphen', () => {
    const { container } = renderHero({ headlineLine2: 'Building AI-native systems.' })
    const nobreak = container.querySelectorAll('.landing-hero__nobreak')
    expect(nobreak).toHaveLength(1)
    expect(nobreak[0]).toHaveTextContent('AI-native')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Building AI-native systems.')
  })
})
