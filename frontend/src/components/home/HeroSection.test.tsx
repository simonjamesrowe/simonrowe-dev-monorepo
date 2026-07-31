import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { HeroSection } from './HeroSection'

const openChat = vi.fn()

vi.mock('../../contexts/ChatContext', () => ({
  useChat: () => ({ openChat }),
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

describe('HeroSection', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renders Simon identity, role, AI chat entry, and prompt chips on desktop', () => {
    setMatchMedia(false)
    render(
      <HeroSection
        name="Simon Rowe"
        title="Head of Engineering, Commercial Trading at Global"
        tagline="Passionate about AI-native development."
      />,
    )

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
    expect(screen.getByText('Head of Engineering, Commercial Trading at Global')).toBeInTheDocument()
    expect(screen.getByText(/Engineering Leadership \/\/ AI-Native Systems/i)).toBeInTheDocument()
    expect(screen.getByText('Passionate about AI-native development.')).toBeInTheDocument()
    expect(screen.getByText(/Chat with an AI assistant/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /What Spring Boot and Kafka patterns/i })).toBeInTheDocument()
    expect(document.querySelector('.tour-home-chat')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Download CV/i })).not.toBeInTheDocument()
    // All four suggested prompts on desktop.
    expect(document.querySelectorAll('.hero__prompt-chip')).toHaveLength(4)
  })

  it('shows the badge, tagline, and exactly two prompt chips on mobile', () => {
    setMatchMedia(true)
    render(
      <HeroSection
        name="Simon Rowe"
        title="Head of Engineering"
        tagline="Passionate about AI-native development."
      />,
    )

    // Identity, role, chat intro, and input remain
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Simon Rowe')
    expect(screen.getByText('Head of Engineering')).toBeInTheDocument()
    expect(screen.getByText(/Chat with an AI assistant/i)).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/Ask me anything about Simon/i)).toBeInTheDocument()

    // Badge and tagline are now rendered at mobile widths too (FR-024).
    expect(screen.getByText(/Engineering Leadership \/\/ AI-Native Systems/i)).toBeInTheDocument()
    expect(screen.getByText('Passionate about AI-native development.')).toBeInTheDocument()

    // Exactly the first two suggested prompts.
    const chips = document.querySelectorAll('.hero__prompt-chip')
    expect(chips).toHaveLength(2)
    expect(screen.getByRole('button', { name: /What Spring Boot and Kafka patterns/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /What is he blogging about recently/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /event sourcing and CQRS/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /How big are the teams/i })).not.toBeInTheDocument()
  })

  it('shrinks the chat textarea at mobile widths so the input stays above the fold', () => {
    setMatchMedia(true)
    const { unmount } = render(
      <HeroSection name="Simon Rowe" title="Head of Engineering" tagline="Tagline." />,
    )
    const mobileRows = screen.getByPlaceholderText(/Ask me anything about Simon/i).getAttribute('rows')
    unmount()

    setMatchMedia(false)
    render(<HeroSection name="Simon Rowe" title="Head of Engineering" tagline="Tagline." />)
    const desktopRows = screen.getByPlaceholderText(/Ask me anything about Simon/i).getAttribute('rows')

    expect(desktopRows).toBe('6')
    expect(Number(mobileRows)).toBeLessThan(Number(desktopRows))
  })

  it('starts the chat from a mobile prompt chip', () => {
    setMatchMedia(true)
    render(<HeroSection name="Simon Rowe" title="Head of Engineering" tagline="Tagline." />)

    fireEvent.click(screen.getByRole('button', { name: /What Spring Boot and Kafka patterns/i }))

    expect(openChat).toHaveBeenCalledWith('What Spring Boot and Kafka patterns does he use?')
  })

  it('submits hero chat text to the chat context', () => {
    setMatchMedia(false)
    render(
      <HeroSection
        name="Simon Rowe"
        title="Head of Engineering"
        tagline="Passionate about AI-native development."
      />,
    )

    fireEvent.change(screen.getByPlaceholderText(/Ask me anything about Simon/i), {
      target: { value: 'What is Simon blogging about?' },
    })
    fireEvent.click(screen.getByRole('button', { name: /send/i }))

    expect(openChat).toHaveBeenCalledWith('What is Simon blogging about?')
  })
})
