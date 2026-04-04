import { ArrowRight, Download, Github, Linkedin, MessageCircle, Twitter } from 'lucide-react'
import { useState } from 'react'
import type { SocialMediaLink } from '../../types/SocialMediaLink'
import { API_BASE_URL } from '../../config/api'

interface HeroSectionProps {
  name: string
  title: string
  tagline: string
  cvUrl?: string | null
  backgroundImageUrl?: string | null
  socialMediaLinks?: SocialMediaLink[]
  onChatOpen: (query: string) => void
}

const socialIcons: Record<string, React.ReactNode> = {
  github: <Github size={20} />,
  linkedin: <Linkedin size={20} />,
  twitter: <Twitter size={20} />,
}

const SUGGESTED_PROMPTS = [
  'Tell me about his AI stack',
  'Leadership philosophy?',
  'Cloud-native experience?',
]

export function HeroSection({ name, title, tagline, cvUrl, backgroundImageUrl, socialMediaLinks, onChatOpen }: HeroSectionProps) {
  const bgUrl = backgroundImageUrl ? `${API_BASE_URL}${backgroundImageUrl}` : undefined
  const [chatInput, setChatInput] = useState('')
  const nameParts = name.split(' ')
  const firstName = nameParts.slice(0, -1).join(' ')
  const lastName = nameParts[nameParts.length - 1]

  const displayLinks = socialMediaLinks?.filter((link, index, arr) => {
    return arr.findIndex(l => l.type === link.type) === index
  }) ?? []

  const handleChatSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (chatInput.trim()) {
      onChatOpen(chatInput.trim())
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
      <div className="hero__grid">
        <div className="hero__left">
          <p className="hero__badge">Engineering Leadership // AI-Native Systems</p>
          <h1 className="hero__name display-lg">{firstName} <span className="hero__name--accent">{lastName}</span></h1>
          <p className="hero__tagline">{tagline}</p>

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

        <div className="hero__right">
          <div className="hero__chat-teaser tour-chat">
            <div className="hero__chat-teaser-header">
              <MessageCircle size={18} />
              <span>Ask me anything</span>
            </div>
            <div className="hero__chat-teaser-body">
              <p className="hero__chat-teaser-intro">
                Chat with an AI assistant trained on Simon's experience, skills, and career history.
              </p>
              <div className="hero__chat-teaser-prompts">
                {SUGGESTED_PROMPTS.map(prompt => (
                  <button
                    key={prompt}
                    className="hero__chat-teaser-chip"
                    onClick={() => onChatOpen(prompt)}
                    type="button"
                  >
                    {prompt}
                  </button>
                ))}
              </div>
              <form className="hero__chat-teaser-input" onSubmit={handleChatSubmit}>
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
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
