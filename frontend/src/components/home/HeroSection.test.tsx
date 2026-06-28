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
  })

  it('hides the badge, tagline, and prompt chips on mobile but keeps name, role, and chat input', () => {
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

    // Badge, tagline, and prompt chips are not rendered
    expect(screen.queryByText(/Engineering Leadership \/\/ AI-Native Systems/i)).not.toBeInTheDocument()
    expect(screen.queryByText('Passionate about AI-native development.')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /What Spring Boot and Kafka patterns/i })).not.toBeInTheDocument()
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
