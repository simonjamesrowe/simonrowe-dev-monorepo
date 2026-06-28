import { ArrowRight, MessageCircle } from 'lucide-react'
import { useState } from 'react'
import { API_BASE_URL } from '../../config/api'
import { useChat } from '../../contexts/ChatContext'
import { useMediaQuery } from '../../hooks/useMediaQuery'

interface HeroSectionProps {
  name: string
  title: string
  tagline: string
  backgroundImageUrl?: string | null
}

const SUGGESTED_PROMPTS = [
  'What Spring Boot and Kafka patterns does he use?',
  'What is he blogging about recently?',
  'How does he handle event sourcing and CQRS?',
  "How big are the teams he's led?",
]

export function HeroSection({ name, title, tagline, backgroundImageUrl }: HeroSectionProps) {
  const bgUrl = backgroundImageUrl ? `${API_BASE_URL}${backgroundImageUrl}` : undefined
  const [chatInput, setChatInput] = useState('')
  const { openChat } = useChat()
  const isMobile = useMediaQuery('(max-width: 768px)')
  const nameParts = name.split(' ')
  const firstName = nameParts.slice(0, -1).join(' ')
  const lastName = nameParts[nameParts.length - 1]

  const handleChatSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (chatInput.trim()) {
      openChat(chatInput.trim())
      setChatInput('')
    }
  }

  return (
    <section className="hero">
      {bgUrl && (
        <div
          className="hero__bg"
          style={{ backgroundImage: `url(${bgUrl})` }}
        />
      )}
      <div className="hero__content">
        {!isMobile && <p className="hero__badge">Engineering Leadership // AI-Native Systems</p>}
        <h1 className="hero__name display-lg">{firstName} <span className="hero__name--accent">{lastName}</span></h1>
        <p className="hero__role">{title}</p>
        {!isMobile && <p className="hero__tagline">{tagline}</p>}

        <div className="hero__chat tour-home-chat">
          <p className="hero__chat-intro">
            <MessageCircle size={18} />
            Chat with an AI assistant trained on Simon's experience, stack, and career history.
          </p>

          <form className="hero__chat-input" onSubmit={handleChatSubmit}>
            <input
              type="text"
              placeholder="Ask me anything about Simon..."
              value={chatInput}
              onChange={e => setChatInput(e.target.value)}
            />
            <button type="submit" disabled={!chatInput.trim()} aria-label="Send">
              <ArrowRight size={18} />
            </button>
          </form>

          {!isMobile && (
            <div className="hero__prompts">
              {SUGGESTED_PROMPTS.map(prompt => (
                <button
                  key={prompt}
                  className="hero__prompt-chip"
                  onClick={() => openChat(prompt)}
                  type="button"
                >
                  {prompt}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </section>
  )
}
