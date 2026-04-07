import { ArrowRight, Download, Github, Linkedin, MessageCircle, Twitter } from 'lucide-react'
import { useState } from 'react'
import type { SocialMediaLink } from '../../types/SocialMediaLink'
import { API_BASE_URL } from '../../config/api'
import { useChat } from '../../contexts/ChatContext'

interface HeroSectionProps {
  name: string
  title: string
  tagline: string
  cvUrl?: string | null
  backgroundImageUrl?: string | null
  socialMediaLinks?: SocialMediaLink[]
}

const socialIcons: Record<string, React.ReactNode> = {
  github: <Github size={20} />,
  linkedin: <Linkedin size={20} />,
  twitter: <Twitter size={20} />,
}

const SUGGESTED_PROMPTS = [
  'What Spring Boot and Kafka patterns does he use?',
  'What is he blogging about recently?',
  'How does he handle event sourcing and CQRS?',
]

export function HeroSection({ name, tagline, cvUrl, backgroundImageUrl, socialMediaLinks }: HeroSectionProps) {
  const bgUrl = backgroundImageUrl ? `${API_BASE_URL}${backgroundImageUrl}` : undefined
  const [chatInput, setChatInput] = useState('')
  const { openChat } = useChat()
  const nameParts = name.split(' ')
  const firstName = nameParts.slice(0, -1).join(' ')
  const lastName = nameParts[nameParts.length - 1]

  const displayLinks = socialMediaLinks?.filter((link, index, arr) => {
    return arr.findIndex(l => l.type === link.type) === index
  }) ?? []

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
        <p className="hero__badge">Engineering Leadership // AI-Native Systems</p>
        <h1 className="hero__name display-lg">{firstName} <span className="hero__name--accent">{lastName}</span></h1>
        <p className="hero__tagline">{tagline}</p>

        <p className="hero__chat-intro">
          <MessageCircle size={18} />
          Chat with an AI assistant trained on Simon's experience, skills, and career history.
        </p>

        <form className="hero__chat-input" onSubmit={handleChatSubmit}>
          <input
            type="text"
            placeholder="Ask about expertise, projects, or career..."
            value={chatInput}
            onChange={e => setChatInput(e.target.value)}
          />
          <button type="submit" disabled={!chatInput.trim()} aria-label="Send">
            <ArrowRight size={18} />
          </button>
        </form>

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

        <div className="hero__actions">
          {cvUrl && (
            <a href={`${API_BASE_URL}${cvUrl}`} target="_blank" rel="noopener noreferrer" className="button button--primary tour-download-cv">
              <Download size={16} /> Download CV
            </a>
          )}
          {displayLinks.length > 0 && (
            <div className="hero__social">
              {displayLinks.map(link => (
                <a
                  key={link.url}
                  href={link.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="hero__social-link"
                  title={link.name}
                >
                  {socialIcons[link.type] ?? link.type}
                </a>
              ))}
            </div>
          )}
        </div>
      </div>
    </section>
  )
}
