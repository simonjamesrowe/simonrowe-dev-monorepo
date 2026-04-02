import type { CSSProperties } from 'react'
import { useState } from 'react'
import { Download, MessageCircle } from 'lucide-react'

import { API_BASE_URL } from '../../config/api'
import type { Profile } from '../../types/Profile'
import { SiteSearch } from '../search/SiteSearch'
import { ChatPanel } from '../chat/ChatPanel'
import { RecaptchaGate } from '../chat/RecaptchaGate'

interface ProfileBannerProps {
  profile: Profile
  onDownloadCv?: () => void
}

export function ProfileBanner({ profile, onDownloadCv }: ProfileBannerProps) {
  const [chatOpen, setChatOpen] = useState(false)
  const [chatQuery, setChatQuery] = useState('')
  const [recaptchaVerified, setRecaptchaVerified] = useState(false)
  const [showRecaptcha, setShowRecaptcha] = useState(false)
  const [pendingQuery, setPendingQuery] = useState('')
  const [hasUsedChat, setHasUsedChat] = useState(false)

  const style = {
    '--desktop-bg': `url(${profile.backgroundImage.url})`,
    '--mobile-bg': `url(${profile.mobileBackgroundImage.url || profile.backgroundImage.url})`,
  } as CSSProperties

  const handleChatStart = (query: string) => {
    if (recaptchaVerified) {
      setChatQuery(query)
      setChatOpen(true)
      setHasUsedChat(true)
    } else {
      setPendingQuery(query)
      setShowRecaptcha(true)
    }
  }

  const handleRecaptchaVerified = () => {
    setRecaptchaVerified(true)
    setShowRecaptcha(false)
    setChatQuery(pendingQuery)
    setChatOpen(true)
    setHasUsedChat(true)
    setPendingQuery('')
  }

  const handleFabClick = () => {
    if (recaptchaVerified) {
      setChatOpen(true)
    } else {
      setPendingQuery(`Tell me about ${profile.name}`)
      setShowRecaptcha(true)
    }
  }

  return (
    <section className="profile-banner" data-testid="profile-banner" style={style}>
      <div className="profile-banner__content">
        <h1>{profile.name}</h1>
        <h2>{profile.title}</h2>
        <p className="profile-banner__headline">{profile.headline}</p>
        <div className="profile-banner__search-row">
          <SiteSearch onChatStart={handleChatStart} />
        </div>
        <a
          className="profile-banner__cv-link tour-download-cv"
          href={`${API_BASE_URL}/api/resume`}
          onClick={onDownloadCv}
          target="_blank"
          rel="noopener noreferrer"
        >
          <Download size={14} />
          Download CV
        </a>
      </div>
      {showRecaptcha && (
        <RecaptchaGate
          onVerified={handleRecaptchaVerified}
          onCancel={() => { setShowRecaptcha(false); setPendingQuery('') }}
        />
      )}
      {chatOpen && (
        <ChatPanel
          initialQuery={chatQuery}
          onClose={() => setChatOpen(false)}
          profileImageUrl={profile.sidebarImage.url}
        />
      )}
      {!chatOpen && hasUsedChat && (
        <button
          className="chat-fab"
          onClick={handleFabClick}
          aria-label="Open chat"
          title="Open chat"
        >
          <MessageCircle size={24} />
        </button>
      )}
    </section>
  )
}
